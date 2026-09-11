package com.maadroid.app.remote

import com.maadroid.app.engine.arknights.core.ArknightsRemoteEngineFactory
import com.maadroid.app.third.Ln

/**
 * 提权进程的引擎装配点。
 *
 * `RemoteServiceImpl` 住在 core-remote，游戏无关，不允许依赖 engine-*（否则依赖方向倒挂）。
 * 而提权进程由 `RootUserService` 反射实例化 `--class=` 指定的类，所以把装配放在这个
 * 由 `:app`（唯一依赖全部引擎的模块）提供的子类里：init 里注册全部引擎工厂，
 * 之后 `getEngineService(engineId)` 只查注册表。
 *
 * **新增一个游戏时，这里加一行注册即可**，core-* 与既有引擎均不需要改动。
 *
 * 必须保留无参构造：`RootUserService.instantiateService` 反射调用它。
 */
class MaaDroidRemoteService : RemoteServiceImpl() {

    init {
        RemoteEngineRegistry.register(ArknightsRemoteEngineFactory)
        // 边狱引擎跑在 App 进程（Kotlin + OpenCV/ONNX），只向提权进程要帧与输入，
        // 因此不在这里注册；见方案步骤 7。
        Ln.i("MaaDroidRemoteService: engines=${RemoteEngineRegistry.registeredIds()}")
    }
}
