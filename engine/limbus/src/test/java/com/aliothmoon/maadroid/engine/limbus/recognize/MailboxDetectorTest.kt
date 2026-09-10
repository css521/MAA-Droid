package com.aliothmoon.maadroid.engine.limbus.recognize

import org.junit.Assert.*
import org.junit.Test

class MailboxDetectorTest {
    private val title = TextMatch("Mailbox", 640, 120, .95)
    private val empty = TextMatch("No mail in storage.", 640, 349, .98)
    private val close = TextMatch("Close", 776, 551, .81)

    @Test fun confirmedEmptyPanelUsesTheRecognizedClosePosition() {
        assertEquals(MailboxObservation(Match(776, 551, .81), empty = true),
            MailboxDetector.fromText(listOf(title, empty, close)))
    }

    @Test fun aMissingOrUnrelatedCloseDoesNotTurnTheMailboxIntoAbsence() {
        for (words in listOf(listOf(empty), listOf(empty, close), listOf(title, empty),
            listOf(title, empty, close.copy(x = 310)),
            listOf(title, empty, close.copy(text = "Claim All")),
            listOf(title.copy(y = 349), empty, close))) {
            assertEquals(MailboxObservation(close = null, empty = true), MailboxDetector.fromText(words))
        }
        assertEquals(MailboxObservation(close = null, empty = false), MailboxDetector.fromText(listOf(title)))
        assertNull(MailboxDetector.fromText(listOf(close)))
    }

    @Test fun lowConfidenceAndMisreadButtonsDoNotBecomeClicks() {
        assertNull(MailboxDetector.fromText(listOf(title, empty, close.copy(score = .69)))!!.close)
        assertNull(MailboxDetector.fromText(listOf(title, empty, close.copy(score = Double.NaN)))!!.close)
        // Re-read pixels in the dedicated button row; never guess the spelling into Close.
        assertNull(MailboxDetector.fromText(listOf(title, empty, close.copy(text = "Clese")))!!.close)
    }

    @Test fun aMissingEmptyMessageIsNotEvidenceOfAnEmptyMailbox() {
        assertFalse(MailboxDetector.fromText(listOf(title, close))!!.empty)
        assertFalse(MailboxDetector.fromText(listOf(title, close, empty.copy(y = 600)))!!.empty)
        assertFalse(MailboxDetector.fromText(listOf(title, close, empty.copy(score = .69)))!!.empty)
        assertNull(MailboxDetector.fromText(emptyList()))
    }

    @Test fun chineseMailboxLabelsKeepTheSameLayoutRequirements() {
        assertEquals(MailboxObservation(Match(776, 551, .81), empty = true),
            MailboxDetector.fromText(listOf(title.copy(text = "邮箱"), empty.copy(text = "暂无邮件"), close.copy(text = "关闭"))))
    }
}
