package com.maadroid.app.engine.limbus

/**
 * 用户可选的边狱任务，以及它们在流水线里对应的节点。
 *
 * 上游的任务模型**不是**「每个任务一个入口节点」——整条流水线只有一个入口 `main`，
 * `task_center` 的 next 里列着这几个节点，靠各自的 `enable` 决定跑不跑
 * （实测：`mirror`、`exp`、`thread`、`mail`、`reward` 都不是节点名，只有 `main` 是）。
 *
 * 所以「选任务」= 覆盖这些节点的 enable，而不是换入口。
 */
enum class LimbusTask(
    /** 对宿主暴露的任务标识，也是 `appendTask(type)` 的取值 */
    val type: String,
    /** 流水线里对应的节点名 */
    val nodeName: String,
    /** 该任务的配置分节名；无配置的任务为 null */
    val configSection: String?,
    /** Android 运行副本中的实际入口；邮件使用上游已有的一次性入口。 */
    val entryNodeName: String = nodeName,
) {
    /** 领取邮件 */
    MAIL("mail", "check_and_get_mails", null, "mail_entry"),

    /** 经验副本 */
    EXP("exp", "exp_entry", "exp"),

    /** 纺锤副本 */
    THREAD("thread", "thread_entry", "thread"),

    /** 镜牢 */
    MIRROR("mirror", "mirror_entry", "mirror"),

    /** 领取奖励 */
    REWARD("reward", "reward_entry", null),
    ;

    companion object {
        /** 流水线唯一入口 */
        const val ENTRY_NODE = "main"

        fun ofType(type: String): LimbusTask? = entries.firstOrNull { it.type == type }

        /** 全部任务节点，用于「未选中的一律关掉」 */
        fun allNodeNames(): List<String> = entries.map { it.nodeName }
    }
}
