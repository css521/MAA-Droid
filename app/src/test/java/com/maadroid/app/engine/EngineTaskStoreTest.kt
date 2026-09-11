package com.maadroid.app.engine

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.maadroid.app.data.repository.FakePreferencesDataStore
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EngineTaskStoreTest {
    private val preferences = FakePreferencesDataStore()
    private val store = EngineTaskStore(preferences)
    private val futureEnvelope = """{
        "enabled":{"mail":false,"future-task":true},
        "params":{"future-task":"{\"incomplete\":true}"},
        "workspaceConfig":"{\"teamConfigs\":{\"2\":{\"name\":\"队伍三\"}}}",
        "future":{"revision":7,"values":[1,2,3]}
    }""".trimIndent()

    @Test fun backupRoundTripKeepsUnknownEnginesFieldsAndUnfinishedDraftsVerbatim() = runBlocking {
        val snapshot = linkedMapOf("limbus" to futureEnvelope, "uninstalled.game" to """{"pluginState":42}""")
        store.importSnapshot(snapshot)
        assertEquals(snapshot, store.exportSnapshot())
        val second = EngineTaskStore(FakePreferencesDataStore())
        second.importSnapshot(store.exportSnapshot())
        assertEquals(snapshot, second.exportSnapshot())
        assertEquals("{\"incomplete\":true}", second.current("limbus").params["future-task"])
    }

    @Test fun editsAfterRestorePreserveFutureFieldsAndOtherEngineData() = runBlocking {
        store.importSnapshot(mapOf("limbus" to futureEnvelope, "another" to futureEnvelope))
        val original = Json.parseToJsonElement(futureEnvelope).jsonObject
        store.setEnabled("limbus", "exp", true)
        store.setParams("limbus", "exp", "{\"stage\":\"09\"}")
        store.setWorkspaceConfig("limbus", "{\"newDraft\":true}")
        val snapshot = store.exportSnapshot()
        val updated = Json.parseToJsonElement(snapshot.getValue("limbus")).jsonObject
        assertEquals(original["future"], updated["future"])
        assertEquals(futureEnvelope, snapshot["another"])
        assertEquals(true, store.current("limbus").enabled["exp"])
        assertEquals("{\"newDraft\":true}", store.current("limbus").workspaceConfig)
        assertEquals("{\"incomplete\":true}", store.current("limbus").params["future-task"])
    }

    @Test fun importOnlyReplacesIncludedEnginesAndEmptyLegacySnapshotDoesNotWrite() = runBlocking {
        store.importSnapshot(mapOf("limbus" to futureEnvelope, "local-only" to futureEnvelope))
        val edits = preferences.editCount.get()
        store.importSnapshot(emptyMap())
        assertEquals(edits, preferences.editCount.get())
        store.importSnapshot(mapOf("limbus" to """{"enabled":{"exp":false}}"""))
        assertEquals(false, store.current("limbus").enabled["exp"])
        assertEquals(futureEnvelope, store.exportSnapshot()["local-only"])
        assertEquals(store.current("limbus"), store.flow("limbus").first())
    }

    @Test fun validatesEveryEnvelopeBeforeAnyImportWrite() = runBlocking {
        store.importSnapshot(mapOf("limbus" to futureEnvelope))
        val before = store.exportSnapshot()
        val edits = preferences.editCount.get()
        for (bad in listOf("not-json", "null", "[]", "", """{"enabled":[]} """, """{"workspaceConfig":{}}""",
            """{"workspaceConfig":"{"}""", """{"params":{"exp":"not-json"}}""", """{"future":not-json}""")) {
            val error = runCatching { store.importSnapshot(linkedMapOf("limbus" to "{}", "broken" to bad)) }.exceptionOrNull()
            assertTrue("$bad: $error", error is IllegalArgumentException)
            assertEquals(before, store.exportSnapshot())
            assertEquals(edits, preferences.editCount.get())
        }
        assertTrue(runCatching { store.importSnapshot(mapOf(" " to "{}")) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, store.exportSnapshot())
    }

    @Test fun unreadableSavedEnvelopeFailsExportInsteadOfBackingUpStartupDefaults() = runBlocking {
        preferences.edit { it[stringPreferencesKey("engine.limbus.tasks")] = "broken-json" }
        assertEquals(EngineTaskStore.EngineTasks(), store.current("limbus"))
        assertTrue(runCatching { store.exportSnapshot() }.exceptionOrNull() is IllegalArgumentException)
        preferences.edit { it[stringPreferencesKey("engine.limbus.tasks")] = "{}" }
        preferences.edit { it[intPreferencesKey("engine.wrong-type.tasks")] = 3 }
        assertTrue(runCatching { store.exportSnapshot() }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun importsAllEnginesInOneCommitAndPropagatesDiskFailure() = runBlocking {
        store.importSnapshot(mapOf("limbus" to futureEnvelope))
        val before = preferences.data.first()
        val edits = preferences.editCount.get()
        preferences.failWrites = true
        val error = runCatching { store.importSnapshot(mapOf("limbus" to "{}", "new-game" to "{}")) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(before, preferences.data.first())
        assertEquals(edits, preferences.editCount.get())
        preferences.failWrites = false
        store.importSnapshot(mapOf("limbus" to "{}", "new-game" to "{}"))
        assertEquals(edits + 1, preferences.editCount.get())
    }

    @Test fun importDoesNotReturnBeforePersistenceAndCancellationLeavesPreviousState() = runBlocking {
        withTimeout(5_000) {
            val underlying = FakePreferencesDataStore()
            var entered = CompletableDeferred<Unit>()
            var allowCommit = CompletableDeferred<Unit>()
            val gated = object : DataStore<Preferences> by underlying {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    entered.complete(Unit)
                    allowCommit.await()
                    return underlying.updateData(transform)
                }
            }
            val delayedStore = EngineTaskStore(gated)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { delayedStore.importSnapshot(mapOf("limbus" to futureEnvelope)) }
            entered.await()
            assertFalse(pending.isCompleted)
            assertTrue(delayedStore.exportSnapshot().isEmpty())
            allowCommit.complete(Unit)
            pending.await()
            assertEquals(futureEnvelope, delayedStore.exportSnapshot()["limbus"])

            entered = CompletableDeferred()
            allowCommit = CompletableDeferred()
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { delayedStore.importSnapshot(mapOf("limbus" to "{}", "new-game" to "{}")) }
            entered.await()
            cancelled.cancelAndJoin()
            assertEquals(mapOf("limbus" to futureEnvelope), delayedStore.exportSnapshot())
        }
    }
}
