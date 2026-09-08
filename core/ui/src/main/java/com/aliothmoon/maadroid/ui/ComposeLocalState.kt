package com.aliothmoon.maadroid.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * 是否处于悬浮窗内。
 *
 * 组件据此切换输入方式：悬浮窗里系统输入法弹不出来，文本框要换成
 * [com.aliothmoon.maadroid.ui.components.FloatWindowEditText] 那条路径。
 *
 * `LocalToaster` 留在宿主：它依赖 sonner 这个第三方库，属宿主级能力，
 * 引擎与组件都不需要它 —— 下沉进来只会让 core:ui 多背一个依赖。
 */
val LocalFloatingWindowContext = compositionLocalOf { false }
