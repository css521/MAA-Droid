package com.aliothmoon.maadroid.engine

/**
 * 一个游戏方案的完整供给：静态描述 + 引擎实现 + UI 插槽。
 *
 * 引擎模块对外只暴露这一个类型，宿主也只依赖这一个类型 —— 接入新游戏时宿主的改动
 * 收敛为「在 [EngineRegistry] 里多注册一个 provider」。
 */
interface EngineProvider {
    val profile: GameProfile

    /** 惰性创建：未被切换到的游戏不应加载其资源与原生库 */
    fun createEngine(): AutomationEngine

    val ui: EngineUi
}

/**
 * App 进程侧的引擎注册表（与提权进程侧的 `RemoteEngineRegistry` 是两张表，
 * 分别管应用层与进程层）。
 *
 * 由 `:app` 在启动时装配。宿主的游戏切换、资源中心、任务页都从这里取。
 */
object EngineRegistry {

    private val providers = LinkedHashMap<String, EngineProvider>()
    private val engines = HashMap<String, AutomationEngine>()

    fun register(provider: EngineProvider) {
        providers[provider.profile.id] = provider
    }

    /** 注册顺序即 UI 上游戏列表的顺序 */
    fun profiles(): List<GameProfile> = providers.values.map { it.profile }

    fun provider(engineId: String): EngineProvider? = providers[engineId]

    /** 惰性创建并缓存；同一引擎多次取用返回同一实例 */
    fun engine(engineId: String): AutomationEngine? {
        val p = providers[engineId] ?: return null
        return engines.getOrPut(engineId) { p.createEngine() }
    }

    /** 所有引擎声明的资源包，供更新服务遍历 —— 这是「各自跟随各自上游」的入口 */
    fun allResourcePacks(): List<ResourcePackSpec> =
        providers.values.flatMap { it.profile.resourcePacks }

    fun isRegistered(engineId: String): Boolean = engineId in providers
}
