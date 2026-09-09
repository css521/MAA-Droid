package com.aliothmoon.maadroid.presentation.view.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineDiagnosticPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun idleAndSuccessfulRunningHaveNoDiagnosticNotice() {
        assertNull(engineDiagnosticNotice(hasExecutionFailure = false, hasNewCrash = false, running = false))
        assertNull(engineDiagnosticNotice(hasExecutionFailure = false, hasNewCrash = false, running = true))
        // A previous crash cannot remain pinned while a new run is active.
        assertNull(engineDiagnosticNotice(hasExecutionFailure = false, hasNewCrash = true, running = true))
    }

    @Test fun explicitFailureWinsEvenIfTheEngineHasNotStoppedYet() {
        for (running in listOf(false, true)) {
            assertEquals(EngineDiagnosticNotice.EXECUTION_FAILURE, engineDiagnosticNotice(true, false, running))
            assertEquals(EngineDiagnosticNotice.EXECUTION_FAILURE, engineDiagnosticNotice(true, true, running))
        }
        assertEquals(EngineDiagnosticNotice.RECENT_CRASH, engineDiagnosticNotice(false, true, false))
    }

    @Test fun onlyUnseenRecentCrashesAreOffered() {
        val now = 2 * 24 * 60 * 60 * 1000L
        val recent = now - 60_000
        assertEquals(recent, newCrashTimestamp(listOf(recent), offeredThrough = 0, now = now))
        assertNull(newCrashTimestamp(listOf(recent), offeredThrough = recent, now = now))
        assertNull(newCrashTimestamp(listOf(now - 24 * 60 * 60 * 1000L - 1, now + 1), 0, now))
        assertNull(newCrashTimestamp(emptyList(), 0, now))
        assertEquals(now, newCrashTimestamp(listOf(now - 2, now - 1, now), offeredThrough = now - 2, now = now))
    }

    @Test fun handledErrorsAndOrdinaryLogsAreNotCrashes() {
        val report = temp.newFile("crash_123456_handled.txt")
        for (phase in listOf("ui.start.failed", "engine.failure", "preview.surface")) {
            report.writeText("identity\ncomponent=limbus phase=$phase thread=main\nException\n")
            assertNull(uncaughtCrashTimestamp(report))
        }
        // A string buried in a stack trace cannot spoof the report header.
        report.writeText("identity\ncomponent=limbus phase=engine.failure thread=main\ncomponent=java phase=uncaught_exception\n")
        assertNull(uncaughtCrashTimestamp(report))
        val events = temp.newFile("events.log")
        events.writeText("identity\ncomponent=java phase=uncaught_exception thread=main\n")
        assertNull(uncaughtCrashTimestamp(events))
    }

    @Test fun uncaughtExceptionUsesTheExactReportHeader() {
        val report = temp.newFile("crash_123456_actual.txt")
        report.writeText("identity\ncomponent=java phase=uncaught_exception thread=main\nException\n")
        assertEquals(123456L, uncaughtCrashTimestamp(report))
        report.writeText("identity\ncomponent=java phase=uncaught_exception_handled thread=main\n")
        assertNull(uncaughtCrashTimestamp(report))
        report.writeText("identity\ncomponent=javascript phase=uncaught_exception thread=main\n")
        assertNull(uncaughtCrashTimestamp(report))
    }

    @Test fun truncatedMissingAndMalformedReportsDoNotCreateNotices() {
        val report = temp.newFile("crash_123456_partial.txt")
        assertNull(uncaughtCrashTimestamp(report))
        report.writeText("identity")
        assertNull(uncaughtCrashTimestamp(report))
        report.writeText("identity\ncomponent=java")
        assertNull(uncaughtCrashTimestamp(report))
        report.delete()
        assertNull(uncaughtCrashTimestamp(report))
        val invalidName = temp.newFile("crash_unknown_invalid.txt")
        invalidName.writeText("identity\ncomponent=java phase=uncaught_exception thread=main\n")
        assertNull(uncaughtCrashTimestamp(invalidName))
    }
}
