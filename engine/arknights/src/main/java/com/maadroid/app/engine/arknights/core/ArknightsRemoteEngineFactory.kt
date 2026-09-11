package com.maadroid.app.engine.arknights.core

import android.os.IBinder
import com.maadroid.app.remote.EngineIds
import com.maadroid.app.remote.RemoteEngineFactory
import com.maadroid.app.third.Ln

/**
 * 明日方舟引擎在提权进程侧的工厂。
 *
 * MaaCore 是 native 库，必须跑在提权进程里（JNA 加载 libMaaCore.so，经 libbridge.so
 * 取帧与注入），所以方舟引擎的实现体在提权侧、App 侧只持有 AIDL 代理。
 * 边狱引擎则相反 —— Kotlin + OpenCV/ONNX 跑在 App 进程，只向提权进程要帧和输入。
 * [RemoteEngineFactory] 同时容纳这两种执行位置。
 *
 */
object ArknightsRemoteEngineFactory : RemoteEngineFactory {

    override val engineId: String = EngineIds.ARKNIGHTS

    /** 惰性：注册表只在首次取用时调用，未启用方舟时不会加载 MaaCore */
    override fun create(): IBinder = MaaCoreManager.maaService

    override fun close() = MaaCoreManager.destroy()

    /** MaaCore 的用户目录必须在跑任务前设好，原先这段写在 RemoteServiceImpl.setup 里 */
    override fun onRemoteSetup(userDir: java.io.File): String? {
        val ctx = MaaCoreManager.MaaContext ?: return "MaaContext is null (libMaaCore.so 未加载)"
        if (!ctx.AsstSetUserDir(userDir.path)) return "AsstSetUserDir($userDir) returned false"
        Ln.i("MaaCore ${ctx.AsstGetVersion()} userDir=$userDir")
        return null
    }

    override fun versionInfo(): String? =
        MaaCoreManager.MaaContext?.AsstGetVersion()
}
