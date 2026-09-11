package com.maadroid.app.engine.arknights

import android.os.IBinder
import com.maadroid.app.RemoteService
import com.maadroid.app.remote.EngineIds
import com.maadroid.app.remote.RemoteDeviceHandle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class ArknightsDeviceAdapterTest {
    private val remote = mockk<RemoteService>(relaxed = true)

    @Test fun engineLookupIsLazyAndMetadataResolvesOneForegroundHandle() {
        val binder = mockk<IBinder>()
        every { remote.getEngineService(EngineIds.ARKNIGHTS) } returns binder
        var opens = 0
        val adapter = ArknightsDeviceAdapter(remote) {
            opens++
            RemoteDeviceHandle(remote, 1920, 1080, dpi = 240, legacyDisplayId = 0)
        }
        assertSame(binder, adapter.engineService(EngineIds.ARKNIGHTS))
        assertEquals(0, opens)
        assertEquals(1920, adapter.displaySpec.width)
        assertEquals(1080, adapter.displaySpec.height)
        assertEquals(240, adapter.displaySpec.dpi)
        assertEquals(0, adapter.displayId)
        assertEquals(1, opens)
        adapter.close()
        adapter.close()
        verify(exactly = 0) { remote.stopVirtualDisplay(); remote.closeFrameChannel() }
        assertThrows(IllegalStateException::class.java) { adapter.displayId }
    }

    @Test fun closeBeforeFirstReadNeverPreparesTheDisplayOrLooksUpEngine() {
        val adapter = ArknightsDeviceAdapter(remote) { error("must remain lazy") }
        adapter.close()
        assertThrows(IllegalStateException::class.java) { adapter.displaySpec }
        assertThrows(IllegalStateException::class.java) { adapter.engineService(EngineIds.ARKNIGHTS) }
        verify(exactly = 0) { remote.getEngineService(any()); remote.stopVirtualDisplay() }
    }

    @Test fun reentrantCloseDuringDisplayResolutionClosesReturnedHandle() {
        val handle = mockk<RemoteDeviceHandle>(relaxed = true)
        lateinit var adapter: ArknightsDeviceAdapter
        adapter = ArknightsDeviceAdapter(remote) { adapter.close(); handle }
        assertThrows(IllegalStateException::class.java) { adapter.displaySpec }
        adapter.close()
        verify(exactly = 1) { handle.close() }
        verify(exactly = 0) { handle.displaySpec }
    }

    @Test fun reentrantCloseDuringEngineLookupDoesNotReturnStaleBinder() {
        val adapter = ArknightsDeviceAdapter(remote) { error("must remain lazy") }
        every { remote.getEngineService(EngineIds.ARKNIGHTS) } answers { adapter.close(); mockk<IBinder>() }
        assertThrows(IllegalStateException::class.java) { adapter.engineService(EngineIds.ARKNIGHTS) }
    }

    @Test fun throwingHandleCloseCannotLeaveAnAccessibleOrRepeatedlyClosedHandle() {
        val handle = mockk<RemoteDeviceHandle>(relaxed = true)
        every { handle.displayId } returns 7
        every { handle.close() } throws IllegalStateException("close failed")
        val adapter = ArknightsDeviceAdapter(remote) { handle }
        assertEquals(7, adapter.displayId)
        assertThrows(IllegalStateException::class.java) { adapter.close() }
        adapter.close()
        assertThrows(IllegalStateException::class.java) { adapter.displayId }
        verify(exactly = 1) { handle.close() }
    }
}
