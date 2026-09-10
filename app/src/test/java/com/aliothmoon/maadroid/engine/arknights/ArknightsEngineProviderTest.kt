package com.aliothmoon.maadroid.engine.arknights

import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.data.preferences.TaskChainState
import com.aliothmoon.maadroid.engine.EngineRegistry
import com.aliothmoon.maadroid.engine.EngineSetup
import com.aliothmoon.maadroid.remote.EngineIds
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.io.IOException

class ArknightsEngineProviderTest {
    private fun resourcePaths(directory: File) = ArknightsEngine.resourcePaths(directory)

    @Test fun setupCanRegisterAndCreateIndependentEnginesBeforeKoinStarts() {
        assertNull(GlobalContext.getOrNull())
        EngineSetup.install()
        val provider = checkNotNull(EngineRegistry.provider(EngineIds.ARKNIGHTS))
        val first = provider.createEngine()
        val second = provider.createEngine()
        try {
            assertTrue(first is ArknightsEngine)
            assertNotSame(first, second)
            assertNotSame(first.events, second.events)
            assertSame(ArknightsProfile, provider.profile)
            assertSame(provider.profile, first.profile)
            assertFalse(first.isRunning)
            assertFalse(second.isRunning)
            assertTrue(provider.ui.taskPanels.isEmpty())
            assertNull(GlobalContext.getOrNull())
        } finally {
            first.release()
            second.release()
        }
    }

    @Test fun creatingInspectingAndReleasingAnUnpreparedEngineNeverResolvesDependencies() {
        val provider = ArknightsEngineProvider { error("Koin must not be accessed before prepare") }
        val first = provider.createEngine()
        val second = provider.createEngine()
        assertNotSame(first, second)
        assertNotSame(first.events, second.events)
        assertFalse(first.isRunning)
        first.release()
        assertFalse(second.isRunning)
        second.release()
    }

    @Test fun prepareFreezesCurrentPreferencesWhileALaterInstanceGetsANewSnapshot() = runBlocking {
        var client = "Official"
        val pause = MutableStateFlow(false)
        val chain = mockk<TaskChainState> { every { clientType } answers { client } }
        val settings = mockk<AppSettingsManager> { every { deployWithPause } returns pause }
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val received = mutableListOf<Pair<File, MaaRunOptions>>()
        val preparation = MaaResourcePreparation { directory, options ->
            received += directory to options
            if (received.size == 1) {
                entered.complete(Unit)
                finish.await()
            }
            Result.success(Unit)
        }
        val dependencies = koinApplication {
            modules(module {
                single { chain }
                single { settings }
                single<MaaResourcePreparation> { preparation }
            })
        }
        try {
            val provider = ArknightsEngineProvider { dependencies.koin }
            val first = provider.createEngine()
            val later = provider.createEngine()
            try {
                verify(exactly = 0) { chain.clientType; settings.deployWithPause }
                client = "YoStarJP"
                pause.value = true
                val firstDir = File("first-resources")
                val pending = async(start = CoroutineStart.UNDISPATCHED) { first.prepare(resourcePaths(firstDir)) }
                entered.await()
                client = "Bilibili"
                pause.value = false
                finish.complete(Unit)
                assertTrue(pending.await().isSuccess)
                assertEquals(firstDir to MaaRunOptions("YoStarJP", true), received.single())
                verify(exactly = 1) { chain.clientType; settings.deployWithPause }

                val laterDir = File("later-resources")
                assertTrue(later.prepare(resourcePaths(laterDir)).isSuccess)
                assertEquals(laterDir to MaaRunOptions("Bilibili", false), received.last())
                verify(exactly = 2) { chain.clientType; settings.deployWithPause }
                assertFalse(first.isRunning)
                assertFalse(later.isRunning)
            } finally {
                finish.complete(Unit)
                first.release()
                later.release()
            }
        } finally {
            dependencies.close()
        }
    }

    @Test fun prepareReturnsTheInjectedAdapterFailureWithoutNeedingAnyRemoteService() = runBlocking {
        val failure = IOException("resources unavailable")
        val chain = mockk<TaskChainState> { every { clientType } returns "YoStarEN" }
        val settings = mockk<AppSettingsManager> { every { deployWithPause } returns MutableStateFlow(false) }
        val dependencies = koinApplication {
            modules(module {
                single { chain }
                single { settings }
                single<MaaResourcePreparation> { MaaResourcePreparation { _, _ -> Result.failure(failure) } }
            })
        }
        try {
            val engine = ArknightsEngineProvider { dependencies.koin }.createEngine()
            try {
                assertSame(failure, engine.prepare(resourcePaths(File("unavailable-resources"))).exceptionOrNull())
                assertFalse(engine.isRunning)
            } finally {
                engine.release()
            }
        } finally {
            dependencies.close()
        }
    }
}
