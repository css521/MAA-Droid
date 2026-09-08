package com.aliothmoon.maadroid.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maadroid.engine.EngineRegistry
import com.aliothmoon.maadroid.engine.EngineSession
import com.aliothmoon.maadroid.engine.EngineTaskStore
import com.aliothmoon.maadroid.engine.LogLevel
import com.aliothmoon.maadroid.engine.TaskPanelSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

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
) : ViewModel() {

    /** 该引擎声明的面板；引擎未提供则为空表，UI 据此显示「该引擎暂无任务面板」 */
    val panels: List<TaskPanelSpec> =
        EngineRegistry.provider(engineId)?.ui?.taskPanels.orEmpty()

    val tasks: StateFlow<EngineTaskStore.EngineTasks> = store.flow(engineId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineTaskStore.EngineTasks())

    private val _expanded = MutableStateFlow<String?>(null)
    val expandedTaskType: StateFlow<String?> = _expanded.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 面向用户的最近一条状态；失败原因（含门闸的「请升级 App」）走这里 */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private var session: EngineSession? = null

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
        if (_running.value) return
        viewModelScope.launch {
            _running.value = true
            _status.value = null
            val s = sessionFactory(engineId).also { session = it }

            val failure = runCatching { s.prepare() }
                .getOrElse { "准备失败：${it.message}" }
            if (failure != null) {
                _status.value = failure
                _running.value = false
                s.close()
                session = null
                return@launch
            }

            val selected = store.selectedTasks(engineId)
            if (selected.isEmpty()) {
                _status.value = "未勾选任何任务"
                _running.value = false
                s.close()
                session = null
                return@launch
            }
            selected.forEach { (type, params) -> s.appendTask(type, params) }

            collectEvents(s)
            if (!s.start()) {
                _status.value = "引擎拒绝启动"
                _running.value = false
                s.close()
                session = null
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            session?.stop()
            session?.close()
            session = null
            _running.value = false
        }
    }

    /**
     * 订阅引擎事件。
     *
     * 只把**面向用户**的两类落到状态上：警告/错误日志与「全部任务结束」。
     * 其余按级别写 Timber —— 识别循环的 Debug 日志很密，全塞进 UI 会刷爆界面。
     */
    private fun collectEvents(s: EngineSession) {
        val events = s.events() ?: return
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    is com.aliothmoon.maadroid.engine.EngineEvent.Log -> {
                        Timber.tag(engineId).log(event.level.toTimberPriority(), event.message)
                        if (event.level >= LogLevel.Warn) _status.value = event.message
                    }
                    is com.aliothmoon.maadroid.engine.EngineEvent.Failure -> {
                        _status.value = event.reason
                    }
                    is com.aliothmoon.maadroid.engine.EngineEvent.AllTasksFinished -> {
                        _running.value = false
                        _status.value = if (event.success) "全部任务已完成" else "任务未全部完成"
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        session?.close()
        session = null
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
