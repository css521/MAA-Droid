package com.aliothmoon.maadroid.diagnostics

/** Always hand the original throwable to Android's handler, even if storage fails. */
internal class DiagnosticCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val report: (Thread, Throwable) -> Unit,
    private val terminate: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            report(thread, error)
        } catch (_: Throwable) {
            // Preserve the original crash, not a secondary diagnostic failure.
        }
        try {
            previous?.uncaughtException(thread, error)
        } finally {
            // Android normally terminates in the previous handler; cover a missing/returning one.
            terminate()
        }
    }
}
