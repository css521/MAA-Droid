package com.aliothmoon.maadroid.engine

import android.content.Context
import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.domain.models.RunMode
import com.aliothmoon.maadroid.engine.resource.EngineResourceService
import com.aliothmoon.maadroid.engine.resource.ResourcePackLocks
import com.aliothmoon.maadroid.remote.EngineDataRoot
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercise the real session with the VM's events -> subscribe -> prepare order. */
class EngineSessionLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = mockk<Context>()
    private val resources = mockk<EngineResourceService>()
    private val remote = mockk<RemoteService>()
    private val device = mockk<EngineDeviceSession>(relaxed = true)
    private val pack = mockk<ResourcePackSpec> {
        every { packId } returns "session-lifecycle-pack"
        every { relativeRoot } returns "engines/session-lifecycle"
        every { upstreamArchive } returns null
        every { checkCompatibility(any()) } returns null
        every { verifyInstalledFiles(any()) } returns null
        every { readInstalledVersion(any()) } returns "test"
    }
    private val profile = mockk<GameProfile> {
        every { id } returns ENGINE_ID
        every { resourcePacks } returns listOf(pack)
    }
    private val provider = mockk<EngineProvider> { every { profile } returns this@EngineSessionLifecycleTest.profile }
    private val engines = mutableListOf<AutomationEngine>()
    private val sessions = mutableListOf<EngineSession>()

    @Before fun setUp() {
        every { context.getExternalFilesDir(null) } returns temp.root
        check(EngineDataRoot.forPack(context, pack).mkdirs())
        mockkObject(EngineRegistry, EngineDeviceSession.Companion)
        every { EngineRegistry.provider(ENGINE_ID) } returns provider
        every { EngineRegistry.createEngine(ENGINE_ID) } answers {
            mockk<AutomationEngine>(relaxed = true) {
                every { events } returns MutableSharedFlow<EngineEvent>()
                every { isRunning } returns false
                coEvery { prepare(any()) } returns Result.success(Unit)
                coEvery { start() } returns true
                coEvery { stop() } returns true
                every { appendTask(any(), any()) } returns 1
            }.also { engines += it }
        }
        coEvery {
            EngineDeviceSession.open(profile, remote, RunMode.BACKGROUND, any(), any())
        } returns device
        coEvery { device.connect(any()) } returns Result.success(Unit)
    }

    @After fun tearDown() {
        try {
            engines.forEach { engine ->
                coEvery { engine.stop() } returns true
                every { engine.isRunning } returns false
            }
            runBlocking { sessions.forEach { it.close() } }
        } finally {
            unmockkObject(EngineRegistry, EngineDeviceSession.Companion)
        }
    }

    private fun session() = EngineSession(
        context, ENGINE_ID, resources, RunMode.BACKGROUND,
        serviceProvider = { block -> block(remote) },
    ).also { sessions += it }

    @Test fun subscriberBeforePrepareReceivesEventsFromTheOneOwnedEngine() = runBlocking {
        val session = session()
        assertTrue(engines.isEmpty())
        val events = session.events()!!
        val engine = engines.single()
        repeat(3) { assertSame(events, session.events()) }
        val message = EngineEvent.Log(LogLevel.Info, "prepared")
        val received = async(start = CoroutineStart.UNDISPATCHED) { events.first() }
        coEvery { engine.prepare(any()) } coAnswers {
            (events as MutableSharedFlow<EngineEvent>).emit(message)
            Result.success(Unit)
        }

        assertNull(session.prepare())
        assertEquals(message, withTimeout(5_000) { received.await() })
        assertSame(events, session.events())
        assertEquals(1, session.appendTask("task", "{}"))
        assertTrue(session.start())
        coVerify(exactly = 1) { engine.prepare(any()); device.connect(engine); engine.start() }
        verify(exactly = 1) { EngineRegistry.createEngine(ENGINE_ID) }
        session.close()
        session.close()
        verify(exactly = 1) { engine.release() }
        assertNull(session.events())
    }

    @Test fun differentSessionsAndALaterRunNeverShareAnEngineOrEvents() = runBlocking {
        val first = session()
        val second = session()
        val firstEvents = first.events()
        val secondEvents = second.events()
        assertNotSame(firstEvents, secondEvents)
        assertNotSame(engines[0], engines[1])
        first.close()
        verify(exactly = 0) { engines[1].release() }

        val later = session()
        val laterEvents = later.events()
        assertNotSame(firstEvents, laterEvents)
        first.close()
        assertNull(first.events())
        assertSame(laterEvents, later.events())
        assertEquals(3, engines.size)
        verify(exactly = 0) { engines[2].release() }
    }

    @Test fun resourceFailureReleasesAnEngineCreatedForEarlySubscription() = runBlocking {
        val session = session()
        session.events()
        val engine = engines.single()
        every { pack.verifyInstalledFiles(any()) } returns "broken resource"

        assertTrue(session.prepare()!!.contains("broken resource"))
        coVerify(exactly = 0) { engine.prepare(any()) }
        verify(exactly = 1) { engine.release() }
        assertNull(session.events())
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        checkNotNull(ResourcePackLocks.tryAcquire(pack.packId)).close()
    }

    @Test fun admissionFailureReleasesItsEarlyInstanceWithoutReleasingAnotherRun() = runBlocking {
        val reservation = checkNotNull(EngineExecutionCoordinator.shared.tryStart("another-run"))
        try {
            val session = session()
            session.events()
            assertTrue(session.prepare()!!.contains("其它任务"))
            verify(exactly = 1) { engines.single().release() }
            assertEquals("another-run", EngineExecutionCoordinator.shared.activeEngineId.value)
            assertNull(session.events())
        } finally {
            reservation.close()
        }
    }

    @Test fun missingProviderAlsoReleasesTheAlreadyCreatedInstance() = runBlocking {
        val session = session()
        session.events()
        every { EngineRegistry.provider(ENGINE_ID) } returns null
        assertTrue(session.prepare()!!.contains("未注册"))
        verify(exactly = 1) { engines.single().release() }
        assertNull(session.events())
    }

    @Test fun cancelledPreparationReleasesTheSubscribedEngineAndItsResourceLease() = runBlocking {
        val session = session()
        session.events()
        val engine = engines.single()
        val entered = CompletableDeferred<Unit>()
        coEvery { engine.prepare(any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch { session.prepare() }
        withTimeout(5_000) { entered.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        verify(exactly = 1) { engine.release() }
        assertNull(session.events())
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        checkNotNull(ResourcePackLocks.tryAcquire(pack.packId)).close()
    }

    @Test fun enginePreparationFailureReleasesOnceAndAClosedSessionCannotRestart() = runBlocking {
        val session = session()
        session.events()
        val engine = engines.single()
        coEvery { engine.prepare(any()) } returns Result.failure(IllegalStateException("prepare failed"))
        assertTrue(session.prepare()!!.contains("prepare failed"))
        session.close()
        assertNull(session.events())
        assertTrue(runCatching { session.prepare() }.isFailure)
        verify(exactly = 1) { EngineRegistry.createEngine(ENGINE_ID); engine.release() }
    }

    @Test fun closingAnUnusedSessionDoesNotCreateAnEngine() = runBlocking {
        val session = session()
        session.close()
        assertNull(session.events())
        assertTrue(runCatching { session.prepare() }.isFailure)
        verify(exactly = 0) { EngineRegistry.createEngine(ENGINE_ID) }
    }

    @Test fun prepareWithoutAnEarlySubscriberStillUsesOneInstance() = runBlocking {
        val session = session()
        assertNull(session.prepare())
        val engine = engines.single()
        assertSame(engine.events, session.events())
        assertSame(engine.events, session.events())
        verify(exactly = 1) { EngineRegistry.createEngine(ENGINE_ID) }
        // Duplicate prepare must not create/release a replacement for the active run.
        assertTrue(runCatching { session.prepare() }.isFailure)
        verify(exactly = 0) { engine.release() }
    }

    private companion object {
        const val ENGINE_ID = "session-lifecycle-test"
    }
}
