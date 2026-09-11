package com.maadroid.app.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID

/** JVM-only, bounded store. A shared OS lock also protects rotation across app processes. */
internal class DiagnosticStore(
    private val directory: File,
    private val identity: String,
    private val maxFileBytes: Int = 256 * 1024,
    private val maxFiles: Int = 4,
    private val maxCrashFiles: Int = 10,
    private val maxCrashBytes: Int = 1024 * 1024,
) {
    companion object {
        const val DIRECTORY = "diagnostics"
        private const val MAX_RECORD_BYTES = 4096
        private val processLock = Any()
    }

    init {
        require(maxFileBytes >= 256 && maxFiles > 0 && maxCrashFiles > 0 && maxCrashBytes >= 256)
    }

    fun record(component: String, phase: String, detail: String = ""): Boolean = safely {
        val line = "${Instant.now()} epoch_ms=${System.currentTimeMillis()} $identity " +
            "component=${DiagnosticText.field(component, 96)} phase=${DiagnosticText.field(phase, 96)} " +
            "detail=${DiagnosticText.field(detail, 2048)}"
        val bytes = DiagnosticText.utf8Prefix(line, minOf(MAX_RECORD_BYTES, maxFileBytes) - 1) + byteArrayOf(10)
        locked {
            val active = File(directory, "events.log")
            if (active.length() + bytes.size > maxFileBytes) rotate(active)
            FileOutputStream(active, true).use { output ->
                output.write(bytes)
                // A completed phase survives immediate process death; no queue/Timber dependency.
                output.fd.sync()
            }
        }
    }

    fun crash(component: String, phase: String, thread: String, error: Throwable): Boolean = safely {
        locked {
            val crashDir = File(directory, "java_crashes")
            check(crashDir.isDirectory || crashDir.mkdirs())
            val old = crashDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }.orEmpty()
            old.drop(maxCrashFiles - 1).forEach { check(it.delete()) }
            val file = File(crashDir, "crash_${System.currentTimeMillis()}_${UUID.randomUUID()}.txt")
            FileOutputStream(file).use { output ->
                val writer = PrintWriter(DiagnosticStackWriter(output, maxCrashBytes))
                writer.println("${Instant.now()} $identity")
                writer.println("component=${DiagnosticText.field(component)} phase=${DiagnosticText.field(phase)} thread=${DiagnosticText.field(thread)}")
                error.printStackTrace(writer)
                writer.flush()
                check(!writer.checkError())
                output.fd.sync()
            }
        }
    }

    /** Copy under the rotation lock; exports never consume or clear the original files. */
    fun snapshot(destination: File): Boolean {
        var complete = true
        val accessible = safely {
            locked {
                check(destination.isDirectory || destination.mkdirs())
                val files = directory.listFiles().orEmpty().filter {
                    it.isFile && (it.name == "events.log" || it.name.matches(Regex("events\\.\\d+\\.log")))
                } + File(directory, "java_crashes").listFiles().orEmpty().filter { it.isFile }
                files.forEach { source ->
                    try {
                        val target = File(destination, source.relativeTo(directory).path)
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        source.copyTo(target, overwrite = true)
                    } catch (_: Throwable) {
                        complete = false // One unreadable source must not hide the other diagnostics.
                    }
                }
            }
        }
        return accessible && complete
    }

    private fun rotate(active: File) {
        val oldest = if (maxFiles == 1) active else File(directory, "events.${maxFiles - 1}.log")
        if (oldest.exists()) check(oldest.delete())
        for (index in maxFiles - 2 downTo 1) {
            val source = File(directory, "events.$index.log")
            if (source.exists()) check(source.renameTo(File(directory, "events.${index + 1}.log")))
        }
        if (maxFiles > 1 && active.exists()) check(active.renameTo(File(directory, "events.1.log")))
    }

    private fun locked(block: () -> Unit) = synchronized(processLock) {
        check(directory.isDirectory || directory.mkdirs())
        RandomAccessFile(File(directory, ".lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use { block() }
        }
    }

    private inline fun safely(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: Throwable) {
        false // Including ENOSPC: diagnostics must not cause a second crash.
    }
}
