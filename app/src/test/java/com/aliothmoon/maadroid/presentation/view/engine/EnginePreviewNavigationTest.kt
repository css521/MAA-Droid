package com.aliothmoon.maadroid.presentation.view.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnginePreviewNavigationTest {
    @Test fun staleRouteDisposalCannotClearTheVisiblePreview() {
        val state = EnginePreviewNavigation()
        val old = Any()
        val current = Any()
        state.enterFullscreen(old, "first")
        state.enterFullscreen(current, "second")
        state.exitFullscreen(old)
        assertEquals("second", state.fullscreenEngineId)
        state.exitFullscreen(current)
        assertNull(state.fullscreenEngineId)
    }
}
