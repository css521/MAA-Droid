package com.aliothmoon.maadroid.engine

import com.aliothmoon.maadroid.engine.arknights.ArknightsEngineProvider
import com.aliothmoon.maadroid.engine.limbus.LimbusEngine
import com.aliothmoon.maadroid.engine.limbus.LimbusProfile
import com.aliothmoon.maadroid.engine.limbus.ui.LimbusUi
import timber.log.Timber

/**
 * App 进程侧的引擎装配点。
 *
 * 与提权进程侧的 `MaaDroidRemoteService` 对称：那边装配跨进程的引擎服务，
 * 这边装配应用层的游戏方案。两处都只在 `:app` —— 唯一依赖全部引擎的模块。
 *
 * **接入第三个游戏时只需在这里加一行 [EngineRegistry.register]**，
 * core-* 与既有引擎均不改动。
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

/**
 * 边狱引擎供给。
 *
 * 每次 [createEngine] 都给一个新实例：引擎持有模板 Mat 缓存与设备句柄，
 * 复用会把上一次会话的原生资源带进新会话。
 */
private object LimbusEngineProvider : EngineProvider {
    override val profile: GameProfile = LimbusProfile

    override fun createEngine(): AutomationEngine = LimbusEngine()

    /** 边狱自带面板，宿主不认识它们的内部结构 */
    override val ui: EngineUi = LimbusUi
}
