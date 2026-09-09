package com.aliothmoon.maadroid.remote

import android.content.Intent
import android.os.SharedMemory
import android.view.Surface
import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.IEngineDeviceSession
import com.aliothmoon.maadroid.engine.DeviceControl
import com.aliothmoon.maadroid.engine.DeviceHandle
import com.aliothmoon.maadroid.engine.Frame
import com.aliothmoon.maadroid.engine.FrameSource
import com.aliothmoon.maadroid.engine.InputSink
import java.nio.ByteBuffer

/**
 * 把提权进程的 `RemoteService` 适配成 engine-api 的设备句柄。
 *
 * 这是「跑在 App 进程的引擎」与「跑在提权进程的能力」之间的桥。方舟引擎不用它
 * （MaaCore 在提权进程内直接访问帧缓冲与输入），边狱引擎全靠它。
 *
 * @param width 逻辑宽，取自 `GameProfile.display`
 * @param height 逻辑高
 */
class RemoteDeviceHandle(
    private val service: RemoteService,
    width: Int,
    height: Int,
    private val session: IEngineDeviceSession? = null,
) : DeviceHandle {

    override val frames: FrameSource = SharedMemoryFrameSource(
        width, height,
        openChannel = { if (session != null) session.openFrameChannel() else service.openFrameChannel(width, height) },
        grabFrame = { if (session != null) session.grabFrame() else service.grabFrame() },
        closeChannel = { if (session != null) session.closeFrameChannel() else service.closeFrameChannel() },
    )

    override val input: InputSink = session?.let(::SessionInputSink) ?: RemoteInputSink(service)

    override val control: DeviceControl = session?.let { SessionDeviceControl(service, it) }
        ?: RemoteDeviceControl(service)

    fun setPreviewSurface(surface: Surface?) {
        if (session != null) session.setPreviewSurface(surface) else service.setMonitorSurface(surface)
    }

    fun close() {
        try {
            frames.close()
        } finally {
            session?.close()
        }
    }
}

/**
 * 共享内存帧源。映射只做一次，之后每次 [grab] 只是让提权侧拷一帧再读元数据。
 */
private class SharedMemoryFrameSource(
    private val width: Int,
    private val height: Int,
    private val openChannel: () -> SharedMemory?,
    private val grabFrame: () -> LongArray?,
    private val closeChannel: () -> Unit,
) : FrameSource {

    private var memory: SharedMemory? = null
    private var mapped: ByteBuffer? = null
    private var closed = false

    override suspend fun grab(): Frame? = synchronized(this) {
        if (closed) return@synchronized null
        val buf = ensureMapped() ?: return@synchronized null
        // meta = [width, height, stride, seq]；null 表示无可用帧或提权侧容量不足
        val meta = grabFrame() ?: return@synchronized null
        val bytes = BgrFrameLayout.byteCount(meta, width, height, buf.capacity())
        Frame(
            width = meta[0].toInt(),
            height = meta[1].toInt(),
            stride = meta[2].toInt(),
            seq = meta[3],
            buffer = buf.asReadOnlyBuffer().apply { clear(); limit(bytes) },
        )
    }

    private fun ensureMapped(): ByteBuffer? {
        mapped?.let { return it }
        val shm = openChannel() ?: return null
        return try {
            // 只读映射：帧由提权侧写入，App 侧不应改动
            shm.mapReadOnly().also {
                memory = shm
                mapped = it
            }
        } catch (failure: Throwable) {
            shm.close()
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val opened = memory != null
        mapped?.let { runCatching { SharedMemory.unmap(it) } }
        mapped = null
        memory?.let { runCatching { it.close() } }
        memory = null
        // A never-opened legacy handle must not close somebody else's global channel.
        if (opened) runCatching { closeChannel() }
    }
}

private class SessionInputSink(private val session: IEngineDeviceSession) : InputSink {
    override fun touchDown(x: Int, y: Int, contact: Int) = session.touchDown(x, y, contact)
    override fun touchMove(x: Int, y: Int, contact: Int) = session.touchMove(x, y, contact)
    override fun touchUp(x: Int, y: Int, contact: Int) = session.touchUp(x, y, contact)
    override fun touchCancel() = session.touchCancel()
    override fun keyDown(keyCode: Int) = session.keyDown(keyCode)
    override fun keyUp(keyCode: Int) = session.keyUp(keyCode)
}

private class SessionDeviceControl(
    private val service: RemoteService,
    private val session: IEngineDeviceSession,
) : DeviceControl {
    override fun startApp(packageName: String): Boolean = session.startApp(packageName)
    override fun stopApp(packageName: String) = session.stopApp(packageName)
    override fun isAppAlive(packageName: String): Boolean = service.isAppAlive(packageName) == AppAliveStatus.ALIVE
    override fun isPackageInstalled(packageName: String): Boolean = service.isPackageInstalled(packageName)
    // A connected engine may confirm its spec, but must not restart the live capturer.
    override fun setDisplaySize(width: Int, height: Int, dpi: Int): Boolean =
        session.matchesDisplaySpec(width, height, dpi)
}

private class RemoteInputSink(private val service: RemoteService) : InputSink {
    override fun touchDown(x: Int, y: Int, contact: Int) {
        runCatching { service.touchDown(x, y, contact) }
    }

    override fun touchMove(x: Int, y: Int, contact: Int) {
        runCatching { service.touchMove(x, y, contact) }
    }

    override fun touchUp(x: Int, y: Int, contact: Int) {
        runCatching { service.touchUp(x, y, contact) }
    }

    override fun touchCancel() {
        runCatching { service.touchCancel() }
    }

    override fun keyDown(keyCode: Int) {
        runCatching { service.keyDown(keyCode) }
    }

    override fun keyUp(keyCode: Int) {
        runCatching { service.keyUp(keyCode) }
    }
}

private class RemoteDeviceControl(private val service: RemoteService) : DeviceControl {

    /**
     * 拉起游戏。用 LAUNCHER 类别的 Intent 而非 monkey —— 后者会带上调试标记，
     * 部分游戏会据此拒绝启动。
     */
    override fun startApp(packageName: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        service.startActivity(intent)
    }.getOrDefault(false)

    override fun stopApp(packageName: String) {
        runCatching { service.forceStopApp(packageName) }
    }

    /** isAppAlive 返回三态（见 AppAliveStatus），此处只关心「活着」 */
    override fun isAppAlive(packageName: String): Boolean = runCatching {
        service.isAppAlive(packageName) == AppAliveStatus.ALIVE
    }.getOrDefault(false)

    override fun isPackageInstalled(packageName: String): Boolean = runCatching {
        service.isPackageInstalled(packageName)
    }.getOrDefault(false)

    override fun setDisplaySize(width: Int, height: Int, dpi: Int): Boolean = runCatching {
        service.setVirtualDisplayResolution(width, height, dpi)
        true
    }.getOrDefault(false)
}
