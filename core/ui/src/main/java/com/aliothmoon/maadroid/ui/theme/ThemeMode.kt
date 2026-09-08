package com.aliothmoon.maadroid.ui.theme

/**
 * 主题模式。
 *
 * 原先是 `AppSettingsManager` 的嵌套枚举，于是 `Theme.kt` 想下沉到 `core:ui` 就得
 * 连宿主的设置管理器一起拖过来 —— 那正是「引擎不得依赖宿主」这条边界会拦住的事。
 * 主题模式本就属于主题而非某个设置类，故提到这里；宿主的设置只负责持久化它的名字。
 *
 * 枚举名即持久化值（`AppSettingsManager` 存 `name`），**不可改名** ——
 * 改了会让已装用户的主题设置读不出来而回落到 SYSTEM。
 * 历史包袱：`WHITE` 而不是 `LIGHT`，因为早期存过 `LIGHT`，迁移代码仍在读它。
 */
enum class ThemeMode {
    SYSTEM,
    WHITE,
    DARK,
    PURE_DARK,
}
