package com.maadroid.app.presentation.view.engine

import java.io.File

internal enum class EngineDiagnosticNotice { EXECUTION_FAILURE, RECENT_CRASH }

/** Ordinary status text and engine logs are deliberately not inputs to this decision. */
internal fun engineDiagnosticNotice(
    hasExecutionFailure: Boolean,
    hasNewCrash: Boolean,
    running: Boolean,
): EngineDiagnosticNotice? = when {
    hasExecutionFailure -> EngineDiagnosticNotice.EXECUTION_FAILURE
    hasNewCrash && !running -> EngineDiagnosticNotice.RECENT_CRASH
    else -> null
}

/** Do not turn retained, already offered, or future-dated records into a permanent banner. */
internal fun newCrashTimestamp(timestamps: Iterable<Long>, offeredThrough: Long, now: Long): Long? =
    timestamps.filter { it > offeredThrough && it in (now - 24 * 60 * 60 * 1000L)..now }.maxOrNull()

/** AppDiagnostics also writes handled errors here; only its uncaught-exception header is a crash. */
internal fun uncaughtCrashTimestamp(file: File): Long? {
    val timestamp = Regex("crash_(\\d+)_.+\\.txt").matchEntire(file.name)
        ?.groupValues?.get(1)?.toLongOrNull() ?: return null
    return try {
        val header = file.bufferedReader().use { reader ->
            val buffer = CharArray(4096)
            val count = reader.read(buffer)
            if (count <= 0) return null
            String(buffer, 0, count).lineSequence().drop(1).firstOrNull()
        } ?: return null
        val fields = header.split(' ')
        timestamp.takeIf { "component=java" in fields && "phase=uncaught_exception" in fields }
    } catch (_: Exception) {
        null
    }
}
