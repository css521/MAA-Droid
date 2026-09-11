package com.maadroid.app.presentation.view.engine

import org.junit.Assert.*
import org.junit.Test

class EnginePreviewPointersTest {
    @Test fun eightManualFingersNeverOccupyThePipelineSlots() {
        val pointers = EnginePreviewPointers()
        val ids = (100L..107L).toList()
        assertEquals((8..15).toList(), ids.map(pointers::acquire))
        assertEquals(8, pointers.acquire(100))
        assertEquals(-1, pointers.acquire(108))
        assertEquals(11, pointers.release(103))
        assertEquals(11, pointers.acquire(Long.MAX_VALUE))
        assertEquals(-1, pointers.release(103))
        assertEquals(11, pointers.find(Long.MAX_VALUE))
    }

    @Test fun portraitAndLandscapeCentersMapToTheGameCenter() {
        assertEquals(EnginePreviewPoint(640, 360, true), enginePreviewPoint(180f, 400f, 360, 800, 1280, 720))
        assertEquals(EnginePreviewPoint(640, 360, true), enginePreviewPoint(1000f, 450f, 2000, 900, 1280, 720))
        assertEquals(EnginePreviewPoint(640, 360, true), enginePreviewPoint(640f, 360f, 1280, 720, 1280, 720))
    }

    @Test fun letterboxDownsAreOutsideAndDraggedPointsClampToTheLastPixel() {
        assertEquals(EnginePreviewPoint(0, 0, false), enginePreviewPoint(-5f, -5f, 360, 800, 1280, 720))
        assertEquals(EnginePreviewPoint(1279, 719, false), enginePreviewPoint(2000f, 900f, 2000, 900, 1280, 720))
        assertFalse(enginePreviewPoint(100f, 450f, 2000, 900, 1280, 720)!!.inside)
        assertEquals(EnginePreviewPoint(0, 360, true), enginePreviewPoint(200f, 450f, 2000, 900, 1280, 720))
    }

    @Test fun unavailableOrInvalidLayoutHasNoInputCoordinates() {
        assertNull(enginePreviewPoint(0f, 0f, 0, 800, 1280, 720))
        assertNull(enginePreviewPoint(0f, 0f, 360, 0, 1280, 720))
        assertNull(enginePreviewPoint(Float.NaN, 0f, 360, 800, 1280, 720))
        assertNull(enginePreviewPoint(0f, Float.POSITIVE_INFINITY, 360, 800, 1280, 720))
    }
}
