package com.aliothmoon.maadroid.engine.arknights.core

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 一条 MaaCore Binder 连接对应的运行时。宿主负责串行启动/停止、准备资源和设备；
 * 本类负责实例、连接握手、完整任务队列与停止确认。资源加载顺序仍由资源加载器管理。
 *
 * 生命周期调用由同一个控制器串行执行。Native 回调可并发到达，而且可能早于
 * AsyncConnect 返回；回调不获取生命周期锁，也不通过异步转发延迟任务参数刷新。
 * 分步任务入口透传 native 返回值和异常，调用者负责失败后的 clearTasks/stop；
 * startTasks 则负责完整计划的失败清理。
 */
class MaaCoreSession(
    private val client: MaaCoreClient,
    private val onEvent: (Int, String?) -> Unit,
    private val connectTimeoutMillis: Long = 2_000,
    private val stopTimeoutMillis: Long = 60_000,
) {
    enum class Initialization { READY, BUSY, CREATE_FAILED, TOUCH_MODE_FAILED }

    data class Task(val type: String, val params: String)

    sealed interface StartResult {
        data object Started : StartResult
        data object Busy : StartResult
        data class RejectedTask(val index: Int) : StartResult
        data object Failed : StartResult
    }

    @Volatile private var initialized = false
    @Volatile private var instanceTerminated = false
    private val callbackOwner = AtomicReference<Any?>()
    private val pendingConnect = AtomicReference<PendingConnect?>()
    private val connectMutex = Mutex()

    val isRunning: Boolean get() = client.running()
    /** native Connect 也会操作设备，即使 AsstRunning 此时仍为 false。 */
    val isBusy: Boolean get() = pendingConnect.get()?.result?.isCompleted == false || client.running()
    val version: String get() = client.version()

    /** 仅清理已初始化的闲置实例；pending Connect 同样禁止修改队列。 */
    fun clearTasks(): Boolean {
        check(initialized) { "MaaCore session has not been initialized" }
        return !isBusy && client.stop()
    }

    /** 返回 native 的真实 taskId；忙碌时返回 0，不追加任务。 */
    fun appendTask(type: String, params: String): Int {
        check(initialized) { "MaaCore session has not been initialized" }
        if (isBusy) return 0
        return client.appendTask(type, params)
    }

    /** 允许在运行中的同步 TaskChainStart 回调内回写，不查询 running 或获取生命周期锁。 */
    fun setTaskParams(taskId: Int, params: String): Boolean {
        check(initialized) { "MaaCore session has not been initialized" }
        if (taskId <= 0 || pendingConnect.get()?.result?.isCompleted == false) return false
        return client.setTaskParams(taskId, params)
    }

    fun startQueuedTasks(): Boolean {
        check(initialized) { "MaaCore session has not been initialized" }
        return !isBusy && client.start()
    }

    fun initialize(): Initialization {
        if (initialized && client.hasInstance()) return Initialization.READY
        // 不销毁仍在执行的实例。新宿主接管闲置实例时必须重建，才能替换旧 Binder 回调。
        if (client.running()) return Initialization.BUSY
        invalidate()
        instanceTerminated = false
        val owner = Any()
        callbackOwner.set(owner)
        if (!client.createInstance { msg, json -> dispatch(owner, msg, json) }) {
            invalidate()
            return Initialization.CREATE_FAILED
        }
        if (!client.setInstanceOption(MaaInstanceOptions.TOUCH_MODE, MaaInstanceOptions.ANDROID)) {
            invalidate()
            return Initialization.TOUCH_MODE_FAILED
        }
        initialized = true
        return Initialization.READY
    }

    suspend fun connect(config: String, deployWithPause: Boolean): Boolean = connectMutex.withLock {
        check(initialized) { "MaaCore session has not been initialized" }
        // 取消等待不等于取消 native 调用。Stop 不会清除异步调用队列，旧 Connect
        // 完成之前不能重试连接，也不能让宿主把设备交给其他引擎。
        if (pendingConnect.get()?.result?.isCompleted == false) return@withLock false
        // 实例跨任务复用，关闭选项时也必须写入 0。
        if (!client.setInstanceOption(MaaInstanceOptions.DEPLOYMENT_WITH_PAUSE, if (deployWithPause) "1" else "0")) {
            return@withLock false
        }
        val pending = PendingConnect()
        pendingConnect.set(pending)
        try {
            val callId = client.asyncConnect(config)
            if (callId <= 0) {
                pending.result.complete(false)
                return@withLock false
            }
            pending.bind(callId)
            withTimeoutOrNull(connectTimeoutMillis) { pending.result.await() } == true
        } finally {
            // 保留超时/取消/传输异常后的未确认调用，直到实际完成或实例终止。
            if (pending.result.isCompleted) pendingConnect.compareAndSet(pending, null)
        }
    }

    /** 所有任务都接受后才能启动；拒绝其中一条时清空整个队列，避免运行残缺计划。 */
    fun startTasks(tasks: List<Task>, onAppended: (index: Int, taskId: Int) -> Unit): StartResult {
        check(initialized) { "MaaCore session has not been initialized" }
        if (isBusy) return StartResult.Busy
        // 上游 Stop 在非运行状态也会清空队列，清理此前启动失败留下的任务。
        if (!client.stop() || tasks.isEmpty()) return StartResult.Failed
        try {
            tasks.forEachIndexed { index, task ->
                val taskId = client.appendTask(task.type, task.params)
                if (taskId <= 0) {
                    client.stop()
                    return StartResult.RejectedTask(index)
                }
                onAppended(index, taskId)
            }
            if (client.start()) return StartResult.Started
            client.stop()
            return StartResult.Failed
        } catch (e: Exception) {
            // 保留原始异常，清理失败作为 suppressed 交给宿主诊断。
            runCatching { client.stop() }.exceptionOrNull()?.takeIf { it !== e }?.let(e::addSuppressed)
            throw e
        }
    }

    /** false 表示还不能确认停止，宿主必须保留设备占用并允许重试。 */
    suspend fun stop(): Boolean {
        if (instanceTerminated || !client.hasInstance()) return true
        val connecting = pendingConnect.get()
        if (connecting != null) {
            val completed = withTimeoutOrNull(stopTimeoutMillis) {
                connecting.result.await()
                true // 连接返回 false 也算已完成；此处关心有无迟到的设备操作。
            } == true
            if (!completed) return false
            pendingConnect.compareAndSet(connecting, null)
            if (instanceTerminated || !client.hasInstance()) return true
        }
        // 即使 Running 为 false，也要 Stop 清空未启动的队列或等待尚在连接的核心。
        if (!client.stop()) return false
        return withTimeoutOrNull(stopTimeoutMillis) {
            while (client.running()) delay(100)
            true
        } == true
    }

    /** Binder 失效时只废弃本地状态；绝不经由旧 Binder 触发销毁或重新加载 native。 */
    fun invalidate() {
        initialized = false
        callbackOwner.set(null)
        pendingConnect.getAndSet(null)?.result?.complete(false)
    }

    private fun dispatch(owner: Any, msg: Int, json: String?) {
        if (callbackOwner.get() !== owner) return
        if (msg == AsstMsg.AsyncCallInfo.value) {
            // MaaCore v6.17.2 Assistant::call_proc: what + async_call_id + details.ret。
            // Click/Screencap 的成功回调不能完成 Connect，也不能让解析异常冲出 Binder。
            val obj = runCatching { Json.parseToJsonElement(json ?: "") as? JsonObject }.getOrNull()
                ?: return
            if ((obj["what"] as? JsonPrimitive)?.content != "Connect") return
            val id = (obj["async_call_id"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return
            val details = obj["details"] as? JsonObject ?: return
            val success = (details["ret"] as? JsonPrimitive)?.booleanOrNull ?: return
            pendingConnect.get()?.let { pending ->
                pending.accept(id, success)
                if (pending.result.isCompleted) pendingConnect.compareAndSet(pending, null)
            }
        } else {
            if (msg == AsstMsg.Destroyed.value) {
                // Destroyed 在 C++ 析构末尾回调，此时服务侧指针可能还未置空。
                instanceTerminated = true
                invalidate()
            }
            onEvent(msg, json)
        }
    }

    private class PendingConnect {
        val result = CompletableDeferred<Boolean>()
        private var id: Int? = null
        private val early = mutableMapOf<Int, Boolean>()

        @Synchronized fun bind(callId: Int) {
            id = callId
            early[callId]?.let(result::complete)
            early.clear()
        }

        @Synchronized fun accept(callId: Int, success: Boolean) {
            if (id == callId) result.complete(success)
            else if (id == null) early[callId] = success
        }
    }
}
