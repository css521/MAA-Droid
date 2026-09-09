package com.aliothmoon.maadroid.engine.resource

import com.aliothmoon.maadroid.engine.*
import com.aliothmoon.maadroid.data.datasource.DownloadProgress
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineResourceInstallTest {
    @get:Rule val temp = TemporaryFolder()
    private val first = ResourceRevision("v5.0.0", "a".repeat(40))
    private val second = ResourceRevision("v5.1.0", "b".repeat(40))
    private val pack = object : ResourcePackSpec {
        override val packId = "resource-install-test"
        override val engineId = "limbus"
        override val relativeRoot = "engines/limbus"
        override val bundledAssetPrefix: String? = null
        override val requiresPrivilegedDelivery = false
        // A second repository proves the host service follows the pack declaration, not LALC constants.
        override val upstreamArchive = UpstreamArchive("Example/ResourceUpstream", first, "lalc_backend", listOf("config/task"))
        override fun finalizeUpstreamInstall(resourceDir: File, revision: ResourceRevision) {
            check(File(resourceDir, "config/task/main.json").readText() == "good") { "invalid resources" }
            File(resourceDir, "manifest.json").writeText("""{"revision":"${revision.commit}","upstream":{"repo":"${upstreamArchive.repository}","tag":"${revision.tag}","commit":"${revision.commit}"}}""")
        }
        override fun verifyInstalledFiles(resourceDir: File): String? =
            if (File(resourceDir, "config/task/main.json").readText() == "good") null else "bad resource"
        override fun readInstalledVersion(resourceDir: File): String? = File(resourceDir, "manifest.json").takeIf { it.isFile }?.readText()
        override fun invalidateInstalledVersion(resourceDir: File) { File(resourceDir, "manifest.json").delete() }
        override fun checkCompatibility(manifestJson: String?): String? = null
        override fun mapZipEntry(entryName: String): String? = null
    }
    private fun archive(vararg entries: Pair<String, String>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
    }
    private fun goodArchive(value: String = "good") = archive("repo/lalc_backend/config/task/main.json" to value)
    private fun installed(): File = File(temp.root, pack.relativeRoot).also {
        AtomicResourceInstaller().install(goodArchive(), it, pack, first)
    }
    private fun fails(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } catch (_: IOException) { } }

    @Test fun validationFailureKeepsPreviousInstallation() {
        val target = installed()
        val before = pack.readInstalledVersion(target)
        fails { AtomicResourceInstaller().install(goodArchive("bad"), target, pack, second) }
        assertEquals(before, pack.readInstalledVersion(target))
        assertFalse(target.parentFile!!.listFiles()!!.any { it.name.contains("staging") })
    }
    @Test fun rejectsTraversalAndCaseCollisionsBeforeReplacing() {
        val target = installed()
        for (entry in listOf("repo/lalc_backend/config/task/../../escape", "/absolute", "repo/lalc_backend/config/task/a\\b")) {
            fails { AtomicResourceInstaller().install(archive(entry to "bad"), target, pack, second) }
        }
        fails { AtomicResourceInstaller().install(archive(
            "repo/lalc_backend/config/task/main.json" to "good",
            "repo/lalc_backend/config/task/MAIN.json" to "good",
        ), target, pack, second) }
        assertTrue(pack.readInstalledVersion(target)!!.contains(first.commit))
        assertFalse(File(temp.root, "escape").exists())
    }
    @Test fun failedActivationRestoresBackup() {
        val target = installed()
        val installer = AtomicResourceInstaller { from, to ->
            if (from.name.contains("staging") && to == target) false else from.renameTo(to)
        }
        fails { installer.install(goodArchive(), target, pack, second) }
        assertTrue(pack.readInstalledVersion(target)!!.contains(first.commit))
    }
    @Test fun recoverInterruptedRenameAndDiscardStaging() {
        val target = installed()
        assertTrue(target.renameTo(File(target.parentFile, ".${target.name}.previous")))
        File(target.parentFile, ".${target.name}.staging-abandoned").mkdirs()
        AtomicResourceInstaller().recover(target)
        assertTrue(pack.readInstalledVersion(target)!!.contains(first.commit))
        assertEquals(listOf(target.name), target.parentFile!!.list()!!.toList())
    }
    @Test fun cancellationKeepsPreviousInstallation() {
        val target = installed()
        try {
            AtomicResourceInstaller().install(goodArchive(), target, pack, second, ensureActive = { throw CancellationException() })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(pack.readInstalledVersion(target)!!.contains(first.commit))
    }
    @Test fun initialInstallAndOfflineEnsureDoNotCallTags() = runBlocking {
        var downloads = 0
        val transport = object : ResourceTransport {
            override suspend fun tags(url: String): ResourceTagPage = error("API must not be called")
            override suspend fun download(url: String, progress: (DownloadProgress) -> Unit): File {
                assertEquals(pack.upstreamArchive.archiveUrl(first), url)
                downloads++
                return goodArchive()
            }
        }
        val service = EngineResourceService(temp.root, transport)
        assertTrue(service.ensureInstalled(pack).isSuccess)
        assertTrue(service.ensureInstalled(pack).isSuccess)
        assertEquals(1, downloads)
        assertEquals(ResourcePhase.READY, service.state(pack).value.phase)
        assertEquals(first, service.state(pack).value.installedRevision)
    }
    @Test fun api403RetainsInstalledVersionAndReportsFailure() = runBlocking {
        val target = installed()
        val before = pack.readInstalledVersion(target)
        val transport = object : ResourceTransport {
            override suspend fun tags(url: String): ResourceTagPage = throw IOException("HTTP 403")
            override suspend fun download(url: String, progress: (DownloadProgress) -> Unit): File = error("must not download")
        }
        val service = EngineResourceService(temp.root, transport)
        assertTrue(service.checkForUpdate(pack).isFailure)
        assertEquals(ResourcePhase.FAILED, service.state(pack).value.phase)
        assertEquals(first, service.state(pack).value.installedRevision)
        assertEquals(before, pack.readInstalledVersion(target))
    }
    @Test fun sourceMappingQuarantinesPython() {
        val source = pack.upstreamArchive.copy(inspectionSuffixes = listOf(".py"))
        assertEquals(".upstream-source/task_action/new.py", source.mapEntry("repo/lalc_backend/task_action/new.py"))
        assertNull(source.mapEntry("repo/lalc_backend/../main.py"))
        assertNull(source.mapEntry("repo/other/config/task/main.json"))
    }
    private fun offlineTransport() = object : ResourceTransport {
        override suspend fun tags(url: String): ResourceTagPage = error("No network expected")
        override suspend fun download(url: String, progress: (DownloadProgress) -> Unit): File = error("No download expected")
    }
    @Test fun refreshInstalledIsOfflineAndDoesNotRecoverOrDeleteFiles() = runBlocking {
        val target = installed()
        val staging = File(target.parentFile, ".${target.name}.staging-do-not-touch").also { it.mkdirs() }
        val before = File(target, "manifest.json").readBytes()
        val service = EngineResourceService(temp.root, offlineTransport())
        val state = service.refreshInstalled(pack).getOrThrow()
        assertEquals(ResourcePhase.READY, state.phase)
        assertEquals(first, state.installedRevision)
        assertTrue(staging.isDirectory)
        assertArrayEquals(before, File(target, "manifest.json").readBytes())
    }
    @Test fun refreshAbsentPackDoesNotCreateDirectories() = runBlocking {
        val service = EngineResourceService(temp.root, offlineTransport())
        assertEquals(ResourcePhase.NOT_INSTALLED, service.refreshInstalled(pack).getOrThrow().phase)
        assertFalse(File(temp.root, pack.relativeRoot).exists())
    }
    @Test fun updatesFailBusyImmediatelyWhileEngineHoldsLease() = runBlocking {
        installed()
        val service = EngineResourceService(temp.root, offlineTransport())
        service.refreshInstalled(pack).getOrThrow()
        ResourcePackLocks.acquire(pack.packId).use {
            withTimeout(2_000) {
                assertTrue(service.update(pack).exceptionOrNull() is ResourcePackBusyException)
                assertTrue(service.checkForUpdate(pack).exceptionOrNull() is ResourcePackBusyException)
            }
            assertEquals(ResourcePhase.FAILED, service.state(pack).value.phase)
            assertEquals(first, service.state(pack).value.installedRevision)
        }
    }

    @Test fun installerReportsVerificationExtractionAndActivationInOrder() {
        val phases = mutableListOf<ResourceInstallPhase>()
        val counts = mutableListOf<Pair<Int, Int>>()
        AtomicResourceInstaller().install(
            goodArchive(), File(temp.root, "phases"), pack, first,
            phaseChanged = phases::add,
            progress = { done, total -> counts += done to total },
        )
        assertEquals(listOf(ResourceInstallPhase.VERIFYING_ARCHIVE, ResourceInstallPhase.EXTRACTING,
            ResourceInstallPhase.VERIFYING_FILES, ResourceInstallPhase.ACTIVATING), phases)
        assertEquals(listOf(0 to 1, 1 to 1), counts)
    }

    @Test fun lalctypeServiceEmitsByteProgressAndEveryInstallPhaseBeforeReady() = runBlocking {
        val callerThread = Thread.currentThread()
        val transport = object : ResourceTransport {
            override suspend fun tags(url: String): ResourceTagPage = error("No tags expected")
            override suspend fun download(url: String, progress: (DownloadProgress) -> Unit): File {
                assertNotSame(callerThread, Thread.currentThread())
                progress(DownloadProgress(0, "1 KB/s", 1024, 0))
                progress(DownloadProgress(0, "2 KB/s", 4096, 0))
                return goodArchive()
            }
        }
        val service = EngineResourceService(temp.root, transport)
        val events = mutableListOf<EngineResourceState>()
        val collect = launch(Dispatchers.Unconfined) { service.state(pack).toList(events) }
        assertTrue(service.ensureInstalled(pack).isSuccess)
        collect.cancelAndJoin()
        val bytes = events.mapNotNull { it.download }.map { it.bytes }.distinct()
        assertEquals(listOf(1024L, 4096L), bytes.map { it.downloaded })
        assertTrue(bytes.all { it.percent == null })
        val phases = events.map { it.phase }.distinct()
        for (phase in listOf(ResourcePhase.DOWNLOADING, ResourcePhase.VERIFYING, ResourcePhase.EXTRACTING, ResourcePhase.INSTALLING)) {
            assertTrue("Missing phase $phase", phase in phases)
            assertTrue(events.filter { it.phase == phase }.all { it.busy })
        }
        assertEquals(ResourcePhase.READY, events.last().phase)
        assertFalse(events.last().busy)
    }
}
