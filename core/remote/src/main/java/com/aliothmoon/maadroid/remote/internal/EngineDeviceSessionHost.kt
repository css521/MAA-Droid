package com.aliothmoon.maadroid.remote.internal

import android.os.IBinder
import com.aliothmoon.maadroid.IEngineDeviceSession
import com.aliothmoon.maadroid.constant.DisplayMode
import com.aliothmoon.maadroid.input.InputControlUtils
import com.aliothmoon.maadroid.third.Ln
import com.aliothmoon.maadroid.third.wrappers.ServiceManager

/** The native BGR capturer is a singleton. Never adopt or stop another display. */
internal class EngineDeviceSessionHost(private val legacyFrames: FrameChannel) {
    private val lease = DeviceSessionLease()

    fun <T> legacy(action: () -> T): T = lease.legacy(action)

    fun open(owner: IBinder, mode: Int, width: Int, height: Int, dpi: Int): IEngineDeviceSession {
        require(mode == DisplayMode.BACKGROUND) { "通用引擎暂不支持前台模式的输入，请使用后台模式" }
        require(width > 0 && height > 0 && dpi > 0 && width.toLong() * height <= Int.MAX_VALUE / 3) {
            "无效显示规格: ${width}x$height@$dpi"
        }
        val session = Session(owner, width, height, dpi)
        return lease.acquire(session, {
            VirtualDisplayManager.isRunning() || PrimaryDisplayManager.isRunning() || legacyFrames.isOpen()
        }) {
            try {
                owner.linkToDeath(session, 0)
                check(owner.isBinderAlive) { "设备会话持有者已退出" }
                VirtualDisplayManager.setResolution(width, height, dpi)
                session.captureStarted = true
                session.id = VirtualDisplayManager.start()
                check(session.id != VirtualDisplayManager.DISPLAY_NONE) { "创建虚拟显示及截图捕获失败" }
                PowerController.startUserActivityKeepAlive(session.id)
                check(owner.isBinderAlive) { "设备会话持有者已退出" }
                session
            } catch (failure: Throwable) {
                session.cleanup()
                throw failure
            }
        }
    }

    private inner class Session(
        private val owner: IBinder,
        private val width: Int,
        private val height: Int,
        private val dpi: Int,
    ) : IEngineDeviceSession.Stub(), IBinder.DeathRecipient {
        var id = VirtualDisplayManager.DISPLAY_NONE
        var captureStarted = false
        private val frames = FrameChannel()
        private val heldKeys = mutableSetOf<Int>()

        override fun getDisplayId(): Int = lease.use(this) { id }
        override fun openFrameChannel() = lease.use(this) { frames.open(width, height) }
        override fun grabFrame(): LongArray? = lease.use(this) { frames.grab() }
        override fun closeFrameChannel() = lease.use(this) { frames.close() }
        override fun matchesDisplaySpec(width: Int, height: Int, dpi: Int): Boolean = lease.use(this) {
            this.width == width && this.height == height && this.dpi == dpi
        }

        override fun startApp(packageName: String): Boolean = lease.use(this) {
            ActivityUtils.startApp(packageName, id)
        }

        override fun stopApp(packageName: String) = lease.use(this) {
            ServiceManager.getActivityManager().forceStopPackage(packageName)
        }

        override fun touchDown(x: Int, y: Int, contact: Int) = lease.use(this) {
            check(InputControlUtils.down(x, y, contact, id)) { "触摸按下注入失败" }
        }

        override fun touchMove(x: Int, y: Int, contact: Int) = lease.use(this) {
            check(InputControlUtils.move(x, y, contact, id)) { "触摸移动注入失败" }
        }

        override fun touchUp(x: Int, y: Int, contact: Int) = lease.use(this) {
            check(InputControlUtils.up(x, y, contact, id)) { "触摸抬起注入失败" }
        }

        override fun touchCancel() = lease.use(this) { InputControlUtils.cancel(id) }
        override fun keyDown(keyCode: Int) = lease.use(this) {
            heldKeys.add(keyCode)
            check(InputControlUtils.keyDown(keyCode, id)) { "按键按下注入失败" }
        }

        override fun keyUp(keyCode: Int) = lease.use(this) {
            check(InputControlUtils.keyUp(keyCode, id)) { "按键抬起注入失败" }
            heldKeys.remove(keyCode)
            Unit
        }

        override fun binderDied() = close()
        override fun close() = lease.release(this) { cleanup() }

        fun cleanup() {
            // Each cleanup is independent: an OEM input/power failure must not leak the capturer.
            runCatching { owner.unlinkToDeath(this, 0) }
            runCatching { frames.close() }
            if (captureStarted) {
                runCatching { InputControlUtils.cancel(id) }
                heldKeys.forEach { key -> runCatching { InputControlUtils.keyUp(key, id) } }
                heldKeys.clear()
                runCatching { PowerController.stopUserActivityKeepAlive() }
                runCatching { VirtualDisplayManager.stop() }
                    .onFailure { Ln.e("Device session display cleanup failed", it) }
                captureStarted = false
            }
        }
    }
}
