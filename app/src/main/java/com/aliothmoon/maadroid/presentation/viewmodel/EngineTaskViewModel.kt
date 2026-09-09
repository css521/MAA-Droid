package com.aliothmoon.maadroid.presentation.viewmodel

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.common.i18n.UiText
import com.aliothmoon.maadroid.common.i18n.uiTextOf
import com.aliothmoon.maadroid.engine.EngineRegistry
import com.aliothmoon.maadroid.engine.EngineSession
import com.aliothmoon.maadroid.engine.EngineTaskStore
import com.aliothmoon.maadroid.engine.LogLevel
import com.aliothmoon.maadroid.engine.TaskPanelSpec
import com.aliothmoon.maadroid.engine.isRecoverableEngineFailure
import com.aliothmoon.maadroid.diagnostics.AppDiagnostics
import com.aliothmoon.maadroid.presentation.state.EngineTaskExecutionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
        AppDiagnostics.record(engineId, "ui.start.clicked")
        startJob = viewModelScope.launch {
            try {
                saveJob?.join()
                workspace?.let { w ->
                    val saved = store.current(engineId)
                    val config = _workspaceDraft.value ?: saved.workspaceConfig ?: w.initialConfig(saved.enabled, saved.params)
                    w.validate(config)?.let { reason ->
                        _status.value = UiText.Dynamic(reason)
                        closeSession()
                        _running.value = session?.isRunning == true
                        return@launch
                    }
                    // 验证和下发使用同一份快照，不能在最后一次持久化失败后跑旧配置。
                    store.setWorkspaceConfig(engineId, config)
                }
                val selected = store.selectedTasks(engineId)
                if (selected.isEmpty()) {
                    _status.value = uiTextOf(R.string.engine_no_tasks_selected)
                    closeSession()
                    _running.value = session?.isRunning == true
                    return@launch
                }

                val s = sessionFactory(engineId).also { session = it }
                _logs.value = emptyList()
                previewJob = viewModelScope.launch { s.previewReady.collect { _previewReady.value = it } }
                syncPreview(s)
                collectEvents(s)
                val failure = s.prepare()
                ensureActive()
                if (failure != null) {
                    _status.value = UiText.Dynamic(failure)
                    closeSession()
                    _running.value = session?.isRunning == true
                    return@launch
                }
                selected.forEach { (type, params) ->
                    check(s.appendTask(type, params) != com.aliothmoon.maadroid.engine.AutomationEngine.INVALID_TASK_ID) { "引擎拒绝任务 $type" }
                }

                if (!s.start()) {
                    _status.value = uiTextOf(R.string.engine_start_rejected)
                    closeSession()
                    _running.value = session?.isRunning == true
                }
            } catch (cancelled: CancellationException) {
                // stop() 先等启动协程退出，再释放它的会话，不能在取消后继续 append/start。
                throw cancelled
            } catch (error: Throwable) {
                if (!error.isRecoverableEngineFailure()) throw error
                AppDiagnostics.failure(engineId, "ui.start.failed", error)
                _status.value = uiTextOf(R.string.engine_start_failed, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                closeSession()
                _running.value = session?.isRunning == true
            }
        }
    }

    fun stop() {
        if (!_running.value || !_stopping.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                startJob?.cancelAndJoin()
                if (session?.stop() == false) {
                    _status.value = uiTextOf(R.string.engine_stop_rejected)
                } else {
                    closeSession()
                    _running.value = session?.isRunning == true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!error.isRecoverableEngineFailure()) throw error
                AppDiagnostics.failure(engineId, "ui.stop.failed", error)
                _status.value = uiTextOf(R.string.engine_stop_failed, error.message.orEmpty())
                if (session?.isRunning != true) {
                    closeSession()
                    _running.value = session?.isRunning == true
                }
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
                    is com.aliothmoon.maadroid.engine.EngineEvent.Log -> {
                        Timber.tag(engineId).log(event.level.toTimberPriority(), event.message)
                        if (event.level >= LogLevel.Info) _logs.value = (_logs.value + "[${event.level}] ${event.message}").takeLast(500)
                        if (event.level >= LogLevel.Warn) _status.value = UiText.Dynamic(event.message)
                    }
                    is com.aliothmoon.maadroid.engine.EngineEvent.Failure -> {
                        event.cause?.let { AppDiagnostics.failure(engineId, "engine.failure", it) }
                            ?: AppDiagnostics.record(engineId, "engine.failure", event.reason)
                        _status.value = UiText.Dynamic(event.reason)
                        _logs.value = (_logs.value + "[Error] ${event.reason}").takeLast(500)
                    }
                    is com.aliothmoon.maadroid.engine.EngineEvent.AllTasksFinished -> {
                        AppDiagnostics.record(engineId, "engine.finished", "success=${event.success}")
                        _status.value = uiTextOf(
                            if (event.success) R.string.engine_tasks_completed
                            else R.string.engine_tasks_incomplete,
                        )
                        closeSession()
                        _running.value = session?.isRunning == true
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

    private suspend fun closeSession() = withContext(NonCancellable) {
        val collector = eventsJob
        if (collector != currentCoroutineContext()[Job]) collector?.cancel()
        eventsJob = null
        previewJob?.cancel()
        previewJob = null
        try { session?.close() }
        catch (error: Throwable) {
            if (!error.isRecoverableEngineFailure()) throw error
            AppDiagnostics.failure(engineId, "ui.cleanup.failed", error)
            _status.value = uiTextOf(R.string.engine_stop_failed, error.message.orEmpty())
        } finally {
            // Failed stops keep the reservation and stop button available for retry.
            if (session?.isRunning != true) {
                session = null
                _previewReady.value = false
                if (ownsDevice) {
                    executionState.release(engineId)
                    ownsDevice = false
                }
            }
            collector?.cancel()
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
