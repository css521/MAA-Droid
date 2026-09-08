package com.aliothmoon.maadroid.engine.limbus.action

/**
 * 动作注册表。
 *
 * 上游 LALC 用 Python 装饰器 `@TaskExecution.register("name")` 注册动作，我们对应为
 * 一张显式表：**表里有哪些名字，决定了资源包的兼容门闸能否通过**
 * （`manifest.required_actions - ActionRegistry.names()` 非空即拒绝装载并提示升级 App）。
 *
 * 之所以留 [ActionBackend] 这一层而不直接存函数：资源包热更能覆盖流水线、模板、模型，
 * 但覆盖不了 Kotlin 代码。将来若要让上游改动作代码也能热更，只需再实现一个脚本后端
 * （QuickJS/Lua）注册进同一张表，调用方无感。本轮只落 Kotlin 后端。
 */
object ActionRegistry {

    private val backends = LinkedHashMap<String, ActionBackend>()

    fun register(name: String, backend: ActionBackend) {
        backends[name] = backend
    }

    fun registerAll(vararg pairs: Pair<String, ActionBackend>) {
        pairs.forEach { (n, b) -> register(n, b) }
    }

    operator fun get(name: String): ActionBackend? = backends[name]

    fun names(): Set<String> = backends.keys.toSet()

    /**
     * 兼容门闸：资源包声明需要但本 App 未实现的动作。
     * 非空即应拒绝装载该资源包 —— 上游新增动作时用户会看到「需升级 App」而不是跑到一半崩。
     */
    fun missing(required: Collection<String>): List<String> =
        required.filterNot { it in backends }.sorted()

    /** 仅测试用：清空以避免用例间互相污染 */
    internal fun clearForTest() = backends.clear()
}

/**
 * 一个动作的实现后端。
 *
 * 返回值决定流水线如何继续（对齐上游 `TaskExecution.execute` 的返回约定）：
 * 上游动作函数返回 `(节点名, do_action, get_next...)` 元组来改写后续执行栈，
 * 这里收敛为显式的 [ActionOutcome]。
 */
interface ActionBackend {
    suspend fun execute(ctx: ActionContext): ActionOutcome
}

/** 动作执行结果 */
sealed interface ActionOutcome {
    /** 正常结束，按节点的 next/interrupt 继续路由 */
    data object Continue : ActionOutcome

    /** 重跑当前节点的动作（上游用于「技能全未选中，点一下重开 p」这类重试） */
    data object RetrySelf : ActionOutcome

    /** 跳转到指定节点继续路由（上游 exec_back_to_init_page 会跳到 mirror_defeat） */
    data class Goto(val nodeName: String) : ActionOutcome

    /** 结束整条流水线 */
    data class Finish(val success: Boolean, val message: String? = null) : ActionOutcome
}
