package com.aliothmoon.maadroid.engine

import com.aliothmoon.maadroid.engine.arknights.ArknightsProfile
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
        EngineRegistry.register(ArknightsEngineProvider)
        EngineRegistry.register(LimbusEngineProvider)
        done = true
        Timber.i("EngineSetup: engines=%s", EngineRegistry.profiles().map { it.id })
    }
}

/**
 * 方舟引擎供给。
 *
 * [createEngine] 暂未实现 —— MaaCompositionService 已委托引擎模块的 MaaCoreSession
 * 管理核心生命周期，资源准备与业务回调仍走旧编排，尚未收拢成 AutomationEngine。
 * 但 profile 与资源包已可用，宿主的游戏列表与资源中心因此已能同时看到两个游戏。
 */
private object ArknightsEngineProvider : EngineProvider {
    override val profile: GameProfile = ArknightsProfile

    override fun createEngine(): AutomationEngine =
        TODO("方舟引擎待从 MaaCompositionService 收拢为 AutomationEngine 实现")

    override val ui: EngineUi = object : EngineUi {
        // 方舟现有任务面板仍挂在宿主导航里，随 presentation/view/panel 迁入时改为这里供给
        override val taskPanels: List<TaskPanelSpec> = emptyList()
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
