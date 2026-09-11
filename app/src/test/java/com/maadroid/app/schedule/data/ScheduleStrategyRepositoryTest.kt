package com.maadroid.app.schedule.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maadroid.app.data.repository.FakePreferencesDataStore
import com.maadroid.app.schedule.model.ScheduleStrategy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ScheduleStrategyRepositoryTest {
    @Test fun snapshotReadsCommittedSchedulesBeforeTheUiCollectorRuns() = runBlocking {
        val pending = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val data = FakePreferencesDataStore()
        val repository = ScheduleStrategyRepository(data, scope)
        try {
            val old = ScheduleStrategy(id = "old", name = "旧定时", profileId = "ark", createdAt = 0)
            repository.importStrategies(listOf(old))
            assertTrue(repository.strategies.value.isEmpty()) // UI collector has not run yet.
            assertFalse(repository.isLoaded.value)
            val checkpoint = repository.snapshot()
            assertEquals(listOf(old), checkpoint)

            val replacement = old.copy(id = "replacement")
            repository.importStrategies(listOf(replacement))
            assertEquals(listOf(replacement), repository.snapshot())
            repository.importStrategies(checkpoint)
            assertEquals(listOf(old), repository.snapshot())

            data.edit { it[stringPreferencesKey("strategies")] = "broken-json" }
            assertTrue(runCatching { repository.snapshot() }.isFailure)
        } finally {
            scope.cancel()
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }
}
