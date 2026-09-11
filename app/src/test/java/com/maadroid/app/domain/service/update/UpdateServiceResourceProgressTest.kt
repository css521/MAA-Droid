package com.maadroid.app.domain.service.update

import com.maadroid.app.data.datasource.*
import com.maadroid.app.data.model.update.*
import com.maadroid.app.engine.ResourcePackSpec
import io.mockk.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateServiceResourceProgressTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sharedResourceUpdateReportsBytesVerificationExtractionInstallationAndSuccess() = runBlocking {
        val callerThread = Thread.currentThread()
        val archive = temp.newFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("root/resource.json"))
                zip.write("resource".toByteArray())
                zip.closeEntry()
            }
        }
        val downloader = mockk<ResourceDownloader>()
        coEvery { downloader.downloadToTempFile(any(), any()) } coAnswers {
            assertNotSame(callerThread, Thread.currentThread())
            secondArg<(DownloadProgress) -> Unit>()(DownloadProgress(0, "1 KB/s", archive.length(), 0))
            Result.success(archive)
        }
        val pack = mockk<ResourcePackSpec> {
            every { upstreamArchive } returns null
            every { packId } returns "resource-progress-test"
            every { requiresPrivilegedDelivery } returns false
            every { mapZipEntry(any()) } answers {
                assertNotSame(callerThread, Thread.currentThread())
                firstArg<String>().removePrefix("root/")
            }
        }
        val service = UpdateService(mockk(relaxed = true), mockk(), mockk(), mockk(), mockk(), mockk(),
            mockk(), downloader, ZipExtractor(), mockk(relaxed = true), mockk())
        val events = mutableListOf<UpdateProcessState>()
        val collect = launch(Dispatchers.Unconfined) { service.resourceProcessState.toList(events) }
        val target = File(temp.root, "installed")
        assertTrue(service.downloadResource(UpdateSource.GITHUB, "old", target, pack).isSuccess)
        collect.cancelAndJoin()
        assertTrue(events.filterIsInstance<UpdateProcessState.Downloading>().any { it.downloaded > 0 && it.bytes.percent == null })
        val verifying = events.indexOf(UpdateProcessState.Verifying)
        val extracting = events.indexOfFirst { it is UpdateProcessState.Extracting }
        val installing = events.indexOf(UpdateProcessState.Installing)
        assertTrue(verifying >= 0 && extracting > verifying && installing > extracting)
        assertEquals(UpdateProcessState.Success, events.last())
        assertEquals("resource", File(target, "resource.json").readText())
        assertFalse(archive.exists())
    }

    @Test fun cancellationDuringExtractionInvalidatesPartialInstallationAndCleansArchive() = runBlocking {
        val archive = temp.newFile()
        val downloader = mockk<ResourceDownloader>()
        coEvery { downloader.downloadToTempFile(any(), any()) } returns Result.success(archive)
        val extractor = mockk<ZipExtractor>()
        coEvery { extractor.extract(any(), any(), any(), any()) } coAnswers {
            arg<(ZipExtractor.ExtractProgress) -> Unit>(3)(ZipExtractor.ExtractProgress(0, 0, 1))
            throw CancellationException("stop")
        }
        val pack = mockk<ResourcePackSpec>(relaxed = true) {
            every { upstreamArchive } returns null
        }
        val target = temp.newFolder()
        val service = UpdateService(mockk(relaxed = true), mockk(), mockk(), mockk(), mockk(), mockk(),
            mockk(), downloader, extractor, mockk(relaxed = true), mockk())
        val error = runCatching { service.downloadResource(UpdateSource.GITHUB, "old", target, pack) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        verify(exactly = 1) { pack.invalidateInstalledVersion(target) }
        assertEquals(UpdateProcessState.Idle, service.resourceProcessState.value)
        assertFalse(archive.exists())
    }
}
