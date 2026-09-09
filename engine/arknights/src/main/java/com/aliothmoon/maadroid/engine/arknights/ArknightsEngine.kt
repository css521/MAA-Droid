package com.aliothmoon.maadroid.engine.arknights

import com.aliothmoon.maadroid.MaaCoreService
import com.aliothmoon.maadroid.engine.AutomationEngine
import com.aliothmoon.maadroid.engine.ConnectionState
import com.aliothmoon.maadroid.engine.DeviceHandle
import com.aliothmoon.maadroid.engine.EngineDiagnosticSink
import com.aliothmoon.maadroid.engine.EngineEvent
import com.aliothmoon.maadroid.engine.RemoteEngineDevice
import com.aliothmoon.maadroid.engine.TaskPhase
import com.aliothmoon.maadroid.engine.arknights.core.AidlMaaCoreClient
import com.aliothmoon.maadroid.engine.arknights.core.AsstMsg
import com.aliothmoon.maadroid.engine.arknights.core.MaaCoreClient
import com.aliothmoon.maadroid.engine.arknights.core.MaaCoreSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * One app-side owner of a remote MaaCore instance. Resource preparation precedes device
 * acquisition; the host retains ownership of the display after this engine is released.
 *
 * [onRawEvent] runs synchronously on the native callback path. Existing Arknights business
 * callbacks can therefore update a task's parameters before the callback returns. The shared
 * event flow is for observation, never for time-sensitive SetTaskParams calls.
 */
