package com.aliothmoon.maadroid.engine.arknights

import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.engine.RemoteEngineDevice
import com.aliothmoon.maadroid.remote.RemoteDeviceHandle
import java.io.Closeable

/** Resolve the engine before touching the legacy foreground/background display. */
internal class ArknightsDeviceAdapter(
    private val remote: RemoteService,
    private val openDisplay: () -> RemoteDeviceHandle,
) : RemoteEngineDevice, Closeable {
    private val lock = Any()
    private var closed = false
    private var handle: RemoteDeviceHandle? = null

    private fun display() = synchronized(lock) {
        check(!closed) { "方舟设备适配器已关闭" }
        handle ?: openDisplay().also {
            // Display preparation can synchronously re-enter host cleanup.
            if (closed) {
                it.close()
                error("方舟设备适配器已关闭")
            }
            handle = it
        }
    }

    override val displaySpec get() = display().displaySpec
    override val displayId get() = display().displayId
    override val frames get() = display().frames
    override val input get() = display().input
    override val control get() = display().control

    override fun engineService(engineId: String) = synchronized(lock) {
        check(!closed) { "方舟设备适配器已关闭" }
        remote.getEngineService(engineId).also { check(!closed) { "方舟设备适配器已关闭" } }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        // This handle has no independent display lease. Closing it leaves manual preview alive.
        val previous = handle
        handle = null
        previous?.close()
    }
}
