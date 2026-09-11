package com.maadroid.app.remote.internal

/** Serializes the process-wide capturer, including legacy display mutations. */
internal class DeviceSessionLease {
    private var owner: Any? = null

    @Synchronized
    fun <T> acquire(token: Any, isBusy: () -> Boolean, start: () -> T): T {
        check(owner == null && !isBusy()) { "设备显示或帧通道正被其它会话占用" }
        owner = token
        try {
            return start()
        } catch (failure: Throwable) {
            owner = null
            throw failure
        }
    }

    @Synchronized
    fun <T> use(token: Any, action: () -> T): T {
        check(owner === token) { "设备会话已关闭" }
        return action()
    }

    @Synchronized
    fun release(token: Any, cleanup: () -> Unit) {
        if (owner !== token) return
        try {
            cleanup()
        } finally {
            owner = null
        }
    }

    @Synchronized
    fun <T> legacy(action: () -> T): T {
        check(owner == null) { "设备正被通用引擎会话占用" }
        return action()
    }
}