class ArknightsEngine internal constructor(
    private val resources: MaaResourcePreparation,
    private val options: () -> MaaRunOptions,
    private val onRawEvent: (Int, String?) -> Unit,
    private val clientFactory: (RemoteEngineDevice) -> MaaCoreClient,
    private val eventScope: CoroutineScope,
    private val connectTimeoutMillis: Long = 2_000,
    private val stopTimeoutMillis: Long = 60_000,
) : AutomationEngine {
    constructor(
        resources: MaaResourcePreparation,
        options: () -> MaaRunOptions,
        onRawEvent: (Int, String?) -> Unit = { _, _ -> },
    ) : this(resources, options, onRawEvent, { device ->
        val binder = checkNotNull(device.engineService(ArknightsProfile.id)) { "远程服务未提供明日方舟引擎" }
        AidlMaaCoreClient(checkNotNull(MaaCoreService.Stub.asInterface(binder)))
    }, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    override val profile = ArknightsProfile
    private val lifecycle = Mutex()
    // Never hold this monitor across Binder calls: TaskChainStart can call SetTaskParams back.
    private val stateLock = Any()
    @Volatile private var session: MaaCoreSession? = null
    @Volatile private var prepared: MaaRunOptions? = null
    @Volatile private var connected = false
    @Volatile private var released = false
    @Volatile private var invalidated = false
    @Volatile private var stopConfirmed = true
    @Volatile private var stopRequested = false
    private var queueAccepted = true
    private val tasks = linkedMapOf<Int, QueuedTask>()
    private var run: Run? = null
    @Volatile private var diagnosticSink: EngineDiagnosticSink? = null

    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events = mutableEvents.asSharedFlow()
    // Only lifecycle transitions enter this queue. Potentially large Raw events are best
    // effort; they cannot fill a queue and displace the terminal event that releases a run.
    private val transitions = Channel<EngineEvent>(Channel.UNLIMITED)
    private var relayStarted = false

    override val isRunning: Boolean
        get() = !released && !invalidated && !stopConfirmed &&
            (runCatching { session?.isBusy == true }.getOrDefault(!stopConfirmed))

    val version: String get() = if (released || invalidated || stopConfirmed) "" else session?.version.orEmpty()

    override fun setDiagnosticSink(sink: EngineDiagnosticSink?) { diagnosticSink = sink }

    override suspend fun prepare(resourceDir: File): Result<Unit> = lifecycle.withLock {
        attempt {
            check(!released && !invalidated && !stopRequested && session == null) { "方舟会话不可重新准备，请创建新引擎" }
            prepared = null
            val snapshot = options()
            require(ArknightsPackages[snapshot.clientType] != null) { "未知方舟客户端：${snapshot.clientType}" }
            resources.prepare(resourceDir, snapshot).getOrThrow()
            currentCoroutineContext().ensureActive()
            check(!invalidated) { "方舟远程服务已断开" }
            prepared = snapshot
            trace("resources.ready", "client=${snapshot.clientType}")
        }
    }

    override suspend fun connect(device: DeviceHandle): Result<Unit> = lifecycle.withLock {
        attempt {
            check(!released && !invalidated && !stopRequested && session == null) { "方舟会话已经连接或关闭" }
            val snapshot = checkNotNull(prepared) { "请先准备方舟资源" }
            val remote = device as? RemoteEngineDevice ?: error("设备不支持远程自动化引擎")
            val client = clientFactory(remote)
            val owner = MaaCoreSession(client, ::onNativeEvent, connectTimeoutMillis, stopTimeoutMillis)
            synchronized(stateLock) {
                check(!invalidated) { "方舟远程服务已断开" }
                session = owner
                stopConfirmed = false
            }
            publish(EngineEvent.Connection(ConnectionState.Connecting))
            when (val result = owner.initialize()) {
                MaaCoreSession.Initialization.READY -> Unit
                else -> throw MaaInitializationException(result)
            }
            check(!invalidated) { "方舟远程服务已断开" }
            check(owner.clearTasks()) { "无法清理方舟任务队列" }
            check(!invalidated) { "方舟远程服务已断开" }
            // The host may prepare its display lazily. BUSY/failed initialization must not
            // touch display metadata; engineService lookup must remain independent of it.
            val display = remote.displaySpec
            val displayId = remote.displayId
            require(displayId >= 0) { "无效游戏显示器：$displayId" }
            val config = buildJsonObject {
                put("library_path", "libbridge.so")
                put("screen_resolution", buildJsonObject {
                    put("width", display.width)
                    put("height", display.height)
                })
                put("display_id", displayId)
                put("force_stop", true)
            }.toString()
            check(owner.connect(config, snapshot.deployWithPause)) { "MaaCore 连接失败或超时" }
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                check(!invalidated) { "方舟远程服务已断开" }
                connected = true
                publish(EngineEvent.Connection(ConnectionState.Connected))
            }
        }.onFailure {
            if (it !is CancellationException) publish(EngineEvent.Connection(ConnectionState.Failed, it.message))
        }
    }

    override fun appendTask(type: String, paramsJson: String): Int {
        // Reserve the whole append, including registration of the native id. Otherwise Start
        // can race the Binder call and execute a partial plan, or Stop can clear it underneath us.
        if (!lifecycle.tryLock()) {
            synchronized(stateLock) { if (run == null) queueAccepted = false }
            return AutomationEngine.INVALID_TASK_ID
        }
        return try {
            val owner = synchronized(stateLock) {
                if (!connected || released || invalidated || stopRequested || run != null || !queueAccepted) {
                    return AutomationEngine.INVALID_TASK_ID
                }
                session ?: return AutomationEngine.INVALID_TASK_ID
            }
            require(type.isNotBlank() && isObject(paramsJson)) { "无效方舟任务或参数" }
            val id = owner.appendTask(type, paramsJson)
            synchronized(stateLock) {
                check(!invalidated) { "方舟远程服务已断开" }
                check(id > 0 && id !in tasks) { "MaaCore 拒绝任务：$type" }
                tasks[id] = QueuedTask(type)
            }
            id
        } catch (failure: Exception) {
            synchronized(stateLock) { queueAccepted = false }
            failure("追加方舟任务失败", failure)
            AutomationEngine.INVALID_TASK_ID
        } finally { lifecycle.unlock() }
    }

    override fun setTaskParams(taskId: Int, paramsJson: String): Boolean {
        val owner = synchronized(stateLock) {
            if (!connected || released || invalidated || stopRequested || !queueAccepted ||
                run?.finished == true || taskId !in tasks || !isObject(paramsJson)) return false
            session ?: return false
        }
        return try { owner.setTaskParams(taskId, paramsJson) } catch (failure: Exception) {
            failure("更新方舟任务参数失败", failure)
            false
        }
    }

    override suspend fun start(): Boolean = lifecycle.withLock {
        currentCoroutineContext().ensureActive()
        val owner = synchronized(stateLock) {
            if (!connected || released || invalidated || stopRequested || !queueAccepted || tasks.isEmpty() || run != null) return@withLock false
            session ?: return@withLock false
        }
        try {
            if (owner.isBusy) return@withLock false
            synchronized(stateLock) {
                if (invalidated || stopRequested || !queueAccepted) return@withLock false
                run = Run()
                stopConfirmed = false
            }
            if (owner.startQueuedTasks()) {
                // A very short task can finish synchronously inside Start. Do not overwrite
                // its terminal state after returning from Binder.
                currentCoroutineContext().ensureActive()
                !invalidated
            } else {
                failure("MaaCore 未接受启动请求")
                finish(false)
                false
            }
        } catch (cancelled: CancellationException) {
            stopRequested = true
            throw cancelled
        } catch (failure: Exception) {
            synchronized(stateLock) { queueAccepted = false }
            failure("启动方舟任务失败", failure)
            finish(false)
            false
        }
    }

    override suspend fun stop(): Boolean = lifecycle.withLock {
        currentCoroutineContext().ensureActive()
        if (released || invalidated || stopConfirmed) return@withLock true
        val owner = session ?: return@withLock true
        stopRequested = true
        val stopped = try { owner.stop() } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            failure("停止方舟任务失败", failure)
            false
        }
        // Cancellation after the last native call is still not a successful stop result.
        currentCoroutineContext().ensureActive()
        if (stopped) {
            synchronized(stateLock) {
                stopConfirmed = true
                connected = false
                finish(false)
                tasks.clear()
            }
            owner.invalidate()
        }
        stopped
    }

    /** A dead remote process cannot still inject input. No stale Binder call is made here. */
    fun invalidate() {
        val owner = synchronized(stateLock) {
            if (released || invalidated) return
            invalidated = true
            connected = false
            stopConfirmed = true
            publish(EngineEvent.Connection(ConnectionState.Disconnected))
            finish(false)
            session
        }
        owner?.invalidate()
    }

    override fun release() {
        check(lifecycle.tryLock()) { "方舟生命周期操作尚未完成" }
        try {
            if (released) return
            check(stopConfirmed) { "尚未确认 MaaCore 停止，不能释放会话" }
            released = true
            session?.invalidate()
            session = null
            prepared = null
            connected = false
            synchronized(stateLock) { tasks.clear(); run = null }
            transitions.close()
            diagnosticSink = null
        } finally { lifecycle.unlock() }
    }

    private fun onNativeEvent(msg: Int, json: String?) {
        val kind = AsstMsg.fromValue(msg)
        val data = runCatching { Json.parseToJsonElement(json ?: "") as? JsonObject }.getOrNull()
        if (!acceptsCallback(kind, data)) return
        try { onRawEvent(msg, json) } catch (error: Exception) {
            if (!acceptsCallback(kind, data)) return
            synchronized(stateLock) { run?.failed = true }
            failure("方舟业务回调处理失败", error)
        }
        // A synchronous business callback may itself invalidate this engine.
        if (!acceptsCallback(kind, data)) return
        mutableEvents.tryEmit(EngineEvent.Raw(msg.toString(), json ?: "{}"))
        when (kind) {
            AsstMsg.TaskChainStart -> taskEvent(data, TaskPhase.Started)
            AsstMsg.TaskChainCompleted -> taskEvent(data, TaskPhase.Completed)
            AsstMsg.TaskChainError -> taskEvent(data, TaskPhase.Failed)
            AsstMsg.TaskChainStopped -> {
                taskEvent(data, TaskPhase.Stopped)
                finish(false)
            }
            AsstMsg.AllTasksCompleted -> finish(!stopRequested)
            AsstMsg.InitFailed, AsstMsg.InternalError -> {
                synchronized(stateLock) { run?.failed = true }
                failure("MaaCore 错误：${AsstMsg.fromValue(msg)?.name}")
            }
            AsstMsg.Destroyed -> invalidate()
            else -> Unit
        }
    }

    private fun acceptsCallback(kind: AsstMsg?, data: JsonObject?): Boolean = synchronized(stateLock) {
        if (released || invalidated || stopConfirmed) return@synchronized false
        if (kind == AsstMsg.Destroyed) return@synchronized true
        if (run?.finished == true) return@synchronized false
        // Task callbacks outside this engine's single run belong to an old native queue.
        when (kind) {
            AsstMsg.TaskChainStart, AsstMsg.TaskChainCompleted, AsstMsg.TaskChainError,
            AsstMsg.TaskChainStopped, AsstMsg.TaskChainExtraInfo -> {
                val id = (data?.get("taskid") as? JsonPrimitive)?.intOrNull
                run != null && (id == null || id in tasks)
            }
            AsstMsg.AllTasksCompleted,
            AsstMsg.SubTaskStart, AsstMsg.SubTaskCompleted, AsstMsg.SubTaskError,
            AsstMsg.SubTaskStopped, AsstMsg.SubTaskExtraInfo -> run != null
            else -> true
        }
    }

    private fun taskEvent(data: JsonObject?, phase: TaskPhase) = synchronized(stateLock) {
        val id = (data?.get("taskid") as? JsonPrimitive)?.intOrNull ?: return@synchronized
        val task = tasks[id] ?: return@synchronized
        val currentRun = run ?: return@synchronized
        if (released || invalidated || currentRun.finished || task.phase == phase ||
            task.phase in listOf(TaskPhase.Completed, TaskPhase.Failed, TaskPhase.Stopped)) return@synchronized
        task.phase = phase
        if (phase == TaskPhase.Failed || phase == TaskPhase.Stopped) currentRun.failed = true
        publish(EngineEvent.Task(id, task.type, phase))
    }

    private fun finish(success: Boolean) = synchronized(stateLock) {
        val currentRun = run ?: return@synchronized
        if (currentRun.finished) return@synchronized
        currentRun.finished = true
        if (!success) tasks.forEach { (id, task) ->
            if (task.phase == null || task.phase == TaskPhase.Started) {
                task.phase = TaskPhase.Stopped
                publish(EngineEvent.Task(id, task.type, TaskPhase.Stopped))
            }
        }
        publish(EngineEvent.AllTasksFinished(success && !currentRun.failed))
    }

    private suspend fun attempt(block: suspend () -> Unit): Result<Unit> = try {
        currentCoroutineContext().ensureActive()
        block()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        failure("方舟引擎准备失败", failure)
        Result.failure(failure)
    }

    private fun failure(reason: String, cause: Throwable? = null) = publish(EngineEvent.Failure(reason, cause))

    private fun publish(event: EngineEvent) {
        synchronized(stateLock) {
            if (released) return
            if (!relayStarted) {
                relayStarted = true
                eventScope.launch {
                    for (next in transitions) {
                        mutableEvents.emit(next)
                        traceEvent(next)
                    }
                }
            }
            transitions.trySend(event)
        }
    }

    // Diagnostic sinks are external code too: never invoke them under the task-state monitor.
    private fun traceEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Task -> trace("task.${event.phase.name.lowercase()}", "taskId=${event.taskId} type=${event.type}")
            is EngineEvent.AllTasksFinished -> trace("tasks.finished", "success=${event.success}")
            is EngineEvent.Connection -> trace("connection.${event.state.name.lowercase()}")
            is EngineEvent.Failure -> trace("engine.failure", "${event.reason} cause=${event.cause?.javaClass?.simpleName.orEmpty()}")
            else -> Unit
        }
    }

    private fun trace(phase: String, detail: String = "") {
        try { diagnosticSink?.record(phase, detail.take(2048)) } catch (_: Exception) { /* optional diagnostics */ }
    }

    private fun isObject(json: String) = runCatching { Json.parseToJsonElement(json) is JsonObject }.getOrDefault(false)
    private data class QueuedTask(val type: String, var phase: TaskPhase? = null)
    private class Run(var failed: Boolean = false, var finished: Boolean = false)
}

class MaaInitializationException(val phase: MaaCoreSession.Initialization) :
    IllegalStateException("MaaCore 实例初始化失败：$phase")
