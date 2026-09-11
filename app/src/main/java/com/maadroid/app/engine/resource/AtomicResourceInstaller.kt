package com.maadroid.app.engine.resource

import com.maadroid.app.engine.ResourcePackSpec
import com.maadroid.app.engine.ResourceRevision
import com.maadroid.app.engine.isSafeResourcePath
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.Locale
import java.nio.file.Files
import java.util.zip.ZipFile

/** Validate in a sibling directory, then replace; interrupted installs keep the last good pack. */
class AtomicResourceInstaller(
    /**
     * 把 APK assets 下指定前缀的素材覆盖层盖到 staging 目录，参数为（前缀, staging）。
     *
     * 与 [move] 同样采用注入：本类不持有 Context，读 assets 的职责留给宿主。
     * 默认空实现，未声明 [ResourcePackSpec.overlayAssetPrefix] 的包完全不受影响。
     */
    private val applyOverlay: (String, File) -> Unit = { _, _ -> },
    /** 放在最后一个参数：调用方习惯用尾随 lambda 注入它（见 EngineResourceInstallTest） */
    private val move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    fun install(
        archive: File,
        target: File,
        pack: ResourcePackSpec,
        revision: ResourceRevision,
        ensureActive: () -> Unit = {},
        phaseChanged: (ResourceInstallPhase) -> Unit = {},
        progress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        phaseChanged(ResourceInstallPhase.VERIFYING_ARCHIVE)
        val source = requireNotNull(pack.upstreamArchive)
        require(!Files.isSymbolicLink(target.toPath())) { "Resource target must not be a symbolic link" }
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        recover(target)
        val staging = File(target.parentFile, ".${target.name}.staging-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Cannot create resource staging directory" }
        try {
            ZipFile(archive).use { zip ->
                require(zip.size() <= 100_000) { "Too many archive entries" }
                val roots = HashSet<String>()
                val entries = zip.entries().asSequence().mapNotNull { entry ->
                    ensureActive()
                    val name = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                    require(isSafeResourcePath(name)) { "Unsafe archive entry: ${entry.name}" }
                    roots += name.substringBefore('/')
                    require(roots.size == 1) { "Source archive must have one root directory" }
                    if (entry.isDirectory) null else source.mapEntry(name)?.let { entry to it }
                }.toList()
                require(entries.isNotEmpty() && entries.size <= 50_000) { "Unexpected resource file count: ${entries.size}" }
                phaseChanged(ResourceInstallPhase.EXTRACTING)
                progress(0, entries.size)
                val seen = HashSet<String>()
                var totalBytes = 0L
                var lastReport = System.nanoTime()
                val buffer = ByteArray(128 * 1024)
                for ((index, pair) in entries.withIndex()) {
                    ensureActive()
                    val (entry, relative) = pair
                    require(isSafeResourcePath(relative) && seen.add(relative.lowercase(Locale.ROOT))) { "Duplicate or unsafe resource: $relative" }
                    val dest = File(staging, relative)
                    check(dest.parentFile!!.mkdirs() || dest.parentFile!!.isDirectory)
                    var fileBytes = 0L
                    zip.getInputStream(entry).use { input ->
                        dest.outputStream().buffered(buffer.size).use { output ->
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                fileBytes += read
                                totalBytes += read
                                require(fileBytes <= 256L * 1024 * 1024 && totalBytes <= 512L * 1024 * 1024) {
                                    "Resource archive exceeds size limit"
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    val now = System.nanoTime()
                    if (now - lastReport >= 100_000_000 || index + 1 == entries.size) {
                        progress(index + 1, entries.size)
                        lastReport = now
                    }
                }
            }
            ensureActive()
            // 覆盖层必须在 finalize 之前：finalize 会写清单并做兼容校验，覆盖后的内容
            // 才是最终要用的内容。作用在 staging 上，失败不会破坏已装资源。
            pack.overlayAssetPrefix?.let { prefix ->
                ensureActive()
                applyOverlay(prefix, staging)
            }
            ensureActive()
            phaseChanged(ResourceInstallPhase.VERIFYING_FILES)
            pack.finalizeUpstreamInstall(staging, revision)
            pack.verifyInstalledFiles(staging)?.let { throw IOException(it) }
            check(!pack.readInstalledVersion(staging).isNullOrBlank()) { "Resource version missing after validation" }
            ensureActive()
            phaseChanged(ResourceInstallPhase.ACTIVATING)
            // No suspension/cancellation point between the two renames.
            val backup = backupOf(target)
            if (target.exists()) check(move(target, backup)) { "Cannot back up installed resources" }
            try {
                if (!move(staging, target)) throw IOException("Cannot activate resource update")
            } catch (failure: Exception) {
                if (!target.exists() && backup.exists()) {
                    try { check(move(backup, target)) { "Cannot restore previous resources" } }
                    catch (restore: Exception) { failure.addSuppressed(restore) }
                }
                throw failure
            }
            backup.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Called under the pack lock, also before opening an installed pack after process restart. */
    fun recover(target: File) {
        val backup = backupOf(target)
        require(!Files.isSymbolicLink(target.toPath()) && !Files.isSymbolicLink(backup.toPath())) {
            "Resource target/backup must not be a symbolic link"
        }
        if (!target.exists() && backup.exists()) {
            check(move(backup, target)) { "Cannot restore interrupted resource update" }
        } else if (target.exists() && backup.exists()) {
            check(backup.deleteRecursively()) { "Cannot remove previous resource backup" }
        }
        target.parentFile?.listFiles()?.filter {
            it.name.startsWith(".${target.name}.staging-")
        }?.forEach { it.deleteRecursively() }
    }

    private fun backupOf(target: File) = File(target.parentFile, ".${target.name}.previous")
}

enum class ResourceInstallPhase { VERIFYING_ARCHIVE, EXTRACTING, VERIFYING_FILES, ACTIVATING }
