package com.aliothmoon.maadroid.engine.limbus.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 流水线注册表。装配步骤严格对齐上游 LALC 的 `workflow/task_registry.py: init_tasks()`：
 *
 * 1. 加载 `config/task` 下的 JSON 全部节点
 * 2. 清空来自 error.json 的节点的 interrupt（否则异常处理自我递归）
 * 3. action 名解析为节点
 * 4. next / interrupt 名解析为节点
 * 5. params 里的 origin / disable_node 解析为节点
 * 6. 校验全部引用可达
 *
 * 顺序不可调整：上游第 2 步必须在解析之前，否则 error 节点会先被塞上默认 interrupt。
 */
class PipelineRegistry private constructor(
    private val nodes: Map<String, PipelineNode>,
    /** 节点名 → 该节点最终生效的 interrupt 列表（已应用默认值与 error 清空规则） */
    private val interrupts: Map<String, List<String>>,
) {

    val size: Int get() = nodes.size

    fun names(): Set<String> = nodes.keys

    operator fun get(name: String): PipelineNode? = nodes[name]

    fun require(name: String): PipelineNode =
        nodes[name] ?: error("未注册的流水线节点: $name")

    fun interruptsOf(name: String): List<String> = interrupts[name].orEmpty()

    /**
     * 覆盖若干节点的 `enable`，返回新注册表（本实例不变）。
     *
     * 这是「用户选了哪些任务」的落地方式，也是上游的做法：整条流水线只有一个入口
     * `main`，`task_center` 的 next 里列着 `check_and_get_mails` / `exp_entry` /
     * `thread_entry` / `mirror_entry` / `reward_entry`，**靠各自的 enable 决定跑不跑**。
     * 不存在 `mirror`、`exp` 这类独立入口节点（实测确认）。
     *
     * 返回新实例而不是原地改：节点不可变是为了让资源包能整体替换，
     * 而一次运行的任务选择不该污染下一次。
     *
     * 名字不在注册表里的覆盖项直接忽略 —— 上游改了节点名时，宁可该任务不跑，
     * 也不要因为一个陌生名字让整条链装不起来。
     */
    fun withEnabled(overrides: Map<String, Boolean>): PipelineRegistry {
        if (overrides.isEmpty()) return this
        val patched = nodes.mapValues { (name, node) ->
            overrides[name]?.let { node.copy(enable = it) } ?: node
        }
        return PipelineRegistry(patched, interrupts)
    }

    fun withTargetCounts(counts: Map<String, Int>): PipelineRegistry {
        val disabled = counts.filterValues { it == 0 }.keys.mapNotNull { nodes[it]?.str("disable_node") }.toSet()
        return PipelineRegistry(nodes.mapValues { (name, node) ->
            val count = counts[name]
            if (count != null) node.copy(params = JsonObject(node.params + ("target_count" to JsonPrimitive(count))))
            else if (name in disabled) node.copy(enable = false) else node
        }, interrupts)
    }

    /** 流水线引用到的全部 action 名（含纯路由的） */
    fun referencedActions(): Set<String> = nodes.values.map { it.action }.toSet()

    /** 流水线引用到的全部模板名，用于校验资源包完整性 */
    fun referencedTemplates(): Set<String> =
        nodes.values.flatMap { it.templates() }.toSet()

    companion object {

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            allowTrailingComma = true
            allowComments = true
        }

        /** 上游把异常处理节点集中在 error.json，其节点名统一带 error_ 前缀 */
        private const val ERROR_FILE = "error.json"

        /**
         * 从「文件名 → JSON 文本」装配。
         *
         * 之所以不直接吃目录：装配逻辑要能在纯 JVM 单测里跑（不依赖 Android 与文件系统
         * 布局），资源实际来源由调用方决定（APK assets 或热更后的资源目录）。
         *
         * @throws IllegalStateException 节点不兼容、参数无效、存在重名节点或断引用时抛出
         */
        fun load(files: Map<String, String>): PipelineRegistry {
            val nodes = LinkedHashMap<String, PipelineNode>()
            val fromErrorFile = HashSet<String>()

            // 1. 加载
            for ((fileName, text) in files.entries.sortedBy { it.key }) {
                val parsed = json.decodeFromString<Map<String, PipelineNode>>(text)
                for ((name, node) in parsed) {
                    check(name !in nodes) { "流水线节点重名: $name（见 $fileName）" }
                    node.compatibilityError()?.let { reason ->
                        error("流水线节点 $name（$fileName）不兼容：$reason")
                    }
                    nodes[name] = node
                    if (fileName.endsWith(ERROR_FILE)) fromErrorFile += name
                }
            }
            check(nodes.isNotEmpty()) { "未加载到任何流水线节点" }

            // 2. error 节点的 interrupt 清空；其余节点缺省为 ["error_handler"]
            val interrupts = nodes.mapValues { (name, node) ->
                when {
                    name in fromErrorFile -> emptyList()
                    node.interrupt != null -> node.interrupt
                    else -> PipelineNode.DEFAULT_INTERRUPT
                }
            }

            // 3-6. 引用校验（解析在运行时按名查表完成，这里只保证可达）
            val errors = mutableListOf<String>()
            for ((name, node) in nodes) {
                if (node.action !in nodes) {
                    errors += "节点 $name 的 action 指向未注册节点: ${node.action}"
                }
                node.next.filterNot { it in nodes }.forEach {
                    errors += "节点 $name 的 next 指向未注册节点: $it"
                }
                interrupts.getValue(name).filterNot { it in nodes }.forEach {
                    errors += "节点 $name 的 interrupt 指向未注册节点: $it"
                }
                for (key in listOf("origin", "disable_node")) {
                    val ref = node.str(key) ?: continue
                    if (ref !in nodes) {
                        errors += "节点 $name 的 params.$key 指向未注册节点: $ref"
                    }
                }
            }
            check(errors.isEmpty()) {
                "流水线引用校验失败（${errors.size} 项）:\n" + errors.joinToString("\n")
            }

            return PipelineRegistry(nodes, interrupts)
        }
    }
}
