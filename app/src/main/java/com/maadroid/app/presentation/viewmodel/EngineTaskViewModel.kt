package com.maadroid.app.presentation.viewmodel

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maadroid.app.R
import com.maadroid.app.common.i18n.UiText
import com.maadroid.app.common.i18n.uiTextOf
import com.maadroid.app.engine.EngineRegistry
import com.maadroid.app.engine.EngineDeviceSession
import com.maadroid.app.engine.EngineSession
import com.maadroid.app.engine.EngineTaskStore
import com.maadroid.app.engine.LogLevel
import com.maadroid.app.engine.TaskPanelSpec
import com.maadroid.app.engine.isRecoverableEngineFailure
import com.maadroid.app.diagnostics.AppDiagnostics
import com.maadroid.app.presentation.state.EngineTaskExecutionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 驱动「按引擎声明渲染的任务页」。
 *
 * 只与三样东西打交道：[EngineRegistry]（有哪些面板）、[EngineTaskStore]（勾了什么、
 * 参数是什么）、[EngineSession]（跑起来）。**不认识任何具体引擎** —— 这是宿主侧
 * 「加一个游戏不改宿主」的最后一环。
 *
 * 与方舟现有的 `BackgroundTaskViewModel` 并存：那个绑着 `TaskChainNode`/profiles，
 * 等方舟收拢为 `AutomationEngine` 后才会合并过来。
 */
