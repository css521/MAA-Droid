package com.aliothmoon.maadroid.engine

/**
 * 一个游戏方案的完整供给：静态描述 + 引擎实现 + UI 插槽。
 *
 * 引擎模块对外只暴露这一个类型，宿主也只依赖这一个类型 —— 接入新游戏时宿主的改动
 * 收敛为「在 [EngineRegistry] 里多注册一个 provider」。
 */
interface EngineProvider {
    val profile: GameProfile

    /** 每次创建独立的运行实例；调用者持有它，并在 stop 后 release。 */
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

    fun register(provider: EngineProvider) {
        providers[provider.profile.id] = provider
    }

    /** 注册顺序即 UI 上游戏列表的顺序 */
    fun profiles(): List<GameProfile> = providers.values.map { it.profile }

    fun provider(engineId: String): EngineProvider? = providers[engineId]

    /** 注册表只提供工厂，不持有运行实例；实例及其事件流属于创建它的会话。 */
    fun createEngine(engineId: String): AutomationEngine? = providers[engineId]?.createEngine()

    /** 所有引擎声明的资源包，供更新服务遍历 —— 这是「各自跟随各自上游」的入口 */
    fun allResourcePacks(): List<ResourcePackSpec> =
        providers.values.flatMap { it.profile.resourcePacks }

    fun isRegistered(engineId: String): Boolean = engineId in providers

    /**
     * 把存下来的游戏 id 解析成实际可用的方案。
     *
     * 存的是字符串而不是枚举，所以必须处理两种真实情形：
     * - 用户曾选的游戏在新版里被移除
     * - 反过来，旧版存的 id 在当前构建里还没注册（例如按 ABI 裁剪掉了某个引擎）
     *
     * 两种都回落到第一个已注册的方案，而不是让宿主拿着一个空引擎渲染空白页。
     * 没有任何引擎时返回 null —— 那是装配出错，应由调用方明确报错。
     */
    fun resolveOrFallback(engineId: String?): GameProfile? {
        providers[engineId]?.let { return it.profile }
        return providers.values.firstOrNull()?.profile
    }

    /** 仅测试用：清空以避免用例间互相污染 */
    internal fun clearForTest() {
        providers.clear()
    }
}
