package com.aliothmoon.maadroid.engine

import com.aliothmoon.maadroid.engine.arknights.ArknightsResourcePreparation

import com.aliothmoon.maadroid.data.config.MaaPathConfig
import com.aliothmoon.maadroid.data.resource.ActivityManager
import com.aliothmoon.maadroid.domain.service.CoreDataPusher
import com.aliothmoon.maadroid.domain.service.MaaResourceLoader
import com.aliothmoon.maadroid.engine.arknights.MaaRunOptions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ArknightsResourcePreparationTest {
    @get:Rule val temp = TemporaryFolder()

    private class Fixture(val root: File, var dirty: Boolean) {
        val calls = mutableListOf<String>()
        val loader = mockk<MaaResourceLoader>()
        val paths = mockk<MaaPathConfig>()
        val pusher = mockk<CoreDataPusher>()
        val activity = mockk<ActivityManager>()
        val preparation = ArknightsResourcePreparation(loader, paths, pusher, activity)

        init {
            every { paths.cacheResourceDir } returns root.path
            coEvery { activity.runIfDirty(any()) } coAnswers {
                calls += "dirty"
                if (dirty) {
                    firstArg<suspend () -> Unit>()()
                    dirty = false
                }
            }
            coEvery { loader.load(any()) } coAnswers {
                calls += "load:${firstArg<String>()}"
                Result.success(Unit)
            }
            coEvery { loader.ensureLoaded(any()) } coAnswers {
                calls += "ensure:${firstArg<String>()}"
                Result.success(Unit)
            }
            coEvery { pusher.pushUserData() } coAnswers {
                calls += "push"
                true
            }
        }
    }

    private fun fixture(dirty: Boolean = true) = Fixture(temp.newFolder(), dirty)
    private fun options(client: String = "YoStarJP", pause: Boolean = true) = MaaRunOptions(client, pause)

    @Test fun dirtyReloadThenEnsureThenPushUsesTheRequestedClient() = runBlocking {
        val f = fixture()
        assertTrue(f.preparation.prepare(f.root, options()).isSuccess)
        assertEquals(listOf("dirty", "load:YoStarJP", "ensure:YoStarJP", "push"), f.calls)
        assertFalse(f.dirty)
    }

    @Test fun cleanResourcesStillEnsureTheRequestedClientBeforePushing() = runBlocking {
        val f = fixture(dirty = false)
        assertTrue(f.preparation.prepare(f.root, options("Bilibili", pause = false)).isSuccess)
        assertEquals(listOf("dirty", "ensure:Bilibili", "push"), f.calls)
        coVerify(exactly = 0) { f.loader.load(any()) }
    }

    @Test fun failedDirtyReloadDoesNotClearDirtyOrContinue() = runBlocking {
        val f = fixture()
        val failure = IOException("reload failed")
        coEvery { f.loader.load("YoStarJP") } returns Result.failure(failure)
        assertSame(failure, f.preparation.prepare(f.root, options()).exceptionOrNull())
        assertTrue(f.dirty)
        coVerify(exactly = 0) { f.loader.ensureLoaded(any()) }
        coVerify(exactly = 0) { f.pusher.pushUserData() }
    }

    @Test fun failedEnsureDoesNotPushUserData() = runBlocking {
        val f = fixture(dirty = false)
        val failure = IOException("ensure failed")
        coEvery { f.loader.ensureLoaded("YoStarJP") } returns Result.failure(failure)
        assertSame(failure, f.preparation.prepare(f.root, options()).exceptionOrNull())
        coVerify(exactly = 0) { f.pusher.pushUserData() }
    }

    @Test fun failedUserDataPushFailsPreparation() = runBlocking {
        val f = fixture()
        coEvery { f.pusher.pushUserData() } returns false
        val result = f.preparation.prepare(f.root, options())
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("用户数据"))
        coVerify(exactly = 1) { f.pusher.pushUserData() }
    }

    @Test fun thrownDependencyFailuresAreReturnedAndStopPreparation() = runBlocking {
        val dirty = fixture()
        val dirtyFailure = IOException("activity failed")
        coEvery { dirty.activity.runIfDirty(any()) } throws dirtyFailure
        assertSame(dirtyFailure, dirty.preparation.prepare(dirty.root, options()).exceptionOrNull())
        coVerify(exactly = 0) { dirty.loader.load(any()) }
        coVerify(exactly = 0) { dirty.loader.ensureLoaded(any()) }
        coVerify(exactly = 0) { dirty.pusher.pushUserData() }

        val pushing = fixture()
        val pushFailure = IOException("Binder failed")
        coEvery { pushing.pusher.pushUserData() } throws pushFailure
        assertSame(pushFailure, pushing.preparation.prepare(pushing.root, options()).exceptionOrNull())
    }

    @Test fun differentOrMissingDirectoryIsRejectedBeforeAnyResourceWork() = runBlocking {
        val f = fixture()
        for (path in listOf(temp.newFolder(), File(temp.root, "missing"), temp.newFile())) {
            assertTrue(f.preparation.prepare(path, options()).isFailure)
        }
        // Even equal path strings do not make a nonexistent directory valid.
        val missing = File(temp.root, "both-missing")
        every { f.paths.cacheResourceDir } returns missing.path
        assertTrue(f.preparation.prepare(missing, options()).isFailure)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun normalizedAndSymlinkPathsToTheSameDirectoryAreAccepted() = runBlocking {
        val f = fixture(dirty = false)
        assertTrue(f.preparation.prepare(File(f.root, "."), options()).isSuccess)
        val link = Files.createSymbolicLink(File(temp.root, "resource-alias").toPath(), f.root.toPath()).toFile()
        assertTrue(f.preparation.prepare(link, options()).isSuccess)
        assertEquals(listOf("dirty", "ensure:YoStarJP", "push", "dirty", "ensure:YoStarJP", "push"), f.calls)
    }

    @Test fun cancellationStoredInLoadResultPropagatesWithoutContinuing() = runBlocking {
        val f = fixture()
        val cancelled = CancellationException("reload cancelled")
        coEvery { f.loader.load("YoStarJP") } returns Result.failure(cancelled)
        try {
            f.preparation.prepare(f.root, options())
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertTrue(f.dirty)
        coVerify(exactly = 0) { f.loader.ensureLoaded(any()) }
        coVerify(exactly = 0) { f.pusher.pushUserData() }
    }

    @Test fun cancellationInEnsureResultOrUserDataPushPropagates() = runBlocking {
        val ensuring = fixture(dirty = false)
        val cancelled = CancellationException("cancelled")
        coEvery { ensuring.loader.ensureLoaded("YoStarJP") } returns Result.failure(cancelled)
        try {
            ensuring.preparation.prepare(ensuring.root, options())
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        coVerify(exactly = 0) { ensuring.pusher.pushUserData() }

        val pushing = fixture()
        coEvery { pushing.pusher.pushUserData() } throws cancelled
        try {
            pushing.preparation.prepare(pushing.root, options())
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test fun cancelledContextDoesNotContinueEvenIfTheLoaderReturnsSuccess() = runBlocking {
        val f = fixture()
        val job = Job()
        coEvery { f.loader.load("YoStarJP") } coAnswers {
            job.cancel()
            Result.success(Unit)
        }
        try {
            withContext(job) { f.preparation.prepare(f.root, options()) }
            fail("Cancelled context must propagate")
        } catch (_: CancellationException) {
            assertTrue(job.isCancelled)
        }
        assertTrue(f.dirty)
        coVerify(exactly = 0) { f.loader.ensureLoaded(any()) }
        coVerify(exactly = 0) { f.pusher.pushUserData() }
    }
}
