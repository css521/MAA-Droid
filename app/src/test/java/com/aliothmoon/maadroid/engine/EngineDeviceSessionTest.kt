package com.aliothmoon.maadroid.engine

import android.os.IBinder
import com.aliothmoon.maadroid.IEngineDeviceSession
import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.domain.models.RunMode
import com.aliothmoon.maadroid.remote.RemoteDeviceHandle
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
        init {
            every { profile.display } returns DisplaySpec(3, 2, 320)
            every { profile.gamePackages } returns listOf("missing", "installed")
            every { service.isPackageInstalled("installed") } returns true
            every { service.openDeviceSession(owner, any(), 3, 2, 320) } returns lease
            every { lease.displayId } returns 7
            every { handle.frames } returns frames
            every { handle.control } returns control
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

    @Test fun connectFailureAndTerminalStopCloseDevice() = runBlocking {
        val h = Harness()
        val engine = mockk<AutomationEngine>()
        coEvery { engine.connect(h.handle) } returns Result.failure(IllegalStateException("connect failed"))
        assertTrue(h.open().connect(engine).isFailure)
        verify(exactly = 1) { h.handle.close() }
        clearMocks(h.handle, answers = false)
        coEvery { engine.stop() } returns false
        assertFalse(h.open().stop(engine))
        coVerifyOrder { engine.stop(); h.handle.close() }
    }

    @Test fun foregroundAndMissingGameFailBeforeDisplayCreation() = runBlocking {
        val h = Harness()
        assertTrue(runCatching { h.open(mode = RunMode.FOREGROUND) }.isFailure)
        verify(exactly = 0) { h.service.setupDevice() }
        every { h.service.isPackageInstalled(any()) } returns false
        assertTrue(runCatching { h.open() }.isFailure)
        verify(exactly = 0) { h.service.openDeviceSession(any(), any(), any(), any(), any()) }
    }
}
