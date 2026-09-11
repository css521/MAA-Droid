package com.maadroid.app.data.achievement

import android.content.Context

/**
 * 成就文案字段。用类型安全枚举取代散落各处的 "title" / "desc" / "condition" 硬编码字符串:
 * 拼错在编译期即报错,并有 IDE 自动补全。[key] 对应资源名 achievement_&lt;id&gt;_&lt;key&gt;,
 * 与生成的 [achievementStringResId] 查表键一致。
 */
enum class AchievementField(val key: String) {
    TITLE("title"),
    DESC("desc"),
    CONDITION("condition"),
}

/**
 * (非组合期用)按成就 id + 字段取字符串资源;命中不到返回空串。
 *
 * 底层走生成的 [achievementStringResId](编译期 R 常量,零反射)。组合期展示请用 presentation 层的
 * @Composable achievementText —— 它用 stringResource,配置感知(语言切换自动刷新)。
 */
fun Context.achievementText(id: String, field: AchievementField): String {
    val resId = achievementStringResId(id, field)
    return if (resId == 0) "" else getString(resId)
}
