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

    /**
     * 注册前完整校验：游戏 ID、包 ID 唯一，包归属正确，资源目录合法且互不重叠。
     * 同一 provider 实例重复注册是无操作；其余冲突抛出异常，不改变已注册方案。
     */
    @Synchronized
    fun register(provider: EngineProvider) {
        val profile = provider.profile
        val id = profile.id
        require(id.isNotBlank()) { "Engine id must not be blank" }
        val previous = providers[id]
        if (previous === provider) return
        require(previous == null) { "Engine id already registered: $id" }

        // These collections are local: a later invalid pack cannot reserve an earlier pack's id/root.
        val roots = LinkedHashMap<String, String>()
        for (pack in allResourcePacks()) roots[pack.packId] = normalizedRoot(pack)
        for (pack in profile.resourcePacks) {
            require(pack.engineId == id) { "Resource pack ${pack.packId} belongs to ${pack.engineId}, not $id" }
            require(pack.packId.isNotBlank()) { "Resource pack id must not be blank: $id" }
            require(pack.packId !in roots) { "Resource pack id already registered: ${pack.packId}" }
            val root = normalizedRoot(pack)
            val conflict = roots.entries.firstOrNull { (_, existing) ->
                root == existing || root.startsWith("$existing/") || existing.startsWith("$root/")
            }
            if (conflict != null) {
                throw IllegalArgumentException(
                    "Resource root ${pack.relativeRoot} (${pack.packId}) overlaps ${conflict.value} (${conflict.key})",
                )
            }
            roots[pack.packId] = root
        }
        providers[id] = provider
    }

    /** 注册顺序即 UI 上游戏列表的顺序 */
    @Synchronized
    fun profiles(): List<GameProfile> = providers.values.map { it.profile }

    @Synchronized
    fun provider(engineId: String): EngineProvider? = providers[engineId]

    /** 注册表只提供工厂，不持有运行实例；实例及其事件流属于创建它的会话。 */
    fun createEngine(engineId: String): AutomationEngine? = provider(engineId)?.createEngine()

    /** 所有引擎声明的资源包，供更新服务遍历 —— 这是「各自跟随各自上游」的入口 */
    @Synchronized
    fun allResourcePacks(): List<ResourcePackSpec> =
        providers.values.flatMap { it.profile.resourcePacks }

    @Synchronized
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
    @Synchronized
    fun resolveOrFallback(engineId: String?): GameProfile? {
        providers[engineId]?.let { return it.profile }
        return providers.values.firstOrNull()?.profile
    }

    /** 仅测试用：清空以避免用例间互相污染 */
    @Synchronized
    internal fun clearForTest() {
        providers.clear()
    }

    private fun normalizedRoot(pack: ResourcePackSpec): String {
        val root = pack.relativeRoot
        require(root.isNotBlank() && !root.startsWith('/') && '\\' !in root && '\u0000' !in root &&
            !Regex("^[A-Za-z]:").containsMatchIn(root)) {
            "Resource root must be a relative directory: ${pack.packId} ($root)"
        }
        val segments = root.split('/')
        require(".." !in segments) { "Resource root must not traverse parent directories: ${pack.packId} ($root)" }
        // File(base, root) treats ./ and repeated/trailing separators as aliases of the same directory.
        val normalized = segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        require(normalized.isNotEmpty()) { "Resource root must not be the app data root: ${pack.packId} ($root)" }
        return normalized
    }
}
