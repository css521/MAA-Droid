package com.maadroid.app.diagnostics

import com.maadroid.app.domain.service.LogExportCollector
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class DiagnosticArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private fun baseline(): File {
        val filesDir = temp.newFolder()
        val directory = File(filesDir, "diagnostics")
        val old = DiagnosticStore(directory, "pid=101 version=old")
        assertTrue(old.record("limbus", "before_native_init"))
        assertTrue(old.crash("limbus", "native", "main", IllegalStateException("previous crash")))
        assertTrue(DiagnosticStore(directory, "pid=202 version=new").record("application", "restart"))
        val zip = File(temp.newFolder(), "maa_logs_test.zip")
        DiagnosticArchive.create(zip) { writer ->
            for (file in LogExportCollector.collectDiagnostics(filesDir)) {
                writer.file(file.relativeTo(filesDir).invariantSeparatorsPath, file)
            }
        }
        return zip
    }

    private fun assertDiagnosticContents(zip: File) = ZipFile(zip).use { reader ->
        val entries = reader.entries().asSequence().toList()
        assertTrue(entries.any { it.name.startsWith("diagnostics/java_crashes/") })
        val events = reader.getInputStream(reader.getEntry("diagnostics/events.log")).bufferedReader().use { it.readText() }
        assertTrue(events.contains("before_native_init"))
        assertTrue(events.contains("phase=restart"))
        assertFalse(entries.any { it.name.endsWith(".lock") })
        // Read every entry, including its CRC/decompression path.
        entries.forEach { reader.getInputStream(it).use { input -> input.readBytes() } }
    }

    @Test fun packagesInternalDiagnosticsWithNoMaaDirectory() {
        assertDiagnosticContents(baseline())
    }

    @Test fun attachmentFailureAfterEntryWritePreservesOriginalZipExactly() {
        val zip = baseline()
        val original = zip.readBytes()
        val appended = DiagnosticArchive.Enricher("test-throwing-source").append(zip) {
            it.text("legacy/partial.txt", "some data")
            throw IOException("remote binder failed")
        }
        assertFalse(appended)
        assertArrayEquals(original, zip.readBytes())
        assertDiagnosticContents(zip)
    }

    @Test fun failedLegacyAppendKeepsPreviouslyCollectedExitHistory() {
        val zip = baseline()
        assertTrue(DiagnosticArchive.Enricher("test-history").append(zip) {
            it.text("diagnostics/exit_history/records.txt", "reason=5 status=11 process=app timestamp=100")
        })
        assertFalse(DiagnosticArchive.Enricher("test-legacy").append(zip) { throw IOException("failed") })
        ZipFile(zip).use { assertNotNull(it.getEntry("diagnostics/exit_history/records.txt")) }
        assertDiagnosticContents(zip)
    }

    @Test fun uninterruptibleSourceTimesOutWithoutPublishingLaterAndRejectsExtraWorkers() {
        val zip = baseline()
        val original = zip.readBytes()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val enricher = DiagnosticArchive.Enricher("test-stuck-binder")
        try {
            assertFalse(enricher.append(zip, timeoutMs = 500) { writer ->
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Simulate Binder ignoring cancellation. */ }
                }
                try { writer.text("legacy/late.txt", "late result") } finally { finished.countDown() }
            })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(enricher.append(zip, timeoutMs = 100) { fail("Must not spawn a second worker") })
            assertArrayEquals(original, zip.readBytes())
        } finally { release.countDown() }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertArrayEquals(original, zip.readBytes())
        assertDiagnosticContents(zip)
    }

    @Test fun sourceReadFailureAndMissingFileStillProduceReadableArchive() {
        val zip = baseline()
        assertTrue(DiagnosticArchive.Enricher("test-read-error").append(zip) { writer ->
            assertTrue(writer.file("legacy/missing.log", File(temp.root, "missing")).status.startsWith("unavailable"))
            val input = object : InputStream() {
                var reads = 0
                override fun read(): Int = throw IOException("failed")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ > 0) throw IOException("source disappeared")
                    buffer[offset] = 42
                    return 1
                }
            }
            val result = writer.stream("legacy/broken.log", input, 1024)
            assertEquals(1L, result.bytes)
            assertTrue(result.status.startsWith("partial"))
            writer.text("attachments_status.txt", result.toString())
        })
        assertDiagnosticContents(zip)
    }

    @Test fun rawTraceIsByteExactAndBoundedWithoutWaitingForEof() {
        val zip = baseline()
        var count = 0
        val source = object : InputStream() {
            override fun read(): Int = (count++ % 256)
        }
        assertTrue(DiagnosticArchive.Enricher("test-raw-trace").append(zip) { writer ->
            val result = writer.stream("diagnostics/exit_history/trace.bin", source, 1024)
            assertEquals(1024L, result.bytes)
            assertTrue(result.status.contains("limit"))
        })
        assertEquals(1024, count)
        ZipFile(zip).use { reader ->
            val bytes = reader.getInputStream(reader.getEntry("diagnostics/exit_history/trace.bin")).use { it.readBytes() }
            assertArrayEquals(ByteArray(1024) { (it % 256).toByte() }, bytes)
        }
    }

    @Test fun unsafeOrDuplicateAttachmentNamesCannotInvalidateDiagnostics() {
        val zip = baseline()
        val original = zip.readBytes()
        val enricher = DiagnosticArchive.Enricher("test-unsafe-path")
        for (name in listOf("../escape", "/absolute", "a/../b", "a\\b", "diagnostics/events.log")) {
            assertFalse(enricher.append(zip) { it.text(name, "bad") })
            assertArrayEquals(original, zip.readBytes())
        }
    }
}
