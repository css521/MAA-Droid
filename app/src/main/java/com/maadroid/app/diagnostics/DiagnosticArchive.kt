package com.maadroid.app.diagnostics

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A completed internal ZIP is never edited in place when adding fallible sources. */
internal object DiagnosticArchive {
    const val MAX_TRACE_BYTES = 2L * 1024 * 1024
    const val MAX_ATTACHMENT_BYTES = 32L * 1024 * 1024
    const val MAX_ATTACHMENTS_BYTES = 128L * 1024 * 1024

    data class CopyResult(val bytes: Long, val status: String) {
        override fun toString(): String = "$status ($bytes bytes)"
    }

    class Writer internal constructor(private val zip: ZipOutputStream) {
        private val names = mutableSetOf<String>()

        fun text(name: String, text: String) {
            text.byteInputStream().use { stream(name, it, text.toByteArray().size.toLong()) }
        }

        fun file(name: String, file: File, limit: Long = MAX_ATTACHMENT_BYTES): CopyResult {
            val input = try { file.inputStream() } catch (error: Exception) {
                return CopyResult(0, "unavailable: ${error.javaClass.simpleName}")
            }
            return input.use { stream(name, it, limit) }
        }

        /** Copy bytes verbatim, including binary tombstones. Source errors cannot poison the ZIP. */
        fun stream(name: String, input: InputStream, limit: Long): CopyResult {
            require(limit >= 0)
            require(validName(name) && names.add(name)) { "Unsafe or duplicate ZIP entry" }
            zip.putNextEntry(ZipEntry(name))
            var remaining = limit
            var status = "complete"
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = try { input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()) }
                catch (error: Exception) {
                    status = "partial: ${error.javaClass.simpleName}"
                    break
                }
                if (count < 0) break
                if (count == 0) {
                    status = "partial: source returned no data"
                    break
                }
                // Output errors propagate: discard the candidate instead of returning a corrupt ZIP.
                zip.write(buffer, 0, count)
                remaining -= count
            }
            if (remaining == 0L) status = "limit reached; may be truncated"
            zip.closeEntry()
            return CopyResult(limit - remaining, status)
        }
    }

    fun validName(name: String): Boolean = name.isNotEmpty() && !name.startsWith('/') &&
        !name.contains('\\') && !name.contains(':') && !name.contains('\u0000') &&
        name.split('/').none { it.isEmpty() || it == "." || it == ".." }

    fun create(file: File, entries: (Writer) -> Unit) {
        FileOutputStream(file).use { output ->
            val zip = ZipOutputStream(BufferedOutputStream(output))
            zip.use {
                entries(Writer(zip))
                zip.finish()
                zip.flush()
                output.fd.sync()
            }
        }
    }

    /** One daemon per source group; a hung Binder cannot accumulate workers on repeated exports. */
    class Enricher(name: String) {
        private val executor = ThreadPoolExecutor(
            0, 1, 10, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, name).apply { isDaemon = true } },
        )

        fun append(baseZip: File, timeoutMs: Long = 5000, entries: (Writer) -> Unit): Boolean {
            var candidate: File? = null
            var future: java.util.concurrent.Future<*>? = null
            val abandoned = AtomicBoolean(false)
            return try {
                val pending = File.createTempFile("diagnostic_add_", ".tmp", baseZip.parentFile)
                candidate = pending
                future = executor.submit {
                    try {
                        if (!abandoned.get()) create(pending) { writer ->
                            ZipFile(baseZip).use { original ->
                                val existing = original.entries()
                                while (existing.hasMoreElements()) {
                                    val entry = existing.nextElement()
                                    original.getInputStream(entry).use {
                                        check(writer.stream(entry.name, it, entry.size).bytes == entry.size)
                                    }
                                }
                            }
                            entries(writer)
                        }
                    } finally {
                        if (abandoned.get()) pending.delete()
                    }
                }
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
                Files.move(pending.toPath(), baseZip.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                true
            } catch (_: Exception) {
                abandoned.set(true)
                future?.cancel(true)
                false
            } finally {
                // Only the waiting caller can publish. A timed-out worker cannot replace the base later.
                runCatching { candidate?.delete() }
            }
        }
    }
}
