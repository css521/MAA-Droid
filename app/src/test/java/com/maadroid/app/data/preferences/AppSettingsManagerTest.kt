package com.maadroid.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.maadroid.app.data.achievement.AchievementEvents
import com.maadroid.app.data.achievement.AchievementRepository
import com.maadroid.app.data.preferences.AppSettingsManager.AppLanguage
import com.maadroid.app.data.preferences.AppSettingsManager.Companion.dataStore
import com.maadroid.app.domain.models.AppSettings
import com.maadroid.app.domain.models.AppSettingsSchema
import com.maadroid.app.koin.appModule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.IOException

class AppSettingsManagerTest {
    private val context = mockk<Context>()
    private val store = ControlledPreferencesStore()

    @Before fun useControlledStore() {
        mockkObject(AppSettingsManager.Companion)
        every { context.dataStore } returns store
    }

    @After fun restoreStoreGetter() {
        unmockkObject(AppSettingsManager.Companion)
    }

    @Test fun languageCallbackWaitsForCommitAndIsAwaitedByTheSetter() = runBlocking {
        withTimeout(5_000) {
            val writeEntered = CompletableDeferred<Unit>()
            val allowCommit = CompletableDeferred<Unit>()
            val callbackEntered = CompletableDeferred<Unit>()
            val allowCallback = CompletableDeferred<Unit>()
            store.beforeCommit = { writeEntered.complete(Unit); allowCommit.await() }
            val seenLanguages = mutableListOf<String?>()
            val settings = AppSettingsManager(context) {
                seenLanguages += store.persisted[AppSettingsSchema.language]
                callbackEntered.complete(Unit)
                allowCallback.await()
            }

            val change = async(start = CoroutineStart.UNDISPATCHED) { settings.setLanguage(AppLanguage.EN) }
            writeEntered.await()
            assertTrue(seenLanguages.isEmpty())
            assertNull(store.persisted[AppSettingsSchema.language])
            assertFalse(change.isCompleted)

            allowCommit.complete(Unit)
            callbackEntered.await()
            assertEquals(listOf(AppLanguage.EN.name), seenLanguages)
            assertFalse("setLanguage must still await its callback", change.isCompleted)
            allowCallback.complete(Unit)
            change.await()
        }
    }

    @Test fun failedWriteDoesNotReportLanguageChange() = runBlocking {
        var callbacks = 0
        val settings = AppSettingsManager(context) { callbacks++ }
        store.beforeCommit = { throw IOException("settings write failed") }

        val failure = runCatching { settings.setLanguage(AppLanguage.EN) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("settings write failed", failure?.message)
        assertEquals(0, callbacks)
        assertNull(store.persisted[AppSettingsSchema.language])
    }

    @Test fun cancelledWriteDoesNotReportLanguageChange() = runBlocking {
        withTimeout(5_000) {
            val writeEntered = CompletableDeferred<Unit>()
            val allowCommit = CompletableDeferred<Unit>()
            store.beforeCommit = { writeEntered.complete(Unit); allowCommit.await() }
            var callbacks = 0
            val settings = AppSettingsManager(context) { callbacks++ }

            val change = async(start = CoroutineStart.UNDISPATCHED) { settings.setLanguage(AppLanguage.EN) }
            writeEntered.await()
            change.cancelAndJoin()

            assertEquals(0, callbacks)
            assertNull(store.persisted[AppSettingsSchema.language])
        }
    }

    @Test fun callbackFailureKeepsTheSavedLanguageAndPropagates() = runBlocking {
        var callbacks = 0
        val settings = AppSettingsManager(context) {
            callbacks++
            throw IOException("report failed")
        }

        val failure = runCatching { settings.setLanguage(AppLanguage.EN) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("report failed", failure?.message)
        assertEquals(1, callbacks)
        assertEquals(AppLanguage.EN.name, store.persisted[AppSettingsSchema.language])
    }

    @Test fun onlySuccessfulLanguageSettersReportIncludingRepeatedSelections() = runBlocking {
        var callbacks = 0
        val settings = AppSettingsManager(context) { callbacks++ }
        assertEquals(0, callbacks)
        settings.setSettings(AppSettings(language = AppLanguage.ZH.name))
        settings.setDebugMode(true)
        assertEquals(0, callbacks)

        // Keep the existing condition: every successful setter, including SYSTEM and
        // a repeated selection, reports. Bulk settings replacement does not report.
        for (language in AppLanguage.entries + AppLanguage.EN) settings.setLanguage(language)

        assertEquals(AppLanguage.entries.size + 1, callbacks)
    }

    @Test fun hostModuleResolvesReporterLazilyAndReportsTheOriginalEventAfterCommit() = runBlocking {
        withTimeout(5_000) {
            val repository = mockk<AchievementRepository>()
            val reportEntered = CompletableDeferred<Unit>()
            val allowReport = CompletableDeferred<Unit>()
            var repositoryCreations = 0
            val events = mutableListOf<String>()
            coEvery { repository.report(any()) } coAnswers {
                assertEquals(AppLanguage.EN.name, store.persisted[AppSettingsSchema.language])
                val event = AchievementRepository.ReportBuilder().apply(firstArg())
                assertEquals(1, event.amount)
                assertTrue(event.payload.isEmpty())
                events += event.event
                reportEntered.complete(Unit)
                allowReport.await()
            }
            val application = koinApplication {
                modules(appModule, module {
                    single<Context> { context }
                    single { repositoryCreations++; repository }
                })
            }
            try {
                // Use the real settings/reporter definitions. Eager reporter resolution
                // would recurse through reporter -> settings during construction.
                val settings = application.koin.get<AppSettingsManager>()
                assertEquals(0, repositoryCreations)

                val change = async(start = CoroutineStart.UNDISPATCHED) { settings.setLanguage(AppLanguage.EN) }
                reportEntered.await()

                assertEquals(1, repositoryCreations)
                assertEquals(listOf(AchievementEvents.LANGUAGE_CHANGED), events)
                assertFalse("Host wiring must await the repository report", change.isCompleted)
                allowReport.complete(Unit)
                change.await()
                coVerify(exactly = 1) { repository.report(any()) }
                assertSame(settings, application.koin.get<AppSettingsManager>())
            } finally {
                allowReport.complete(Unit)
                application.close()
            }
        }
    }

    /** Controls DataStore's commit boundary, without testing Android or filesystem I/O. */
    private class ControlledPreferencesStore : DataStore<Preferences> {
        var persisted: Preferences = emptyPreferences()
            private set
        var beforeCommit: suspend () -> Unit = {}

        // Finite snapshots let the manager's eager IO collectors finish after each read.
        override val data: Flow<Preferences> = flow { emit(persisted) }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(persisted)
            beforeCommit()
            persisted = updated
            return updated
        }
    }
}
