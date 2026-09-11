package com.maadroid.app.remote

import android.os.IBinder
import com.maadroid.app.IEngineDeviceSession
import com.maadroid.app.RemoteService
import com.maadroid.app.engine.DisplaySpec
import com.maadroid.app.engine.RemoteEngineDevice
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteDeviceHandleTest {
    private val service = mockk<RemoteService>()
    private val session = mockk<IEngineDeviceSession>()

    @Test
    fun metadataUsesExactSpecAndSessionDisplayWithoutLoadingAnEngine() {
        every { session.displayId } returns 37
        val device: RemoteEngineDevice = RemoteDeviceHandle(
            service, 1920, 1080, session, dpi = 240, legacyDisplayId = 99,
        )

        assertEquals(DisplaySpec(1920, 1080, 240), device.displaySpec)
        assertEquals(37, device.displayId)

        verify(exactly = 1) { session.displayId }
        confirmVerified(session)
        verify { service wasNot Called }
    }

    @Test
    fun existingSessionConstructorKeepsItsPositionAndDefaultDpi() {
        every { session.displayId } returns 7
        val device = RemoteDeviceHandle(service, 1280, 720, session)

        assertEquals(DisplaySpec(1280, 720, 160), device.displaySpec)
        assertEquals(7, device.displayId)
        verify { service wasNot Called }
    }

    @Test
    fun legacyConstructorDoesNotGuessADisplayId() {
        val device = RemoteDeviceHandle(service, 1280, 720)

        assertEquals(DisplaySpec(1280, 720, 160), device.displaySpec)
        assertThrows(IllegalStateException::class.java) { device.displayId }
        verify { service wasNot Called }
    }

    @Test
    fun legacyMetadataAcceptsExplicitDisplayIdsIncludingZero() {
        for (id in listOf(0, 53)) {
            val device = RemoteDeviceHandle(service, 1280, 720, dpi = 320, legacyDisplayId = id)

            assertEquals(id, device.displayId)
            assertEquals(DisplaySpec(1280, 720, 320), device.displaySpec)
        }
        verify { service wasNot Called }
    }

    @Test
    fun activeSessionDelegatesBinderAndMissingServiceAfterValidatingItsSpec() {
        val binder = mockk<IBinder>()
        every { session.matchesDisplaySpec(1920, 1080, 240) } returns true
        every { service.getEngineService("test-engine") } returns binder
        every { service.getEngineService("missing") } returns null
        val device = RemoteDeviceHandle(service, 1920, 1080, session, dpi = 240)

        assertSame(binder, device.engineService("test-engine"))
        assertNull(device.engineService("missing"))

        verifySequence {
            session.matchesDisplaySpec(1920, 1080, 240)
            service.getEngineService("test-engine")
            session.matchesDisplaySpec(1920, 1080, 240)
            session.matchesDisplaySpec(1920, 1080, 240)
            service.getEngineService("missing")
            session.matchesDisplaySpec(1920, 1080, 240)
        }
    }

    @Test
    fun legacyLookupDelegatesWithoutRequiringSessionMetadata() {
        val binder = mockk<IBinder>()
        every { service.getEngineService("test-engine") } returns binder
        val device = RemoteDeviceHandle(service, 1280, 720)

        assertSame(binder, device.engineService("test-engine"))
        verify(exactly = 1) { service.getEngineService("test-engine") }
        confirmVerified(service)
    }

    @Test
    fun mismatchedSessionRejectsLookupBeforeRequestingABinder() {
        every { session.matchesDisplaySpec(1280, 720, 320) } returns false
        val device = RemoteDeviceHandle(service, 1280, 720, session, dpi = 320)

        assertThrows(IllegalStateException::class.java) { device.engineService("test-engine") }
        verify { service wasNot Called }
    }

    @Test
    fun expiredSessionRejectsLookupBeforeRequestingABinder() {
        val expired = IllegalStateException("expired lease")
        every { session.matchesDisplaySpec(1280, 720, 160) } throws expired
        val device = RemoteDeviceHandle(service, 1280, 720, session)

        assertSame(expired, assertThrows(IllegalStateException::class.java) {
            device.engineService("test-engine")
        })
        verify { service wasNot Called }
    }

    @Test
    fun sessionExpiringDuringLookupDoesNotReturnTheAcquiredBinder() {
        var active = true
        val expired = IllegalStateException("expired lease")
        every { session.matchesDisplaySpec(1280, 720, 160) } answers {
            if (!active) throw expired
            true
        }
        every { service.getEngineService("test-engine") } answers {
            active = false
            mockk<IBinder>()
        }
        val device = RemoteDeviceHandle(service, 1280, 720, session)

        assertSame(expired, assertThrows(IllegalStateException::class.java) {
            device.engineService("test-engine")
        })
        verify(exactly = 1) { service.getEngineService("test-engine") }
    }

    @Test
    fun closedLegacyHandleCannotRequestABinderOrReadALiveDisplayId() {
        val device = RemoteDeviceHandle(service, 1280, 720, legacyDisplayId = 53)

        device.close()
        device.close()

        assertThrows(IllegalStateException::class.java) { device.engineService("test-engine") }
        assertThrows(IllegalStateException::class.java) { device.displayId }
        assertEquals(DisplaySpec(1280, 720, 160), device.displaySpec)
        verify { service wasNot Called }
    }

    @Test
    fun failedSessionCleanupStillClosesTheHandleAndIsNotRetried() {
        val failure = IllegalStateException("remote close failed")
        every { session.close() } throws failure
        val device = RemoteDeviceHandle(service, 1280, 720, session)

        assertSame(failure, assertThrows(IllegalStateException::class.java) { device.close() })
        device.close()

        assertThrows(IllegalStateException::class.java) { device.engineService("test-engine") }
        verify(exactly = 1) { session.close() }
        confirmVerified(session)
        verify { service wasNot Called }
    }

    @Test
    fun lookupDuringCloseCannotAcquireABinderAfterCleanup() {
        val closing = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val lookupStarted = CountDownLatch(1)
        every { session.close() } answers {
            closing.countDown()
            check(finishClose.await(2, TimeUnit.SECONDS))
        }
        val device = RemoteDeviceHandle(service, 1280, 720, session)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val close = executor.submit { device.close() }
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            val lookup = executor.submit<Throwable?> {
                lookupStarted.countDown()
                runCatching { device.engineService("test-engine") }.exceptionOrNull()
            }
            assertTrue(lookupStarted.await(2, TimeUnit.SECONDS))
            finishClose.countDown()

            close.get(2, TimeUnit.SECONDS)
            assertTrue(lookup.get(2, TimeUnit.SECONDS) is IllegalStateException)
            device.close()
            verify(exactly = 1) { session.close() }
            confirmVerified(session)
            verify { service wasNot Called }
        } finally {
            finishClose.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun reuseRequiresSameRemoteBinderAndExactDisplaySpec() {
        val binder = mockk<IBinder>()
        val reconnected = mockk<RemoteService>()
        every { service.asBinder() } returns binder
        every { reconnected.asBinder() } returns mockk<IBinder>()
        every { session.matchesDisplaySpec(1280, 720, 320) } returns true
        every { session.close() } returns Unit
        val device = RemoteDeviceHandle(service, 1280, 720, session, dpi = 320)
        val spec = DisplaySpec(1280, 720, 320)
        assertTrue(device.isActiveOn(service, spec))
        org.junit.Assert.assertFalse(device.isActiveOn(reconnected, spec))
        org.junit.Assert.assertFalse(device.isActiveOn(service, spec.copy(dpi = 160)))
        every { session.matchesDisplaySpec(1280, 720, 320) } returns false
        org.junit.Assert.assertFalse(device.isActiveOn(service, spec))
        device.close()
        org.junit.Assert.assertFalse(device.isActiveOn(service, spec))
    }

    @Test
    fun fpsUsesLeaseInsteadOfGlobalServiceAndFiltersUnavailableSamples() {
        val device = RemoteDeviceHandle(service, 1280, 720, session)
        every { session.gameFps } returnsMany listOf(58.5f, -1f, Float.NaN, Float.POSITIVE_INFINITY)
        every { session.close() } returns Unit
        assertEquals(58.5f, device.readGameFps())
        repeat(3) { assertNull(device.readGameFps()) }
        device.close()
        assertNull(device.readGameFps())
        verify(exactly = 4) { session.gameFps }
        verify { service wasNot Called }
    }
}
