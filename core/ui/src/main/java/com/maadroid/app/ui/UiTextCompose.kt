package com.maadroid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.maadroid.app.common.i18n.UiText
import com.maadroid.app.common.i18n.resolve

/**
 * 在 Compose 里取 [UiText] 的当前语言文案。
 *
 * 与 [UiText] 分处两个模块：类型与 `resolve(Context)` 在 `core:common`（非 UI 层也要
 * 构造 UiText，不该背 Compose 依赖），这个取值器在 `core:ui`。
 *
 * 读一次 [LocalConfiguration] 是**必须的**，不是多余代码：它让本组合在语言/字号变化时
 * 重组，否则切换系统语言后文案会停在旧值直到该组合因别的原因重组。
 */
@Composable
fun UiText?.asString(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current)
}
