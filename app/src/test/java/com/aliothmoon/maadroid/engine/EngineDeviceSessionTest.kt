package com.aliothmoon.maadroid.engine

import android.os.IBinder
import com.aliothmoon.maadroid.IEngineDeviceSession
import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.domain.models.RunMode
import com.aliothmoon.maadroid.remote.RemoteDeviceHandle
import com.aliothmoon.maadroid.remote.AppAliveStatus
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class EngineDeviceSessionTest {
    private class Harness {
        val service = mockk<RemoteService>(relaxed = true)
        val lease = mockk<IEngineDeviceSession>(relaxed = true)
        val owner = mockk<IBinder>()
        val handle = mockk<RemoteDeviceHandle>(relaxed = true)
        val profile = mockk<GameProfile>()
        val frames = mockk<FrameSource>()
        val control = mockk<DeviceControl>()
        val input = mockk<InputSink>(relaxed = true)
        init {
            every { profile.id } returns "test"
            every { profile.display } returns DisplaySpec(3, 2, 320)
            every { profile.gamePackages } returns listOf("missing", "installed")
            every { service.isPackageInstalled("installed") } returns true
            every { service.openDeviceSession(owner, any(), 3, 2, 320) } returns lease
            every { lease.displayId } returns 7
            every { handle.frames } returns frames
            every { handle.control } returns control
            every { handle.input } returns input
            every { control.startApp("installed") } returns true
            coEvery { frames.grab() } returns Frame(3, 2, 9, 1, ByteBuffer.allocateDirect(18))
        }
        suspend fun open(timeout: Long = 1_000, mode: RunMode = RunMode.BACKGROUND) =
            EngineDeviceSession.open(profile, service, mode, timeout, 1, { owner }, { _, _, _, _ -> handle })
    }

    @Test fun startsInstalledPackageAfterCaptureAndPermissionGrant() = runBlocking {
        val h = Harness()
        val session = h.open()
        assertEquals("installed", session.packageName)
        assertEquals(7, session.displayId)
        verifyOrder {
            h.service.setupDevice()
            h.service.isPackageInstalled("missing")
            h.service.isPackageInstalled("installed")
            h.service.openDeviceSession(h.owner, any(), 3, 2, 320)
            h.service.grantPermissions(any())
            h.control.startApp("installed")
        }
        coVerify(exactly = 1) { h.frames.grab() }
        session.close()
        session.close()
        verify(exactly = 1) { h.handle.close() }
    }

    @Test fun timeoutAndWrongDimensionsCloseOwnedHandle() = runBlocking {
        for (wrongSize in listOf(false, true)) {
            val h = Harness()
            coEvery { h.frames.grab() } returns if (wrongSize) Frame(2, 3, 6, 1, ByteBuffer.allocate(18)) else null
            val failure = runCatching { h.open(timeout = 30) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains(if (wrongSize) "尺寸" else "超时"))
            verify(exactly = 1) { h.handle.close() }
        }
    }

    @Test fun cancellationDuringFirstFrameClosesLeaseAndPropagates() = runBlocking {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        coEvery { h.frames.grab() } coAnswers { entered.complete(Unit); awaitCancellation() }
        val job = launch { h.open() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        verify(exactly = 1) { h.handle.close() }
    }

    @Test fun failedConnectionStopsBeforeClosingAndPreservesTheFailure() = runBlocking {
        val h = Harness()
        val engine = mockk<AutomationEngine>()
        val failure = IllegalStateException("connect failed")
        coEvery { engine.connect(h.handle) } returns Result.failure(failure)
        coEvery { engine.stop() } returns true
        assertSame(failure, h.open().connect(engine).exceptionOrNull())
        coVerifyOrder { engine.connect(h.handle); engine.stop(); h.handle.close() }
        verify(exactly = 1) { h.handle.close() }
    }

    @Test fun failedConnectionKeepsDeviceWhenStopIsUnconfirmedAndAllowsRetry() = runBlocking {
        for (throwOnStop in listOf(false, true)) {
            val h = Harness()
            val session = h.open()
            val engine = mockk<AutomationEngine>()
            val failure = IllegalStateException("connect failed")
            val stopFailure = IllegalStateException("stop failed")
            every { engine.isRunning } returns false
            coEvery { engine.connect(h.handle) } returns Result.failure(failure)
            coEvery { engine.stop() } coAnswers { if (throwOnStop) throw stopFailure else false }

            assertSame(failure, session.connect(engine).exceptionOrNull())
            if (throwOnStop) assertTrue(failure.suppressed.any { it === stopFailure || it.cause === stopFailure })
            coVerify(exactly = 1) { engine.stop() }
            verify(exactly = 0) { h.handle.close() }

            coEvery { engine.stop() } returns true
            assertTrue(session.stop(engine))
            verify(exactly = 1) { h.handle.close() }
        }
    }

    @Test fun cancelledConnectionWaitsForStopWithoutReplacingTheCancellation() = runBlocking {
        // The caller is already cancelled while stop suspends. Only a confirmed stop may close.
        for (stopResult in listOf(true, false, null)) {
            val h = Harness()
            val session = h.open()
            val engine = mockk<AutomationEngine>()
            val entered = CompletableDeferred<Unit>()
            val stopping = CompletableDeferred<Unit>()
            val finishStop = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable>()
            val cancellation = CancellationException("cancel connect")
            val stopFailure = IllegalStateException("stop failed")
            coEvery { engine.connect(h.handle) } coAnswers {
                entered.complete(Unit)
                awaitCancellation()
            }
            coEvery { engine.stop() } coAnswers {
                check(currentCoroutineContext().isActive)
                stopping.complete(Unit)
                finishStop.await()
                stopResult ?: throw stopFailure
            }
            val job = launch {
                try { session.connect(engine) } catch (failure: Throwable) {
                    observed.complete(failure)
                    throw failure
                }
            }
            try {
                withTimeout(5_000) { entered.await() }
                job.cancel(cancellation)
                withTimeout(5_000) { stopping.await() }
                assertFalse(job.isCompleted)
                verify(exactly = 0) { h.handle.close() }
                finishStop.complete(Unit)
                withTimeout(5_000) { job.join() }
                val reported = withTimeout(5_000) { observed.await() }
                // Coroutine stack recovery may copy the exception and retain the original as cause.
                assertTrue(reported === cancellation || reported.cause === cancellation)
                if (stopResult == null) assertTrue(
                    generateSequence(reported) { it.cause }.flatMap { it.suppressed.asSequence() }
                        .any { it === stopFailure || it.cause === stopFailure },
                )
                verify(exactly = if (stopResult == true) 1 else 0) { h.handle.close() }
            } finally {
                finishStop.complete(Unit)
                job.cancelAndJoin()
                coEvery { engine.stop() } returns true
                session.stop(engine)
            }
        }
    }

    @Test fun terminalStopRetainsDeviceOnFalseOrExceptionUntilSuccessfulRetry() = runBlocking {
        val h = Harness()
        val session = h.open()
        val engine = mockk<AutomationEngine>()
        every { engine.isRunning } returns false
        coEvery { engine.stop() } returns false
        assertFalse(session.stop(engine))
        verify(exactly = 0) { h.handle.close() }

        val failure = IllegalStateException("stop failed")
        coEvery { engine.stop() } throws failure
        val reported = runCatching { session.stop(engine) }.exceptionOrNull()
        assertTrue(reported === failure || reported?.cause === failure)
        verify(exactly = 0) { h.handle.close() }

        coEvery { engine.stop() } returns true
        assertTrue(session.stop(engine))
        verify(exactly = 1) { h.handle.close() }
    }

    @Test fun closedDeviceDoesNotConnectOrStopASuppliedEngine() = runBlocking {
        val session = Harness().open()
        session.close()
        val engine = mockk<AutomationEngine>()
        assertTrue(session.connect(engine).isFailure)
        verify { engine wasNot Called }
    }

    @Test fun foregroundAndMissingGameFailBeforeDisplayCreation() = runBlocking {
        val h = Harness()
        assertTrue(runCatching { h.open(mode = RunMode.FOREGROUND) }.isFailure)
        verify(exactly = 0) { h.service.setupDevice() }
        every { h.service.isPackageInstalled(any()) } returns false
        assertTrue(runCatching { h.open() }.isFailure)
        verify(exactly = 0) { h.service.openDeviceSession(any(), any(), any(), any(), any()) }
    }

    @Test fun previewDetachDoesNotReleaseDisplayAndClosedSessionIgnoresNewSurface() = runBlocking {
        val h = Harness()
        val surface = mockk<android.view.Surface>()
        val session = h.open()
        session.setPreviewSurface(surface)
        session.setPreviewSurface(null)
        verifyOrder { h.handle.setPreviewSurface(surface); h.handle.setPreviewSurface(null) }
        verify(exactly = 0) { h.handle.close() }
        session.close()
        session.setPreviewSurface(surface)
        verify(exactly = 1) { h.handle.setPreviewSurface(surface) }
    }

    @Test fun manualTouchesUseReservedSlotsAndDisposeReleasesOnlyTheirLastPositions() = runBlocking {
        val h = Harness()
        val session = h.open()
        val manual = session.openManualInput()!!
        listOf(-1, 0, 7, 16).forEach { manual.touchDown(1, 1, it) }
        verify(exactly = 0) { h.input.touchDown(any(), any(), any()) }

        // The pipeline owns contact 0, independently of the preview.
        h.input.touchDown(0, 0, 0)
        manual.touchDown(1, 1, 8)
        manual.touchDown(2, 1, 15)
        manual.touchMove(2, 0, 8)
        manual.touchUp(2, 1, 15)
        session.setPreviewSurface(null)
        manual.close()
        manual.close()

        verifyOrder {
            h.input.touchDown(1, 1, 8)
            h.input.touchDown(2, 1, 15)
            h.input.touchMove(2, 0, 8)
            h.input.touchUp(2, 1, 15)
            h.input.touchUp(2, 0, 8)
        }
        verify(exactly = 1) { h.input.touchUp(any(), any(), 8) }
        verify(exactly = 1) { h.input.touchUp(any(), any(), 15) }
        verify(exactly = 0) { h.input.touchUp(any(), any(), 0) }
        verify(exactly = 0) { h.input.touchCancel() }
        verify(exactly = 0) { h.handle.close() }
        session.close()
    }

    @Test fun replacedPreviewCannotMoveOrReleaseTheNewPreviewsContacts() = runBlocking {
        val h = Harness()
        val session = h.open()
        val old = session.openManualInput()!!
        old.touchDown(1, 1, 8)
        val next = session.openManualInput()!!
        verify(exactly = 1) { h.input.touchUp(1, 1, 8) }
        clearMocks(h.input, answers = false)
        next.touchDown(2, 1, 8)
        old.touchDown(0, 0, 9)
        old.touchMove(0, 0, 8)
        old.touchUp(0, 0, 8)
        old.close()
        verify(exactly = 1) { h.input.touchDown(any(), any(), any()) }
        verify(exactly = 0) { h.input.touchMove(any(), any(), any()) }
        verify(exactly = 0) { h.input.touchUp(any(), any(), any()) }
        next.close()
        verify { h.input.touchUp(2, 1, 8) }
        session.close()
    }

    @Test fun closedSessionCannotInjectIntoAReplacementSession() = runBlocking {
        val oldHarness = Harness()
        val oldSession = oldHarness.open()
        val old = oldSession.openManualInput()!!
        old.touchDown(1, 1, 8)
        oldSession.close()
        assertNull(oldSession.openManualInput())
        clearMocks(oldHarness.input, answers = false)

        val nextHarness = Harness()
        val nextSession = nextHarness.open()
        val next = nextSession.openManualInput()!!
        next.touchDown(2, 1, 8)
        old.touchDown(0, 0, 8)
        old.touchMove(0, 0, 8)
        old.touchUp(0, 0, 8)
        old.close()
        verify { oldHarness.input wasNot Called }
        verify(exactly = 0) { nextHarness.input.touchUp(any(), any(), any()) }
        verify(exactly = 0) { nextHarness.input.touchCancel() }
        nextSession.close()
    }

    @Test fun failedManualInputReleasesOtherManualContactsWithoutCancellingAutomation() = runBlocking {
        val h = Harness()
        val session = h.open()
        val manual = session.openManualInput()!!
        manual.touchDown(1, 1, 8)
        every { h.input.touchDown(2, 1, 9) } throws IllegalStateException("expired lease")
        manual.touchDown(2, 1, 9)
        manual.touchDown(1, 0, 10)
        verify { h.input.touchUp(1, 1, 8); h.input.touchUp(2, 1, 9) }
        verify(exactly = 0) { h.input.touchDown(any(), any(), 10) }
        verify(exactly = 0) { h.input.touchCancel() }
        session.close()
    }

    @Test fun failedManualReleaseStillClosesTheOwnedDevice() = runBlocking {
        val h = Harness()
        val session = h.open()
        val manual = session.openManualInput()!!
        manual.touchDown(1, 1, 8)
        manual.touchDown(2, 1, 9)
        every { h.input.touchUp(any(), any(), 8) } throws IllegalStateException("dead remote")
        session.close()
        verify { h.input.touchUp(2, 1, 9) }
        verify(exactly = 1) { h.handle.close() }
        verify(exactly = 0) { h.input.touchCancel() }
    }

    @Test fun reuseRequiresMatchingProfileModeAndLiveRemoteHandle() = runBlocking {
        val h = Harness()
        val session = h.open()
        every { h.handle.isActiveOn(h.service, DisplaySpec(3, 2, 320)) } returns true
        assertTrue(session.canReuse(h.profile, h.service, RunMode.BACKGROUND))
        assertFalse(session.canReuse(h.profile, h.service, RunMode.FOREGROUND))
        every { h.handle.isActiveOn(h.service, DisplaySpec(3, 2, 320)) } returns false
        assertFalse(session.canReuse(h.profile, h.service, RunMode.BACKGROUND))
        every { h.handle.isActiveOn(h.service, DisplaySpec(3, 2, 320)) } returns true
        every { h.profile.gamePackages } returns listOf("other.game")
        assertFalse(session.canReuse(h.profile, h.service, RunMode.BACKGROUND))
        session.close()
        assertFalse(session.canReuse(h.profile, h.service, RunMode.BACKGROUND))
    }

    @Test fun resumingDoesNotSendLaunchIntentUnlessGameIsConfirmedDead() = runBlocking {
        val h = Harness()
        val session = h.open()
        clearMocks(h.control, answers = false)
        for (status in listOf(AppAliveStatus.ALIVE, AppAliveStatus.UNKNOWN)) {
            every { h.service.isAppAlive("installed") } returns status
            session.resumeGame(h.service)
        }
        verify { h.control wasNot Called }
        every { h.service.isAppAlive("installed") } returns AppAliveStatus.DEAD
        session.resumeGame(h.service)
        verify(exactly = 1) { h.control.startApp("installed") }
        verify(exactly = 0) { h.control.stopApp(any()) }
        session.close()
        assertTrue(runCatching { session.resumeGame(h.service) }.isFailure)
    }

    @Test fun gameCloseAndFpsUseTheOwnedHandleAndCannotActAfterDisposal() = runBlocking {
        val h = Harness()
        val session = h.open()
        every { h.handle.readGameFps() } returns 59.25f
        every { h.control.stopApp("installed") } just Runs
        assertEquals(59.25f, session.readGameFps())
        session.stopGame()
        verify(exactly = 1) { h.control.stopApp("installed") }
        verify(exactly = 0) { h.service.forceStopApp(any()); h.service.gameFps }
        session.close()
        clearMocks(h.control, h.handle, answers = false)
        session.stopGame()
        assertNull(session.readGameFps())
        verify { h.control wasNot Called; h.handle wasNot Called }
    }
}
