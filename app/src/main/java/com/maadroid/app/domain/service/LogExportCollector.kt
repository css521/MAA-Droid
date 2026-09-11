package com.maadroid.app.domain.service

import com.maadroid.app.constant.LogConfig
import com.maadroid.app.diagnostics.DiagnosticStore
import com.maadroid.app.diagnostics.DiagnosticArchive
import java.io.File

/** 日志导出筛选：不删源、不写盘。 */
object LogExportCollector {

    const val EXPORT_DIR_NAME = "export"

    private val RESERVED_ROOTS = setOf(
        "diagnostics", "properties.txt", "device_info.txt", "attachments_status.txt", "export",
    )

    /** Preserve old relative paths without letting optional files shadow diagnostic/metadata entries. */
    fun isLegacyEntryAllowed(name: String): Boolean =
        DiagnosticArchive.validName(name) && name.substringBefore('/') !in RESERVED_ROOTS

    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    private val ROLLING_DIR_MARKERS = listOf(
        "/gui/",
        "/schedule/",
        "/error_logs/",
        "/crash_logs/",
    )

    fun collect(debugDir: File): List<File> {
        if (!debugDir.isDirectory) return emptyList()
        val exportDir = File(debugDir, EXPORT_DIR_NAME)
        return select(
            debugDir.walkTopDown()
                .onEnter { it != exportDir }
                .filter { file ->
                    file.isFile
                }
                .toList(),
        )
    }

    /** Internal logs are retained across restart and are not subject to the legacy age filter. */
    fun collectDiagnostics(filesDir: File): List<File> {
        val directory = File(filesDir, DiagnosticStore.DIRECTORY)
        if (!directory.isDirectory) return emptyList()
        return directory.walkTopDown().filter { it.isFile && it.name != ".lock" }.toList()
    }

    fun select(files: Iterable<File>): List<File> {
        val cutoff = System.currentTimeMillis() -
                LogConfig.EXPORT_ROLLING_LOG_DAYS * MS_PER_DAY
        return files.filter { shouldExport(it, cutoff) }
    }

    private fun shouldExport(file: File, rollingCutoff: Long): Boolean {
        if (!file.isFile) return false
        if (!isUnderRollingDir(file.invariantSeparatorsPath)) return true
        return file.lastModified() >= rollingCutoff
    }

    private fun isUnderRollingDir(invariantPath: String): Boolean =
        ROLLING_DIR_MARKERS.any { invariantPath.contains(it) }
}
