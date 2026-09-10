package com.aliothmoon.maadroid.engine.limbus.recognize

import org.junit.Assert.*
import org.junit.Test

class TeamSelectionDetectorTest {
    private val count = TextMatch("12/12", 1176, 520, .996)
    private val top = TextMatch("SELECTED", 958, 249, .828)
    private val bottom = TextMatch("SELECTED", 959, 448, .956)

    @Test fun actualPreviewOcrRequiresCounterAndBothCardRows() {
        assertNotNull(TeamSelectionDetector.fromText(listOf(count), listOf(top, bottom)))
        for (labels in listOf(emptyList(), listOf(top), listOf(bottom), listOf(top, top))) {
            assertNull(TeamSelectionDetector.fromText(listOf(count), labels))
        }
        assertNull(TeamSelectionDetector.fromText(emptyList(), listOf(top, bottom)))
        assertNotNull(TeamSelectionDetector.fromText(listOf(count.copy(text = "0 / 6")),
            listOf(top.copy(text = "BACKUP"), bottom.copy(text = "backup"))))
    }

    @Test fun rejectsOtherPageTextMisplacedNumbersAndLowConfidence() {
        for (invalid in listOf(count.copy(text = "13/12"), count.copy(text = "1/0"),
            count.copy(text = "12/120"), count.copy(text = "STAGE 09"),
            count.copy(x = 400), count.copy(y = 250), count.copy(score = .89),
            count.copy(score = Double.NaN))) {
            assertNull(TeamSelectionDetector.fromText(listOf(invalid), listOf(top, bottom)))
        }
        for (invalid in listOf(top.copy(text = "SELECIE"), top.copy(score = .79),
            top.copy(score = Double.NaN), top.copy(x = 1100), top.copy(y = 360))) {
            assertNull(TeamSelectionDetector.fromText(listOf(count), listOf(invalid, bottom)))
        }
        assertNull(TeamSelectionDetector.fromText(listOf(count, count.copy(text = "1/6")), listOf(top, bottom)))
        assertNull(TeamSelectionDetector.fromText(listOf(count), listOf(
            TextMatch("待执行", 640, 360, 1.0), TextMatch("60 FPS", 80, 50, 1.0))))
    }
}
