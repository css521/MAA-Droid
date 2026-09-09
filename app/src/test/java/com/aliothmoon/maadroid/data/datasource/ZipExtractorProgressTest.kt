package com.aliothmoon.maadroid.data.datasource

import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipExtractorProgressTest {
    @get:Rule val temp = TemporaryFolder()

    private fun archive(): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("root/large.bin", "root/version.json")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(ByteArray(512 * 1024) { (it % 251).toByte() })
                zip.closeEntry()
            }
        }
    }

    @Test fun verifiesThenExtractsOnIoAndFinishesWithExactFileCount() {
        val zip = archive()
        Executors.newSingleThreadExecutor { Thread(it, "resource-ui-test") }.asCoroutineDispatcher().use { ui ->
            runBlocking(ui) {
                val callerThread = Thread.currentThread()
                val events = mutableListOf<ZipExtractor.ExtractProgress>()
                val target = File(temp.root, "output")
                val result = ZipExtractor().extract(zip, target, { path ->
                    assertNotSame(callerThread, Thread.currentThread())
                    path.removePrefix("root/")
                }) {
                    assertNotSame(callerThread, Thread.currentThread())
                    events += it
                }
                assertEquals(2, result.getOrThrow())
                assertEquals(ZipExtractor.Phase.VERIFYING, events.first().phase)
                assertEquals(ZipExtractor.ExtractProgress(100, 2, 2), events.last())
                assertEquals(512L * 1024, File(target, "large.bin").length())
                assertSame(callerThread, Thread.currentThread())
            }
        }
    }

    @Test fun cancellationDuringVerificationDoesNotWriteFilesOrReportSuccess() = runBlocking {
        val target = File(temp.root, "cancelled")
        val error = runCatching {
            ZipExtractor().extract(archive(), target, { throw CancellationException("cancel scan") }, {})
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(target.exists())
    }

    @Test fun invalidArchiveAndUnsafeMappedPathFailBeforeWriting() = runBlocking {
        val target = File(temp.root, "bad")
        val invalid = temp.newFile().also { it.writeText("not a zip") }
        assertTrue(ZipExtractor().extract(invalid, target, { it }, {}).isFailure)
        assertTrue(ZipExtractor().extract(archive(), target, { "../escape" }, {}).isFailure)
        assertFalse(target.exists())
        assertFalse(File(temp.root, "escape").exists())
    }
}
