package com.maadroid.app.diagnostics

import android.annotation.TargetApi
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.time.Instant

internal object ExitHistoryCollector {
    const val LIMIT = 8
    const val AVAILABILITY = """Historical exit reasons require Android 11 / API 30.
Native crash traces may require Android 12 / API 31; a vendor may not provide a trace.
Trace files are raw bytes (possibly binary tombstone protobuf), limited to 2 MiB each.
Only this package's system-retained history is queried; a Shizuku/root process under another UID may be absent.
Missing history/trace does not prove that no crash occurred. Collection can time out.
"""

    fun append(context: Context, writer: DiagnosticArchive.Writer) {
        if (Build.VERSION.SDK_INT < 30) {
            writer.text("diagnostics/exit_history/records.txt", "Unavailable: API ${Build.VERSION.SDK_INT}; requires API 30+.\n")
            return
        }
        appendSupported(context, writer)
    }

    @TargetApi(30)
    private fun appendSupported(context: Context, writer: DiagnosticArchive.Writer) {
        val records = try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(context.packageName, 0, LIMIT)
                .sortedByDescending { it.timestamp }.take(LIMIT)
        } catch (error: Exception) {
            writer.text("diagnostics/exit_history/records.txt", "Unavailable: ${error.javaClass.simpleName}\n")
            return
        }
        writer.text("diagnostics/exit_history/records.txt", buildString {
            appendLine("Collected at ${Instant.now()}; records=${records.size}; max=$LIMIT")
            for ((index, record) in records.withIndex()) {
                appendLine("[$index] timestamp=${record.timestamp} time=${Instant.ofEpochMilli(record.timestamp)}")
                appendLine("process=${DiagnosticText.field(record.processName.orEmpty())} pid=${record.pid}")
                appendLine("reason=${record.reason} (${reasonName(record.reason)}) status=${record.status} importance=${record.importance}")
                appendLine("pss_kb=${record.pss} rss_kb=${record.rss}")
                appendLine("description=${DiagnosticText.field(record.description.orEmpty(), 2048)}")
            }
        })
        val status = StringBuilder()
        for ((index, record) in records.withIndex()) {
            val input = try { record.traceInputStream } catch (error: Exception) {
                status.appendLine("[$index] unavailable: ${error.javaClass.simpleName}")
                continue
            }
            if (input == null) {
                status.appendLine("[$index] no trace supplied by Android/vendor")
                continue
            }
            input.use {
                val name = "diagnostics/exit_history/trace_${index}_${record.timestamp}_${record.pid}.bin"
                status.appendLine("[$index] $name: ${writer.stream(name, it, DiagnosticArchive.MAX_TRACE_BYTES)}")
            }
        }
        writer.text("diagnostics/exit_history/trace_status.txt", status.toString().ifEmpty { "No historical records.\n" })
    }

    private fun reasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNRECOGNIZED"
    }
}
