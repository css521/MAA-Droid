package com.maadroid.app.domain.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.maadroid.app.BuildConfig
import com.maadroid.app.constant.MaaFiles
import com.maadroid.app.constant.Packages
import com.maadroid.app.data.achievement.AchievementEvents
import com.maadroid.app.data.achievement.AchievementRepository
import com.maadroid.app.data.config.MaaPathConfig
import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.data.preferences.TaskChainState
import com.maadroid.app.data.resource.MaaCoreVersion
import com.maadroid.app.diagnostics.AppDiagnostics
import com.maadroid.app.diagnostics.DiagnosticArchive
import com.maadroid.app.diagnostics.ExitHistoryCollector
import com.maadroid.app.manager.RemoteServiceManager
import com.maadroid.app.manager.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class LogExportService(
    private val context: Context,
    private val pathConfig: MaaPathConfig,
    private val appSettingsManager: AppSettingsManager,
    private val taskChainState: TaskChainState,
    private val achievementRepository: AchievementRepository,
) {
    companion object {
        private val exportMutex = Mutex()
        private val historyEnricher = DiagnosticArchive.Enricher("diagnostic-exit-history")
        private val legacyEnricher = DiagnosticArchive.Enricher("diagnostic-legacy-logs")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val INFO_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS (Z)")
    }

    /** Internal diagnostics are committed first. Optional collection can never replace them on failure. */
    suspend fun exportZip(): File? = withContext(Dispatchers.IO) {
        exportMutex.withLock {
            var snapshot: File? = null
            var zipFile: File? = null
            var committed = false
            try {
                val exportDir = File(context.cacheDir, LogExportCollector.EXPORT_DIR_NAME)
                check(exportDir.isDirectory || exportDir.mkdirs())
                cleanupOldExports(exportDir)
                val zip = File.createTempFile("maa_logs_${ZonedDateTime.now().format(DATE_FORMAT)}_", ".zip", exportDir)
                zipFile = zip
                val staging = Files.createTempDirectory(exportDir.toPath(), "diagnostic_snapshot_").toFile()
                snapshot = staging
                AppDiagnostics.initialize(context)
                AppDiagnostics.record("log_export", "snapshot")
                val captured = AppDiagnostics.snapshot(context, File(staging, "diagnostics"))
                DiagnosticArchive.create(zip) { writer ->
                    val status = StringBuilder("Internal snapshot: ${if (captured) "complete" else "unavailable or partial (storage error)"}\n")
                    for (file in LogExportCollector.collectDiagnostics(staging)) {
                        val name = file.relativeTo(staging).invariantSeparatorsPath
                        status.appendLine("$name: ${writer.file(name, file)}")
                    }
                    writer.text("diagnostics/collection_status.txt", status.toString())
                    writer.text("diagnostics/environment.txt", buildBasicInfo())
                    writer.text("diagnostics/exit_history/availability.txt", "Device API ${Build.VERSION.SDK_INT}\n" + ExitHistoryCollector.AVAILABILITY)
                    writer.text("diagnostics/README.txt", """
                        Offline diagnostic snapshot; original files are retained across app restarts.
                        events.log is newest; events.1.log through events.3.log are older generations.
                        java_crashes contains Java stacks including causes and suppressed exceptions.
                        If exit_history/records.txt is absent, system collection failed, was busy or exceeded 5 seconds.
                        API below 30 cannot provide historical exits. See exit_history/availability.txt.
                        If attachments_status.txt is absent, optional logs failed, were busy or exceeded 5 seconds.
                        Optional logs never invalidate this internal diagnostic snapshot.
                        New text diagnostics omit configuration dumps and redact common credential patterns.
                        Legacy logs and raw Android traces are not rewritten; inspect before sharing.
                    """.trimIndent() + "\n")
                }
                committed = true

                // Separate workers: an unresponsive remote Binder cannot block Android history next time.
                historyEnricher.append(zip) { ExitHistoryCollector.append(context, it) }
                legacyEnricher.append(zip) { appendLegacyLogs(it) }
                // Achievement/reporting failures must not hide a valid ZIP from share or SAF.
                withTimeoutOrNull(250) {
                    runCatching { achievementRepository.report { event = AchievementEvents.LOG_EXPORTED } }
                }
                cleanupOldExports(exportDir)
                zip
            } catch (error: Exception) {
                AppDiagnostics.record("log_export", "export_error", "error=${error.javaClass.name}")
                if (committed) zipFile else {
                    runCatching { zipFile?.delete() }
                    null
                }
            } finally {
                runCatching { snapshot?.deleteRecursively() }
            }
        }
    }

    suspend fun exportAllLogs(): Intent? = exportZip()?.let { zip ->
        runCatching { createShareIntent(zip) }.getOrElse {
            AppDiagnostics.record("log_export", "share_unavailable", "error=${it.javaClass.name}")
            null
        }
    }

    /** 写入 [targetUri]；成功返回显示名，失败返回 null。 */
    suspend fun exportToUri(targetUri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        val zip = exportZip() ?: return@withContext null
        try {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                zip.inputStream().use { it.copyTo(out) }
            } ?: return@withContext null
            queryDisplayName(targetUri) ?: zip.name
        } catch (e: Exception) {
            Timber.e(e, "Failed to export to uri: $targetUri")
            null
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query DISPLAY_NAME for $uri")
            null
        }
    }

    private fun buildBasicInfo(): String = """
        Export time: ${ZonedDateTime.now().format(INFO_TIME_FORMAT)}
        App: ${BuildConfig.APPLICATION_ID}
        Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        Build type: ${BuildConfig.BUILD_TYPE}
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        ABI: ${Build.SUPPORTED_ABIS.joinToString()}
    """.trimIndent() + "\n"

    private fun appendLegacyLogs(writer: DiagnosticArchive.Writer) {
        val status = StringBuilder()
        // A small allowlist avoids spawning getprop and dumping device identifiers or credentials.
        writer.text("properties.txt", "Selected Android Build fields (no getprop subprocess).\n" + buildBasicInfo())
        val deviceInfo = runCatching { buildDeviceInfo() }.getOrElse {
            status.appendLine("device_info: unavailable (${it.javaClass.simpleName})")
            buildBasicInfo()
        }
        writer.text("device_info.txt", deviceInfo)
        var remaining = DiagnosticArchive.MAX_ATTACHMENTS_BYTES
        val debugDir = runCatching { File(pathConfig.debugDir) }.getOrNull()
        val files = runCatching { debugDir?.let(LogExportCollector::collect).orEmpty() }.getOrElse {
            status.appendLine("legacy: unavailable (${it.javaClass.simpleName})")
            emptyList()
        }
        for (file in files.take(256)) {
            if (remaining <= 0) break
            val name = file.relativeTo(debugDir!!).invariantSeparatorsPath
            if (!LogExportCollector.isLegacyEntryAllowed(name) || name.substringBefore('/') == MaaFiles.EXPORT_REMOTE_DIR) {
                status.appendLine("Skipped reserved or unsafe local entry")
                continue
            }
            val limit = minOf(file.length(), remaining, DiagnosticArchive.MAX_ATTACHMENT_BYTES)
            status.appendLine("$name: ${writer.file(name, file, limit)}")
            remaining -= limit
        }
        status.appendLine("Local candidates=${files.size}; at most 256 files; each snapshot <=32 MiB, total attachments <=128 MiB.")
        appendRemoteDebugFiles(writer, status, remaining)
        writer.text("attachments_status.txt", status.toString())
    }

    private fun appendRemoteDebugFiles(writer: DiagnosticArchive.Writer, status: StringBuilder, byteBudget: Long) {
        if (!pathConfig.isCoreSeparated) return
        val srv = RemoteServiceManager.getInstanceOrNull()
        if (srv == null) {
            status.appendLine("remote: service not connected")
            return
        }
        val files = runCatching { srv.listCoreDebugFiles() }.getOrElse {
            status.appendLine("remote: list unavailable (${it.javaClass.simpleName})")
            return
        }
        var remaining = byteBudget
        for (rel in files.distinct().take(128)) {
            if (remaining <= 0) break
            val name = "${MaaFiles.EXPORT_REMOTE_DIR}/$rel"
            if (!LogExportCollector.isLegacyEntryAllowed(name) || !DiagnosticArchive.validName(rel)) continue
            val pfd = runCatching { srv.openCoreDebugFile(rel) }.getOrElse {
                status.appendLine("$name: unavailable (${it.javaClass.simpleName})")
                null
            } ?: continue
            val limit = minOf(remaining, DiagnosticArchive.MAX_ATTACHMENT_BYTES)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
                status.appendLine("$name: ${writer.stream(name, it, limit)}")
            }
            // Unknown remote sizes: reserve the full limit to keep the total strictly bounded.
            remaining -= limit
        }
    }

    /** 导出时采集的设备与运行环境快照，用于 issue 排障 */
    private fun buildDeviceInfo(): String = buildString {
        val line = "=".repeat(60)
        append(line).append("\n")
        append("=== MAA Droid Device & App Info ===\n")
        append("Export Time : ${ZonedDateTime.now().format(INFO_TIME_FORMAT)}\n")
        append("App         : ${BuildConfig.APPLICATION_ID}\n")
        append("Version     : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("Build Type  : ${BuildConfig.BUILD_TYPE}\n")
        append("Device      : ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android     : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Security    : ${Build.VERSION.SECURITY_PATCH}\n")
        append("ABI         : ${Build.SUPPORTED_ABIS.joinToString()}\n")
        append("--- MAA ---\n")
        append("Core        : ${MaaCoreVersion.current.ifBlank { "unknown" }}\n")
        append("Resource    : ${pathConfig.readDiskResourceVersion() ?: "none"}\n")
        append("Client      : ${taskChainState.clientType}\n")
        val coreLoc = if (pathConfig.isCoreSeparated) "LOCAL_TMP" else "APP_DIR"
        append("Core Dir    : $coreLoc (${pathConfig.coreRootDir})\n")
        append("Game        : $gameVersionInfo\n")
        append(
            "Run Mode    : ${appSettingsManager.runMode.value} / " +
                    "${appSettingsManager.backgroundResolution.value} / " +
                    "forceFullscreen=${appSettingsManager.forceFullscreenOnVirtualDisplay.value}\n"
        )
        append("--- Backend ---\n")
        append("Startup     : ${appSettingsManager.startupBackend.value.display}\n")
        append("Shizuku     : $shizukuStatus\n")
        append("--- Device ---\n")
        append("Screen      : $screenInfo (density ${context.resources.displayMetrics.densityDpi}dpi)\n")
        append("RAM         : ${memoryInfo}\n")
        append("Storage     : $storageInfo\n")
        append("Battery Opt : $batteryOptimized\n")
        append("SELinux     : $selinuxMode\n")
        append(line).append("\n")
    }

    private val gameVersionInfo: String
        get() = runCatching {
            val pkg = Packages[taskChainState.clientType] ?: return@runCatching "unknown package"
            val info = context.packageManager.getPackageInfo(pkg, 0)
            "$pkg ${info.versionName ?: "?"}"
        }.getOrElse { "${Packages[taskChainState.clientType] ?: "?"} not installed" }

    private val shizukuStatus: String
        get() = runCatching {
            if (!ShizukuManager.isShizukuAvailable()) return@runCatching "unavailable"
            val granted = if (ShizukuManager.isGranted()) "granted" else "not granted"
            val api = runCatching { Shizuku.getVersion() }.getOrNull()
            val uid = runCatching { Shizuku.getUid() }.getOrNull()
            val identity = if (uid == 0) "root" else "adb"
            "available, $granted, API $api, uid $uid ($identity)"
        }.getOrElse { "unknown" }

    private val screenInfo: String
        get() = runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            } else {
                val size = Point().also(wm.defaultDisplay::getRealSize)
                size.x to size.y
            }
            @Suppress("DEPRECATION")
            val refresh = wm.defaultDisplay.refreshRate
            "$w x $h @ ${"%.0f".format(Locale.US, refresh)}Hz"
        }.getOrElse { "unknown" }

    private val memoryInfo: String
        get() = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "${formatGb(mi.totalMem)} total, ${formatGb(mi.availMem)} free"
        }.getOrElse { "unknown" }

    private val storageInfo: String
        get() = runCatching {
            val dir = File(pathConfig.rootDir)
            "${formatGb(dir.usableSpace)} usable / ${formatGb(dir.totalSpace)} total"
        }.getOrElse { "unknown" }

    private val batteryOptimized: String
        get() = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                "ignored (app exempt)"
            } else {
                "NOT ignored (schedule may be killed)"
            }
        }.getOrElse { "unknown" }

    private val selinuxMode: String
        get() = runCatching {
            when (File("/sys/fs/selinux/enforce").readText().trim()) {
                "1" -> "enforcing"
                "0" -> "permissive"
                else -> "unknown"
            }
        }.getOrDefault("unknown")

    private fun formatGb(bytes: Long): String =
        "%.1f GB".format(Locale.US, bytes / 1024f / 1024f / 1024f)

    private fun createShareIntent(zipFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, zipFile)

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MAA Droid 日志导出")
            putExtra(
                Intent.EXTRA_TEXT,
                "MAA Droid 日志文件导出于 ${
                    ZonedDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (Z)"))
                }"
            )
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun cleanupOldExports(dir: File) {
        try {
            // Recover staging files left by process death, while leaving current work alone.
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            dir.listFiles { file ->
                (file.name.startsWith("diagnostic_snapshot_") || file.name.startsWith("diagnostic_add_")) &&
                    file.lastModified() < cutoff
            }?.forEach { it.deleteRecursively() }
            dir.listFiles { file ->
                file.isFile && file.name.startsWith("maa_logs_") && file.name.endsWith(".zip")
            }?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        } catch (e: Exception) {
            AppDiagnostics.record("log_export", "cleanup_unavailable", "error=${e.javaClass.name}")
        }
    }
}
