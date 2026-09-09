package com.aliothmoon.maadroid.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun newWriterRetainsPreviousProcessAndSnapshotDoesNotConsumeLogs() {
        val directory = temp.newFolder("diagnostics")
        assertTrue(DiagnosticStore(directory, "pid=101 process=old version=1 abi=arm64").record("limbus", "before_native_init"))
        val next = DiagnosticStore(directory, "pid=202 process=new version=2 abi=arm64")
        assertTrue(next.record("application", "restart"))
        val snapshot = File(temp.root, "snapshot")
        assertTrue(next.snapshot(snapshot))
        val lines = File(snapshot, "events.log").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("pid=101 process=old version=1 abi=arm64"))
        assertTrue(lines[0].contains("phase=before_native_init"))
        assertTrue(lines[1].contains("pid=202"))
        assertEquals(File(directory, "events.log").readText(), File(snapshot, "events.log").readText())
        assertFalse(File(snapshot, ".lock").exists())
    }

    @Test fun rotationBoundsTotalAndPreservesNewestAcrossWriterRestart() {
        val directory = temp.newFolder("diagnostics")
        for (index in 0..99) {
            val writer = DiagnosticStore(directory, "pid=$index", maxFileBytes = 512, maxFiles = 3)
            assertTrue(writer.record("limbus", "boundary_$index", "short fact ".repeat(12)))
        }
        val files = directory.listFiles()!!.filter { it.extension == "log" }
        assertEquals(3, files.size)
        assertTrue(files.all { it.length() <= 512 })
        assertTrue(files.sumOf { it.length() } <= 1536)
        assertTrue(File(directory, "events.log").readText().contains("boundary_99"))
        assertFalse(files.joinToString("") { it.readText() }.contains("phase=boundary_0 "))
    }

    @Test fun oneGenerationStillRotatesAndKeepsUtf8RecordsWhole() {
        val directory = temp.newFolder("diagnostics")
        val writer = DiagnosticStore(directory, "pid=1", maxFileBytes = 256, maxFiles = 1)
        repeat(5) { assertTrue(writer.record("limbus", "phase_$it", "中文阶段".repeat(500))) }
        val file = File(directory, "events.log")
        assertTrue(file.length() <= 256)
        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(file.readBytes())).toString()
        assertTrue(decoded.contains("phase=phase_4"))
        assertTrue(decoded.endsWith("\n"))
        assertEquals(1, directory.listFiles()!!.count { it.extension == "log" })
    }

    @Test fun multipleWritersDoNotInterleaveOrLoseRecords() {
        val directory = temp.newFolder("diagnostics")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (0..39).map { index -> pool.submit<Boolean> {
                DiagnosticStore(directory, "pid=$index").record("parallel", "phase_$index")
            } }
            assertTrue(results.all { it.get(10, TimeUnit.SECONDS) })
        } finally { pool.shutdownNow() }
        val lines = File(directory, "events.log").readLines()
        assertEquals(40, lines.size)
        assertEquals(40, lines.map { it.substringAfter("phase=") }.toSet().size)
    }

    @Test fun blockedStorageDoesNotThrowAndCanRecoverLater() {
        val directory = temp.newFile("diagnostics")
        val writer = DiagnosticStore(directory, "pid=1")
        assertFalse(writer.record("limbus", "start"))
        assertFalse(writer.crash("limbus", "start", "main", IllegalStateException("failed")))
        assertFalse(writer.snapshot(File(temp.root, "snapshot")))
        assertTrue(directory.delete())
        assertTrue(writer.record("limbus", "retry"))
    }

    @Test fun failedSnapshotFileStillCopiesOtherInternalDiagnostics() {
        val directory = temp.newFolder("diagnostics")
        val writer = DiagnosticStore(directory, "pid=1")
        assertTrue(writer.record("limbus", "start"))
        assertTrue(writer.crash("limbus", "start", "main", Exception("crash")))
        val snapshot = temp.newFolder("snapshot")
        File(snapshot, "events.log/blocked").apply { parentFile.mkdirs(); writeText("block replacement") }
        assertFalse(writer.snapshot(snapshot))
        val copied = File(snapshot, "java_crashes").listFiles()!!.single()
        assertTrue(copied.readText().contains("java.lang.Exception: crash"))
    }

    @Test fun crashContainsCausesSuppressedAndDeepFramesButRedactsCredentials() {
        val directory = temp.newFolder("diagnostics")
        val error = IllegalStateException("native init failed", IllegalArgumentException("password=secret with spaces"))
        error.addSuppressed(UnsupportedOperationException("suppressed failure"))
        error.stackTrace = arrayOf(StackTraceElement("NativeInit", "load", "NativeInit.kt", 42))
        assertTrue(DiagnosticStore(directory, "pid=2 abi=arm64").crash("limbus", "native", "main", error))
        val text = File(directory, "java_crashes").listFiles()!!.single().readText()
        assertTrue(text.contains("NativeInit.load(NativeInit.kt:42)"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(text.contains("Suppressed: java.lang.UnsupportedOperationException"))
        assertFalse(text.contains("secret with spaces"))
        assertTrue(text.contains("password=[redacted]"))
    }

    @Test fun crashCountAndSizeAreBoundedAndTruncationIsExplicit() {
        val directory = temp.newFolder("diagnostics")
        repeat(5) {
            val error = Exception("oversized")
            error.stackTrace = Array(1000) { index -> StackTraceElement("NativeInit", "load", "NativeInit.kt", index) }
            assertTrue(DiagnosticStore(directory, "pid=$it", maxCrashFiles = 2, maxCrashBytes = 1024)
                .crash("limbus", "native", "main", error))
        }
        val files = File(directory, "java_crashes").listFiles()!!
        assertEquals(2, files.size)
        assertTrue(files.all { it.length() <= 1024 && it.readText().contains("stack truncated") })
    }

    @Test fun breadcrumbsOmitConfigAndSecretsAndCannotInjectLines() {
        val directory = temp.newFolder("diagnostics")
        val writer = DiagnosticStore(directory, "pid=1")
        assertTrue(writer.record("limbus", "before", "access_token=abc123 password=secret"))
        assertTrue(writer.record("limbus", "before", "{\"tasks\":[\"private-task\"]}"))
        assertTrue(writer.record("limbus", "before", "TaskConfig(tasks=private-task)"))
        assertTrue(writer.record("limbus\ninjected", "before", "Authorization: Bearer abcxyz"))
        val lines = File(directory, "events.log").readLines()
        assertEquals(4, lines.size)
        val text = lines.joinToString("\n")
        for (secret in listOf("abc123", "secret", "private-task", "abcxyz")) assertFalse(text.contains(secret))
        assertTrue(text.contains("[structured data omitted]"))
        assertTrue(text.contains("[redacted]"))
    }
}
