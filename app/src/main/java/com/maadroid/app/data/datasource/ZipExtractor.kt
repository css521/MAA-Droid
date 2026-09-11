package com.maadroid.app.data.datasource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

class ZipExtractor {
    enum class Phase { VERIFYING, EXTRACTING }

    data class ExtractProgress(
        val progress: Int,
        val current: Int,
        val total: Int,
        val phase: Phase = Phase.EXTRACTING,
    )

    /** Scan/CRC verification, streamed extraction and buffered flushes all run on IO. */
    suspend fun extract(
        zipFile: File,
        destDir: File,
        pathFilter: (String) -> String?,
        onProgress: (ExtractProgress) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            onProgress(ExtractProgress(0, 0, 0, Phase.VERIFYING))
            val buffer = ByteArray(128 * 1024)
            val root = destDir.canonicalFile
            fun destination(relative: String): File = File(root, relative).canonicalFile.also {
                require(it.path.startsWith(root.path + File.separator)) { "Unsafe resource path: $relative" }
            }
            var total = 0
            // Reading each entry verifies its ZIP CRC before modifying installed resources.
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) pathFilter(entry.name)?.let {
                        destination(it)
                        total++
                    }
                    while (zip.read(buffer) >= 0) currentCoroutineContext().ensureActive()
                    zip.closeEntry()
                }
            }
            require(total > 0) { "Archive contains no matching resource files" }
            onProgress(ExtractProgress(0, 0, total))
            var count = 0
            var lastReport = System.nanoTime()
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val relative = if (entry.isDirectory) null else pathFilter(entry.name)
                    if (relative != null) {
                        val file = destination(relative)
                        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                        file.outputStream().buffered(buffer.size).use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = zip.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                        count++
                        val now = System.nanoTime()
                        if (now - lastReport >= 100_000_000 || count == total) {
                            onProgress(ExtractProgress(count * 100 / total, count, total))
                            lastReport = now
                        }
                    } else {
                        while (zip.read(buffer) >= 0) currentCoroutineContext().ensureActive()
                    }
                    zip.closeEntry()
                }
            }
            Result.success(count)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.e(e, "解压失败")
            Result.failure(e)
        }
    }
}
