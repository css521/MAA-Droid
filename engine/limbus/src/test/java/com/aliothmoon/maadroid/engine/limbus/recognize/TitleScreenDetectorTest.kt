package com.aliothmoon.maadroid.engine.limbus.recognize

import org.junit.Assert.*
import org.junit.Test

class TitleScreenDetectorTest {
    @Test fun anchorIdentifiesTitleButNeverClicksClearCaches() {
        val target = TitleScreenDetector.fromAnchor(listOf(Match(256, 656, 0.9)))!!
        assertEquals(640 to 540, target.x to target.y)
        assertNull(TitleScreenDetector.fromAnchor(listOf(Match(256, 300, 0.99))))
        assertNull(TitleScreenDetector.fromAnchor(listOf(Match(256, 656, 0.6))))
    }

    @Test fun blinkingPromptCanUseTwoAlignedFooterLabels() {
        val target = TitleScreenDetector.fromText(listOf(
            TextMatch("Change banner", 108, 655, 0.92),
            TextMatch("Clear all caches", 258, 656, 0.95),
        ))!!
        assertEquals(640 to 540, target.x to target.y)
        assertNull(TitleScreenDetector.fromText(listOf(TextMatch("Clear all caches", 258, 656, 0.95))))
    }

    @Test fun ocrRequiresTitleTextInItsExpectedRegion() {
        for (label in listOf("TOUCH TO START", "Touch  to start", "点击开始")) {
            val target = TitleScreenDetector.fromText(listOf(TextMatch(label, 640, 550, 0.91)))!!
            assertEquals(640 to 550, target.x to target.y)
        }
        assertNull(TitleScreenDetector.fromText(listOf(TextMatch("TOUCH TO START", 640, 200, 0.99))))
        assertNull(TitleScreenDetector.fromText(listOf(TextMatch("TOUCH TO START", 640, 550, 0.5))))
        assertNull(TitleScreenDetector.fromText(listOf(TextMatch("TOUCH TO START a battle", 640, 550, 0.99))))
    }
}
