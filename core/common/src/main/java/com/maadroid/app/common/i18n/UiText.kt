package com.maadroid.app.common.i18n

import android.content.Context
import androidx.annotation.StringRes

/**
 * 可延迟解析的界面文本。
 *
 * 存在的理由：非 UI 层也需要产出面向用户的文案（配置校验、资源加载、异常原因），
 * 但那时拿不到 Context、也不知道当前语言 —— 直接存字符串会让语言切换后文案不更新。
 * 于是先存「资源 id + 参数」，到 UI 层再 [resolve]。
 *
 * 放在 core:common 而非 core:ui：`FightConfig`、`ActivityManager`、`CopilotManager`
 * 这些非 UI 类都在构造 UiText，不该为此背上 Compose 依赖。
 * @Composable 的取值器在 core:ui（见 `UiTextCompose.kt`）。
 */
sealed interface UiText {
    data object Empty : UiText

    data class Dynamic(
        val value: String,
    ) : UiText

    data class Resource(
        @param:StringRes val resId: Int,
        val args: List<Any?> = emptyList(),
    ) : UiText

    data class Joined(
        val parts: List<UiText>,
        val separator: UiText = Empty,
    ) : UiText
}

fun uiTextOf(@StringRes resId: Int, vararg args: Any?): UiText =
    UiText.Resource(resId = resId, args = args.toList())

fun uiTextDynamic(value: String?): UiText =
    if (value.isNullOrBlank()) {
        UiText.Empty
    } else {
        UiText.Dynamic(value)
    }

fun uiTextDynamicOr(value: String?, @StringRes fallback: Int): UiText =
    if (value.isNullOrBlank()) uiTextOf(fallback) else UiText.Dynamic(value)

fun uiTextJoin(vararg parts: UiText, separator: UiText = UiText.Empty): UiText =
    UiText.Joined(parts = parts.filterNot { it is UiText.Empty }, separator = separator)

fun uiTextLines(vararg lines: UiText): UiText =
    uiTextJoin(*lines, separator = UiText.Dynamic("\n"))

fun UiText?.resolve(context: Context): String {
    return when (this) {
        null,
        UiText.Empty -> ""

        is UiText.Dynamic -> value

        is UiText.Resource -> {
            val resolvedArgs = args.map { arg ->
                when (arg) {
                    is UiText -> arg.resolve(context)
                    else -> arg
                }
            }.toTypedArray()
            context.getString(resId, *resolvedArgs)
        }

        is UiText.Joined -> {
            val resolvedSeparator = separator.resolve(context)
            parts.joinToString(separator = resolvedSeparator) { it.resolve(context) }
        }
    }
}
