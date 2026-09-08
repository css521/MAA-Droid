package com.aliothmoon.maadroid.presentation

import androidx.compose.runtime.staticCompositionLocalOf
import com.dokar.sonner.ToasterState

/**
 * 全局 toast 宿主。
 *
 * 留在 `:app` 而非随组件下沉到 `core:ui`：它绑着 sonner 这个第三方库，
 * 是宿主级能力，组件与引擎都不用它。
 */
val LocalToaster = staticCompositionLocalOf<ToasterState> {
    error("LocalToaster not provided.")
}