class EngineTaskViewModel(
    private val engineId: String,
    private val store: EngineTaskStore,
    private val sessionFactory: (String) -> EngineSession,
    private val executionState: EngineTaskExecutionState,
    private val canStart: () -> Boolean = { true },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val previewDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val quickActions: EngineTaskQuickActions? = null,
) : ViewModel(scope) {

    /** 该引擎声明的面板；引擎未提供则为空表，UI 据此显示「该引擎暂无任务面板」 */
    val panels: List<TaskPanelSpec> =
        EngineRegistry.provider(engineId)?.ui?.taskPanels.orEmpty()
    val workspace = EngineRegistry.provider(engineId)?.ui?.workspace

    private val _workspaceDraft = MutableStateFlow<String?>(null)
    val workspaceDraft = _workspaceDraft.asStateFlow()
    private var saveJob: Job? = null
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    fun onWorkspaceChange(configJson: String) {
        if (_running.value) return
        _workspaceDraft.value = configJson
        val previous = saveJob
        saveJob = viewModelScope.launch {
            previous?.join()
            try { store.setWorkspaceConfig(engineId, configJson) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _status.value = UiText.Dynamic("保存配置失败：${error.message}") }
        }
    }

    val tasks: StateFlow<EngineTaskStore.EngineTasks> = store.flow(engineId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineTaskStore.EngineTasks())

    private val _expanded = MutableStateFlow<String?>(null)
    val expandedTaskType: StateFlow<String?> = _expanded.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    private val _previewReady = MutableStateFlow(false)
    val previewReady: StateFlow<Boolean> = _previewReady.asStateFlow()
    private val previewSurface = AtomicReference<Surface?>(null)
    private val previewMutex = Mutex()
    private var previewJob: Job? = null

    val mutedGamePackage: StateFlow<String> = quickActions?.mutedPackage ?: MutableStateFlow("")
    val gamePackageName: String? get() = session?.gamePackageName

    suspend fun readGameFps(): Float? = withContext(previewDispatcher) {
        val current = session
        if (!_previewReady.value || current == null) return@withContext null
        try {
            current.readGameFps()?.takeIf { session === current && it.isFinite() && it >= 0f }
        } catch (failure: Throwable) {
            if (failure is CancellationException || !failure.isRecoverableEngineFailure()) throw failure
            null
        }
    }

    fun onToggleGameSound() {
        val current = session ?: return
        val pkg = current.gamePackageName ?: return
        if (!_previewReady.value || _stopping.value) return
        runQuickAction {
            if (session === current && current.previewReady.value) {
                check(quickActions?.toggleGameSound(pkg) == true) { "游戏声音设置失败" }
            }
        }
    }

    fun onScreenOff() = runQuickAction { quickActions?.turnScreenOff() }

    private fun runQuickAction(action: suspend () -> Unit) {
        viewModelScope.launch(previewDispatcher) {
            try { action() }
            catch (failure: Throwable) {
                if (failure is CancellationException || !failure.isRecoverableEngineFailure()) throw failure
                // A menu operation failing is not an automation crash.
                _status.value = UiText.Dynamic(failure.message ?: "快捷操作失败")
            }
        }
    }

    /** Explicit user action: stop automation and close this game's owned display/process. */
    fun onCloseGame() {
        val current = session ?: return
        if (!_stopping.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                startJob?.cancelAndJoin()
                if (session === current) {
                    val pkg = current.gamePackageName
                    current.closeGame()
                    restoreGameSound(pkg)
                    closeSession()
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException || !failure.isRecoverableEngineFailure()) throw failure
                _status.value = UiText.Dynamic(failure.message ?: "关闭游戏失败")
            } finally {
                _stopping.value = false
            }
        }
    }

    private suspend fun restoreGameSound(packageName: String?) {
        if (packageName == null) return
        try {
            if (quickActions?.onGameClosed(packageName) == false) {
                _status.value = uiTextOf(R.string.bg_toast_mute_failed)
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException || !failure.isRecoverableEngineFailure()) throw failure
            _status.value = UiText.Dynamic(failure.message ?: "恢复游戏声音失败")
        }
    }

    /** Capture the current device once; queued events must not look up a newer session. */
    fun openPreviewInput(): PreviewInput? {
        if (!_previewReady.value || _stopping.value) return null
        val input = session?.openManualInput() ?: return null
        return PreviewInput(input, viewModelScope, previewDispatcher)
    }

    class PreviewInput internal constructor(
        private val input: EngineDeviceSession.ManualInput,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val events = Channel<(EngineDeviceSession.ManualInput) -> Unit>(Channel.UNLIMITED)

        init {
            scope.launch(dispatcher) {
                try {
                    for (event in events) if (!closed.get()) event(input)
                } finally {
                    closed.set(true)
                    events.cancel()
                    input.close()
                }
            }.invokeOnCompletion {
                // Also release if the scope was cancelled before the worker first ran.
                close()
                input.close()
            }
        }

        fun touchDown(x: Int, y: Int, contact: Int) = enqueue { it.touchDown(x, y, contact) }
        fun touchMove(x: Int, y: Int, contact: Int) = enqueue { it.touchMove(x, y, contact) }
        fun touchUp(x: Int, y: Int, contact: Int) = enqueue { it.touchUp(x, y, contact) }

        private fun enqueue(event: (EngineDeviceSession.ManualInput) -> Unit) {
            if (!closed.get()) events.trySend(event)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) events.close()
        }
    }

    fun onPreviewSurfaceAvailable(surface: Surface) {
        previewSurface.set(surface)
        session?.let { s -> viewModelScope.launch { syncPreview(s) } }
    }

    fun onPreviewSurfaceDestroyed(surface: Surface) {
        if (previewSurface.compareAndSet(surface, null)) {
            session?.let { s -> viewModelScope.launch { syncPreview(s) } }
        }
    }

    private suspend fun syncPreview(s: EngineSession) = withContext(previewDispatcher) {
        previewMutex.withLock {
            if (session !== s) return@withLock
            try { s.setPreviewSurface(previewSurface.get()) }
            catch (failure: Throwable) {
                if (!failure.isRecoverableEngineFailure()) throw failure
                AppDiagnostics.failure(engineId, "preview.surface", failure)
                _status.value = uiTextOf(R.string.engine_preview_error_detail, failure.message.orEmpty())
            }
        }
    }

    /** 面向用户的最近一条状态；失败原因（含门闸的「请升级 App」）走这里 */
    private val _status = MutableStateFlow<UiText?>(null)
    val status: StateFlow<UiText?> = _status.asStateFlow()
    private val _diagnosticFailure = MutableStateFlow<UiText?>(null)
    val diagnosticFailure: StateFlow<UiText?> = _diagnosticFailure.asStateFlow()

    private fun reportFailure(message: UiText) {
        _status.value = message
        _diagnosticFailure.value = message
    }

    @Volatile private var session: EngineSession? = null
    private var startJob: Job? = null
    private var eventsJob: Job? = null
    private var ownsDevice = false

    fun isEnabled(panel: TaskPanelSpec): Boolean =
        tasks.value.enabled[panel.taskType] ?: panel.enabledByDefault

    fun paramsOf(panel: TaskPanelSpec): String = tasks.value.params[panel.taskType] ?: ""

    fun onEnabledChange(panel: TaskPanelSpec, enabled: Boolean) {
        viewModelScope.launch { store.setEnabled(engineId, panel.taskType, enabled) }
    }

    fun onParamsChange(panel: TaskPanelSpec, paramsJson: String) {
        viewModelScope.launch { store.setParams(engineId, panel.taskType, paramsJson) }
    }

    fun onToggleExpand(taskType: String?) {
        _expanded.value = taskType
    }

    /**
     * 起跑。
     *
     * 顺序上有一处不能省：**先 prepare 再 appendTask**。prepare 里含兼容门闸，
     * 门闸不过时要把原因原样报给用户（「请升级 App」这类），而不是继续下发任务
     * 然后崩在某个节点上。
     */
    fun start() {
        // 在 launch 之前占位，快速连点 / 同时从深链进入也只能准备一次。
        if (_stopping.value || _running.value) return
        if (!canStart() || !executionState.tryAcquire(engineId)) {
            _status.value = uiTextOf(R.string.engine_other_game_running)
            return
        }
        ownsDevice = true
        _running.value = true
        _status.value = null
        _diagnosticFailure.value = null
        AppDiagnostics.record(engineId, "ui.start.clicked")
        startJob = viewModelScope.launch {
            val previous = session
            val previousPackage = previous?.gamePackageName
            try {
                saveJob?.join()
                workspace?.let { w ->
                    val saved = store.current(engineId)
                    val config = _workspaceDraft.value ?: saved.workspaceConfig ?: w.initialConfig(saved.enabled, saved.params)
                    w.validate(config)?.let { reason ->
                        _status.value = UiText.Dynamic(reason)
                        releaseTaskReservation()
                        return@launch
                    }
                    // 验证和下发使用同一份快照，不能在最后一次持久化失败后跑旧配置。
                    store.setWorkspaceConfig(engineId, config)
                }
                val selected = store.selectedTasks(engineId)
                if (selected.isEmpty()) {
                    _status.value = uiTextOf(R.string.engine_no_tasks_selected)
                    releaseTaskReservation()
                    return@launch
                }

                // EngineSession.prepare adopts the retained device for this engine. Closing
                // it here would discard the playable preview and restart the game on each run.
                previewJob?.cancel()
                previewJob = null
                session = null
                _previewReady.value = false
                val s = sessionFactory(engineId).also { session = it }
                _logs.value = emptyList()
                observePreview(s)
                syncPreview(s)
                collectEvents(s)
                val failure = s.prepare()
                ensureActive()
                if (failure != null) {
                    reportFailure(UiText.Dynamic(failure))
                    closeFailedStart(previous, previousPackage)
                    return@launch
                }
                s.gamePackageName?.let { pkg ->
                    if (quickActions?.onGameReady(pkg) == false) {
                        _status.value = uiTextOf(R.string.bg_toast_mute_failed)
                    }
                }
                selected.forEach { (type, params) ->
                    check(s.appendTask(type, params) != com.maadroid.app.engine.AutomationEngine.INVALID_TASK_ID) { "引擎拒绝任务 $type" }
                }

                if (!s.start()) {
                    reportFailure(uiTextOf(R.string.engine_start_rejected))
                    closeFailedStart(previous, previousPackage)
                }
            } catch (cancelled: CancellationException) {
                // stop() 先等启动协程退出，再释放它的会话，不能在取消后继续 append/start。
                if (previous?.previewReady?.value == true) {
                    withContext(NonCancellable) { closeFailedStart(previous, previousPackage) }
                }
                throw cancelled
            } catch (error: Throwable) {
                if (!error.isRecoverableEngineFailure()) throw error
                AppDiagnostics.failure(engineId, "ui.start.failed", error)
                reportFailure(uiTextOf(R.string.engine_start_failed, "${error.javaClass.simpleName}: ${error.message.orEmpty()}"))
                closeFailedStart(previous, previousPackage)
            }
        }
    }

    private fun observePreview(current: EngineSession) {
        previewJob?.cancel()
        _previewReady.value = current.previewReady.value
        previewJob = viewModelScope.launch {
            current.previewReady.collect { if (session === current) _previewReady.value = it }
        }
    }

    private suspend fun closeFailedStart(previous: EngineSession?, previousPackage: String?) {
        if (previous != null && session === previous && previous.previewReady.value) {
            releaseTaskReservation()
            return
        }
        if (!closeSession()) return
        if (previous?.previewReady?.value == true) {
            // Preparation failed before transfer. Its predecessor still owns the playable
            // display; observe it again, including a later cross-game handoff invalidation.
            session = previous
            observePreview(previous)
            syncPreview(previous)
        } else {
            // The failed replacement already consumed the old display. Restore its audio
            // marker too, since neither session has a package getter after disposal.
            restoreGameSound(previousPackage)
        }
    }

    fun stop() {
        if (!_running.value || !_stopping.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                startJob?.cancelAndJoin()
                closeSession(keepPreview = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!error.isRecoverableEngineFailure()) throw error
                AppDiagnostics.failure(engineId, "ui.stop.failed", error)
                reportFailure(uiTextOf(R.string.engine_stop_failed, error.message.orEmpty()))
            } finally {
                _stopping.value = false
            }
        }
    }

    /**
     * 订阅引擎事件。
     *
     * 只把**面向用户**的两类落到状态上：警告/错误日志与「全部任务结束」。
     * 其余按级别写 Timber —— 识别循环的 Debug 日志很密，全塞进 UI 会刷爆界面。
     */
    private fun collectEvents(s: EngineSession) {
        eventsJob?.cancel()
        val events = s.events() ?: return
        // SharedFlow 无重放：先订阅再 start，避免瞬间完成的任务丢掉结束事件。
        eventsJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { event ->
                if (session !== s || _stopping.value) return@collect
                when (event) {
                    is com.maadroid.app.engine.EngineEvent.Log -> {
                        Timber.tag(engineId).log(event.level.toTimberPriority(), event.message)
                        if (event.level >= LogLevel.Info) _logs.value = (_logs.value + "[${event.level}] ${event.message}").takeLast(500)
                        if (event.level >= LogLevel.Warn) _status.value = UiText.Dynamic(event.message)
                    }
                    is com.maadroid.app.engine.EngineEvent.Failure -> {
                        event.cause?.let { AppDiagnostics.failure(engineId, "engine.failure", it) }
                            ?: AppDiagnostics.record(engineId, "engine.failure", event.reason)
                        reportFailure(UiText.Dynamic(event.reason))
                        _logs.value = (_logs.value + "[Error] ${event.reason}").takeLast(500)
                    }
                    is com.maadroid.app.engine.EngineEvent.Task -> {
                        if (event.phase == com.maadroid.app.engine.TaskPhase.Failed) {
                            event.message?.takeIf(String::isNotBlank)?.let { reason ->
                                // A pipeline's reported failure is a task result, not an engine
                                // crash. Preserve its actual reason when the terminal event arrives.
                                reportFailure(UiText.Dynamic(reason))
                                _logs.value = (_logs.value + "[Error] ${event.type}: $reason").takeLast(500)
                            }
                        }
                    }
                    is com.maadroid.app.engine.EngineEvent.AllTasksFinished -> {
                        AppDiagnostics.record(engineId, "engine.finished", "success=${event.success}")
                        val terminal = uiTextOf(
                            if (event.success) R.string.engine_tasks_completed
                            else R.string.engine_tasks_incomplete,
                        )
                        if (event.success) _status.value = terminal
                        else reportFailure(_diagnosticFailure.value ?: terminal)
                        if (quickActions?.closeOnTaskEnd == true) {
                            val pkg = s.gamePackageName
                            try {
                                s.closeGame()
                                restoreGameSound(pkg)
                                closeSession()
                            } catch (failure: Throwable) {
                                if (failure is CancellationException || !failure.isRecoverableEngineFailure()) throw failure
                                _status.value = UiText.Dynamic(failure.message ?: "自动关闭游戏失败")
                                closeSession(keepPreview = true)
                            }
                        } else closeSession(keepPreview = true)
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        // ViewModel scope 已取消，收尾必须等启动/运行退出后才能解映射。
        cleanupScope.launch {
            startJob?.cancelAndJoin()
            closeSession()
        }
    }

    private suspend fun closeSession(keepPreview: Boolean = false): Boolean = withContext(NonCancellable) {
        val collector = eventsJob
        val current = session
        val packageName = current?.gamePackageName
        val stopped = try {
            if (keepPreview) current?.finishTask() != false
            else { current?.close(); true }
        } catch (error: Throwable) {
            if (!error.isRecoverableEngineFailure()) throw error
            AppDiagnostics.failure(engineId, "ui.cleanup.failed", error)
            reportFailure(uiTextOf(R.string.engine_stop_failed, error.message.orEmpty()))
            return@withContext false
        }
        // An idle native flag alone does not prove an asynchronous connection has stopped.
        if (!stopped) {
            reportFailure(uiTextOf(R.string.engine_stop_rejected))
            return@withContext false
        }
        eventsJob = null
        if (!keepPreview || current?.previewReady?.value != true) {
            previewJob?.cancel()
            previewJob = null
            session = null
            _previewReady.value = false
            restoreGameSound(packageName)
        }
        releaseTaskReservation()
        // Update terminal state before cancelling a collector that may be this coroutine.
        collector?.cancel()
        true
    }

    private fun releaseTaskReservation() {
        _running.value = false
        if (ownsDevice) {
            executionState.release(engineId)
            ownsDevice = false
        }
    }

    private companion object {
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** 引擎日志级别 → Timber 优先级。两边都是「越大越严重」，但常量值不同 */
private fun LogLevel.toTimberPriority(): Int = when (this) {
    LogLevel.Trace -> android.util.Log.VERBOSE
    LogLevel.Debug -> android.util.Log.DEBUG
    LogLevel.Info -> android.util.Log.INFO
    LogLevel.Warn -> android.util.Log.WARN
    LogLevel.Error -> android.util.Log.ERROR
}
