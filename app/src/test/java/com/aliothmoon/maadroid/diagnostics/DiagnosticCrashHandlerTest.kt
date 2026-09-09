package com.aliothmoon.maadroid.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DiagnosticCrashHandlerTest {
    @Test fun failedCrashWriteStillDelegatesOriginalThrowableBeforeFallback() {
        val calls = mutableListOf<String>()
        val error = IllegalStateException("original")
        val thread = Thread.currentThread()
        val handler = DiagnosticCrashHandler(
            previous = Thread.UncaughtExceptionHandler { actualThread, actualError ->
                assertSame(thread, actualThread)
                assertSame(error, actualError)
                calls += "android"
            },
            report = { _, _ -> calls += "diagnostics"; throw IOException("full storage") },
            terminate = { calls += "terminate" },
        )
        handler.uncaughtException(thread, error)
        assertEquals(listOf("diagnostics", "android", "terminate"), calls)
    }

    @Test fun missingPreviousHandlerStillReportsAndTerminates() {
        val calls = mutableListOf<String>()
        DiagnosticCrashHandler(null, { _, _ -> calls += "report" }, { calls += "terminate" })
            .uncaughtException(Thread.currentThread(), Exception("crash"))
        assertEquals(listOf("report", "terminate"), calls)
    }

    @Test fun throwingPreviousHandlerStillRunsFallback() {
        var terminated = false
        val delegateError = IllegalStateException("delegate")
        try {
            DiagnosticCrashHandler(Thread.UncaughtExceptionHandler { _, _ -> throw delegateError },
                { _, _ -> }, { terminated = true })
                .uncaughtException(Thread.currentThread(), Exception("crash"))
            fail("Expected delegate error")
        } catch (error: IllegalStateException) { assertSame(delegateError, error) }
        assertTrue(terminated)
    }
}
