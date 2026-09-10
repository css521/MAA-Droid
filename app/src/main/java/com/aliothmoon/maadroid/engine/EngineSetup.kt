package com.aliothmoon.maadroid.engine

import com.aliothmoon.maadroid.engine.arknights.ArknightsEngineProvider
import com.aliothmoon.maadroid.engine.limbus.LimbusEngineProvider
import timber.log.Timber

/**
 * App 进程侧的引擎装配点。
 *
 * 与提权进程侧的 `MaaDroidRemoteService` 对称：那边装配跨进程的引擎服务，
 * 这边装配应用层的游戏方案。两处都只在 `:app` —— 唯一依赖全部引擎的模块。
 *
 * 新模块实现 provider 并接入构建依赖后，在这里增加 [EngineRegistry.register]。
 * 通用任务页按注册表装配；需要自有提权服务时另在 MaaDroidRemoteService 注册工厂。
 */
object EngineSetup {

    @Volatile
    private var done = false

    /** 由 Application.onCreate 调用；幂等 */
    @Synchronized
    fun install() {
        if (done) return
        EngineRegistry.register(ArknightsEngineProvider())
        EngineRegistry.register(LimbusEngineProvider)
        done = true
        Timber.i("EngineSetup: engines=%s", EngineRegistry.profiles().map { it.id })
    }
}
