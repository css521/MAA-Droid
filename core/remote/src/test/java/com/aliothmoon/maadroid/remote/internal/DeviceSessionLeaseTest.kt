package com.aliothmoon.maadroid.remote.internal

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeviceSessionLeaseTest {
    @Test fun neverAdoptsExistingDisplayOrChannel() {
        val lease = DeviceSessionLease()
        var started = false
        assertThrows(IllegalStateException::class.java) {
            lease.acquire(Any(), { true }) { started = true }
        }
        assertFalse(started)
        lease.legacy { started = true }
        assertTrue(started)
    }

    @Test fun oldCloseAndInputCannotAffectNewOwner() {
        val lease = DeviceSessionLease()
        val old = Any()
        val next = Any()
        lease.acquire(old, { false }) {}
        assertThrows(IllegalStateException::class.java) { lease.legacy {} }
        assertThrows(IllegalStateException::class.java) { lease.acquire(next, { false }) {} }
        lease.release(old) {}
        lease.acquire(next, { false }) {}
        lease.release(old) { fail("stale cleanup must never run") }
        assertThrows(IllegalStateException::class.java) { lease.use(old) {} }
        assertEquals(7, lease.use(next) { 7 })
    }

    @Test fun failedStartAndCleanupReleaseExclusivity() {
        val lease = DeviceSessionLease()
        val owner = Any()
        assertThrows(IllegalArgumentException::class.java) {
            lease.acquire(owner, { false }) { errorForTest() }
        }
        lease.acquire(owner, { false }) {}
        assertThrows(IllegalArgumentException::class.java) {
            lease.release(owner) { errorForTest() }
        }
        lease.legacy {}
    }

    @Test fun simultaneousAcquisitionHasExactlyOneWinner() {
        val lease = DeviceSessionLease()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                executor.submit<Boolean> {
                    start.await()
                    runCatching { lease.acquire(Any(), { false }) {} }.isSuccess
                }
            }
            start.countDown()
            assertEquals(1, attempts.count { it.get(2, TimeUnit.SECONDS) })
        } finally { executor.shutdownNow() }
    }

    private fun errorForTest(): Nothing = throw IllegalArgumentException("test")
}
