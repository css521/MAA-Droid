package com.maadroid.app.engine.limbus.recognize

import org.junit.Assert.*
import org.junit.Test

class AdvancedTemplateMatcherTest {
    @Test fun cropOffsetIsAddedAfterUndoingScaleAndOddTemplateCenter() {
        val hit = AdvancedTemplateMatcher.center(17, 9, 11, 7, 0.9, 0.5, 640, 140)
        assertEquals(684, hit.x)
        assertEquals(164, hit.y)
        assertEquals(0.5, hit.scale, 0.0)
    }

    @Test fun mergingAcrossScalesKeepsWinningScaleAndStrictTwentyPixelBoundary() {
        val a = AdvancedTemplateMatcher.ScaledMatch(100, 100, 0.8, 0.5)
        val b = AdvancedTemplateMatcher.ScaledMatch(119, 119, 0.95, 1.2)
        val c = AdvancedTemplateMatcher.ScaledMatch(139, 119, 0.9, 0.8)
        assertEquals(listOf(b, c), AdvancedTemplateMatcher.mergeNearby(listOf(a, c, b)))
    }

    @Test fun mergeIsStableForTiesAndRejectsNonfiniteScores() {
        val first = AdvancedTemplateMatcher.ScaledMatch(0, 0, 0.9, 1.1)
        val second = first.copy(x = 1, scale = 0.8)
        assertEquals(listOf(first), AdvancedTemplateMatcher.mergeNearby(listOf(first, second,
            first.copy(x = 200, score = Double.NaN), first.copy(x = 400, score = Double.POSITIVE_INFINITY))))
    }

    @Test fun pyramidUsesUpstreamDescendingRangeAndExcludesLowerEndpoint() {
        val scales = AdvancedTemplateMatcher.pyramidScales(1.6, 0.5)
        assertEquals(44, scales.size)
        assertEquals(1.6, scales.first(), 1e-12)
        assertEquals(0.525, scales.last(), 1e-12)
        assertTrue(scales.zipWithNext().all { (a, b) -> a > b })
    }

    @Test fun colorScoreUsesUpstreamSaturationAtHalfSimilarity() {
        assertEquals(0.4, AdvancedTemplateMatcher.normalizeColorSimilarity(0.2, 0.5), 1e-12)
        assertEquals(1.0, AdvancedTemplateMatcher.normalizeColorSimilarity(0.5, 0.5), 0.0)
        assertEquals(1.0, AdvancedTemplateMatcher.normalizeColorSimilarity(0.8, 0.5), 0.0)
    }

    @Test fun ratioIsStrictAndRejectsAmbiguousIdenticalDescriptors() {
        assertTrue(AdvancedTemplateMatcher.passesRatio(7.0, 10.0, 0.75))
        assertFalse(AdvancedTemplateMatcher.passesRatio(7.5, 10.0, 0.75))
        assertFalse(AdvancedTemplateMatcher.passesRatio(0.0, 0.0, 0.75))
        assertFalse(AdvancedTemplateMatcher.passesRatio(Double.NaN, 10.0, 0.75))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownModeDoesNotFallBack() {
        AdvancedTemplateMatcher.Mode.fromUpstreamName("gray")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPyramidRangeFailsExplicitly() {
        AdvancedTemplateMatcher.pyramidScales(0.5, 1.6)
    }
}
