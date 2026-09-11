package com.maadroid.app.presentation.view.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Host chrome follows the visible preview, including the standalone engine route. */
class EnginePreviewNavigation {
    var fullscreenEngineId: String? by mutableStateOf(null)
        private set
    private var owner: Any? = null

    fun enterFullscreen(owner: Any, engineId: String) {
        this.owner = owner
        fullscreenEngineId = engineId
    }

    fun exitFullscreen(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        fullscreenEngineId = null
    }
}

val LocalEnginePreviewNavigation = staticCompositionLocalOf<EnginePreviewNavigation?> { null }
