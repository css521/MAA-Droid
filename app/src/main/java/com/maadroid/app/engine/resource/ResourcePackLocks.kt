package com.maadroid.app.engine.resource

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/** A running engine retains a lease so templates/models cannot be replaced under it. */
object ResourcePackLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun acquire(packId: String): Closeable {
        val lock = locks.getOrPut(packId) { Mutex() }
        val owner = Any()
        lock.lock(owner)
        val closed = AtomicBoolean(false)
        return Closeable { if (closed.compareAndSet(false, true)) lock.unlock(owner) }
    }

    fun tryAcquire(packId: String): Closeable? {
        val lock = locks.getOrPut(packId) { Mutex() }
        val owner = Any()
        if (!lock.tryLock(owner)) return null
        val closed = AtomicBoolean(false)
        return Closeable { if (closed.compareAndSet(false, true)) lock.unlock(owner) }
    }
}
