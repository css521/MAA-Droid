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

    /**
     * v5.0.0 routes task_center straight to the notification dot, bypassing mail_entry.
     * On Android an explicitly selected Mail task must inspect the mailbox even without
     * that desktop-sized dot. Use the existing one-shot entry/check pair for this run.
     * The public task selection still addresses the upstream check_and_get_mails node.
     */
    internal fun withAndroidMailEntry(): PipelineRegistry {
        fun changed(name: String): Nothing = error(
            "邮件流水线节点 $name 已变化，当前 Android 邮件适配仅支持 LALC v5.0.0 的邮件流程，请升级 App 后重试",
        )
        fun mailNode(name: String): PipelineNode = nodes[name] ?: changed(name)
        val open = mailNode("check_and_get_mails")
        if (!open.enable) {
            // Mail is not part of this run. Disable both legacy and wrapped entry paths
            // without imposing the Android mail shape on another selected task.
            return PipelineRegistry(nodes.mapValues { (name, node) ->
                if (name == "mail_entry") node.copy(enable = false) else node
            }, interrupts)
        }
        fun route(
            name: String, action: String, recognition: String, next: List<String>,
            inverse: Boolean = false, type: String = PipelineNode.TYPE_NORMAL,
        ) {
            val node = mailNode(name)
            if (node.action != action || node.recognition != recognition || node.next != next ||
                node.inverse != inverse || node.type != type) changed(name)
        }
        // Only fields whose meaning this adapter relies on are checked. Descriptions,
        // timing, thresholds and unrelated extension fields remain owned by the resource.
        route("mail_entry", "empty", "direct", listOf("mail_enter_main_window"))
        route("mail_enter_main_window", "main_window_confirm", "template_match", listOf("check_and_get_mails", "check_mail"))
        route("check_and_get_mails", "click", "template_match", listOf("claim_mail"))
        route("claim_mail", "click", "template_match", listOf("wait_mailbox_connecting_disappear"), inverse = true)
        route("wait_mailbox_connecting_disappear", "wait_connecting_disappear", "direct", listOf("confirm_reward", "exit_mailbox"))
        route("confirm_reward", "key", "direct", listOf("claim_mail", "exit_mailbox"))
        route("exit_mailbox", "key", "direct", listOf("check_mail"))
        route("check_mail", "check_out_update", "direct", listOf("main_circle_center"), type = PipelineNode.TYPE_CHECK)
        val center = require("task_center")
        if (center.next.count { it == "check_and_get_mails" } != 1 || "mail_entry" in center.next) changed("task_center")
        if (mailNode("mail_enter_main_window").str("template") != "main_window_no_text") changed("mail_enter_main_window")
        if (open.str("template") != "red_exclaimation" || open.ints("mask") != listOf(1080, 60, 70, 80) ||
            open.ints("target_offset") != listOf(-10, 10) || "target" in open.params) changed("check_and_get_mails")
        val claim = mailNode("claim_mail")
        if (claim.str("template") != "no_mail_in_storage" || claim.ints("target") != listOf(950, 270) ||
            (claim.ints("target_offset") ?: listOf(0, 0)) != listOf(0, 0)) changed("claim_mail")
        if (mailNode("confirm_reward").str("key") != "esc") changed("confirm_reward")
        if (mailNode("exit_mailbox").str("key") != "esc") changed("exit_mailbox")
        for (name in listOf("check_and_get_mails", "claim_mail", "confirm_reward", "exit_mailbox")) {
            if ((mailNode(name).num("repeat") ?: 1.0) != 1.0) changed(name)
        }
        val check = mailNode("check_mail")
        if (check.str("disable_node") != "mail_entry" || check.num("target_count") != 1.0) changed("check_mail")

        fun adapted(node: PipelineNode) = node.copy(
            params = JsonObject(node.params + ("android_mail_flow" to JsonPrimitive(true))),
        )
        val patched = nodes + mapOf(
            "task_center" to center.copy(next = center.next.map {
                if (it == "check_and_get_mails") "mail_entry" else it
            }),
            "mail_entry" to mailNode("mail_entry").copy(enable = open.enable),
            "check_and_get_mails" to adapted(open).copy(
                recognition = PipelineNode.RECOGNITION_DIRECT,
                inverse = false,
                // An already empty mailbox must exit instead of looping in error_handler.
                next = (open.next + "exit_mailbox").distinct(),
            ),
            "claim_mail" to adapted(claim),
            "confirm_reward" to adapted(mailNode("confirm_reward")),
        )
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
