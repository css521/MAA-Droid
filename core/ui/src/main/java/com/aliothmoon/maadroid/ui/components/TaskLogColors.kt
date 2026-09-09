package com.aliothmoon.maadroid.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class TaskLogColors(val info: Color, val success: Color, val warning: Color, val error: Color, val trace: Color)

/** Existing task-log colors, shared with engines without exposing Arknights log models. */
fun taskLogColors(isDark: Boolean): TaskLogColors = if (isDark) {
    TaskLogColors(Color(0xFF5DADE2), Color(0xFF7ED957), Color(0xFFF0B050), Color(0xFFFF8585), Color(0xFFB0B4BC))
} else {
    TaskLogColors(Color(0xFF409EFF), Color(0xFF67C23A), Color(0xFFE6A23C), Color(0xFFF56C6C), Color(0xFF909399))
}

val LocalTaskLogColors = staticCompositionLocalOf { taskLogColors(false) }
