package com.maadroid.app.engine

import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App 进程内的跨引擎准入。覆盖下载/资源换档到任务停止，不依赖哪个页面可见。
 * 同一引擎的资源准备可嵌套在启动中；另一引擎不能借此重启共享的提权进程。
 * 提权进程的显示租约仍负责最终设备互斥及 Binder 死亡回收。
 */
class EngineExecutionCoordinator {
    private val reservations = LinkedHashMap<Lease, Reservation>()
    private val active = MutableStateFlow<String?>(null)
    val activeEngineId = active.asStateFlow()

    @Synchronized
    fun tryStart(engineId: String): Lease? {
        require(engineId.isNotBlank())
        if (reservations.values.any { it.running || it.engineId != engineId }) return null
        return reserve(engineId, running = true)
    }

    @Synchronized
    fun tryPrepareResources(engineId: String): Lease? {
        require(engineId.isNotBlank())
        if (reservations.values.any { it.engineId != engineId }) return null
        return reserve(engineId, running = false)
    }

    private fun reserve(engineId: String, running: Boolean): Lease = Lease(this).also {
        reservations[it] = Reservation(engineId, running)
        active.value = engineId
    }

    @Synchronized
    private fun release(lease: Lease) {
        reservations.remove(lease)
        active.value = reservations.values.firstOrNull()?.engineId
    }

    /** 按对象身份释放；旧会话重复 close 不会释放同一游戏后来的会话。 */
    class Lease internal constructor(private val coordinator: EngineExecutionCoordinator) : Closeable {
        override fun close() = coordinator.release(this)
    }

    private data class Reservation(val engineId: String, val running: Boolean)

    companion object {
        val shared = EngineExecutionCoordinator()
    }
}
