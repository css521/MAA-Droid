package com.maadroid.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.maadroid.app.constant.OFFICIAL_SHIZUKU_PACKAGE
import com.maadroid.app.data.model.TaskChainNode
import com.maadroid.app.data.model.TaskProfile
import com.maadroid.app.data.model.WakeUpConfig
import com.maadroid.app.data.notification.NotificationSettings
import com.maadroid.app.data.notification.NotificationSettingsManager
import com.maadroid.app.data.repository.FakePreferencesDataStore
import com.maadroid.app.domain.models.AppSettings
import com.maadroid.app.engine.EngineTaskStore
import com.maadroid.app.engine.limbus.config.LimbusTeamConfig
import com.maadroid.app.engine.limbus.config.LimbusWorkspaceConfig
import com.maadroid.app.schedule.data.ScheduleStrategyRepository
import com.maadroid.app.schedule.model.ScheduleStrategy
import com.maadroid.app.schedule.service.ScheduleAlarmManager
import com.maadroid.app.utils.JsonUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class MultiEngineConfigBackupTest {
    private val json = JsonUtils.common
    private val profile = TaskProfile(id = "exported-ark", name = "方舟任务",
        chain = listOf(TaskChainNode(id = "wake", name = "唤醒", config = WakeUpConfig(clientType = "Bilibili"))))

    private fun backup(engineTasks: Map<String, String> = emptyMap()) = ConfigBackup(
        version = 2, appSettings = AppSettings(language = "EN"),
        notificationSettings = NotificationSettings(sendOnComplete = "false"),
        taskProfiles = listOf(profile), activeProfileId = profile.id,
        scheduleStrategies = listOf(ScheduleStrategy(id = "imported-schedule", name = "导入定时",
            enabled = false, profileId = profile.id, createdAt = 0)), engineTasks = engineTasks,
    )

    @Test fun legacyV1WireFormatPreservesLocalLimbusAndNormalizesDeviceSettings() = runBlocking {
        val rig = Rig()
        rig.store.setWorkspaceConfig("limbus", "{\"taskConfigs\":{},\"teamConfigs\":{}}")
        val engineBefore = rig.store.exportSnapshot()
        val bytes = javaClass.getResourceAsStream("/config-backup/v1.json")!!.use { it.readBytes() }
        assertFalse(bytes.decodeToString().contains("engineTasks"))
        rig.manager.importFrom(bytes.inputStream())

        assertEquals(engineBefore, rig.store.exportSnapshot())
        assertEquals("EN", rig.app.value.language)
        assertEquals("local-background", rig.app.value.customBackgroundToken)
        assertEquals("true", rig.app.value.customBackgroundEnabled)
        assertEquals(OFFICIAL_SHIZUKU_PACKAGE, rig.app.value.shizukuLaunchPackage)
        val imported = rig.profiles.value.single()
        assertEquals("legacy-ark", imported.id)
        assertEquals("Bilibili", (imported.chain.single().config as WakeUpConfig).clientType)
        assertEquals("legacy-ark", rig.activeProfile.value)
        assertTrue(rig.registeredAlarms.isEmpty()) // The legacy strategy is disabled.
    }

    @Test fun v2ExportsAndRestoresArknightsAndTheFullLimbusWorkspaceWithoutAnInstalledProvider() = runBlocking {
        val source = Rig()
        val workspace = LimbusWorkspaceConfig(
            themePackWeights = mapOf("An Unloving" to 47),
            teamConfigs = mapOf("2" to LimbusTeamConfig(teamName = "突刺队",
                selectedMembers = listOf("Yi Sang", "Faust"),
                giftName2Status = mapOf("gift-a" to "Allow List", "gift-b" to "Block List"),
                skillReplacementEnabled = mapOf("Faust" to true),
                skillReplacementOrders = mapOf("Faust" to listOf(listOf(2, 3))),
            )),
        )
        source.store.setWorkspaceConfig("limbus", workspace.encode())
        source.store.setEnabled("limbus", "mail", false)
        source.store.setParams("limbus", "mail", "{\"custom\":7}")
        val future = """{"enabled":{"mail":true},"params":{"mail":"{\"custom\":9}"},"future":{"revision":8}}"""
        source.store.importSnapshot(mapOf("future-plugin" to future))
        source.profiles.value = listOf(profile)
        source.activeProfile.value = profile.id
        val bytes = source.export()
        val encoded = json.decodeFromString<ConfigBackup>(bytes.decodeToString())
        assertEquals(2, encoded.version)
        assertEquals("", encoded.appSettings.mirrorChyanCdk)
        assertEquals("", encoded.appSettings.wakeCredential)
        assertEquals("", encoded.appSettings.customBackgroundToken)
        assertEquals("", encoded.notificationSettings.smtpPassword)
        assertEquals(listOf(profile), encoded.taskProfiles)

        val target = Rig()
        target.store.importSnapshot(mapOf("local-only" to "{}"))
        target.manager.importFrom(bytes.inputStream())
        assertEquals(future, target.store.exportSnapshot()["future-plugin"])
        assertEquals(source.store.current("limbus"), target.store.current("limbus"))
        assertEquals(workspace, LimbusWorkspaceConfig.decode(target.store.current("limbus").workspaceConfig!!))
        assertEquals("{}", target.store.exportSnapshot()["local-only"])
        assertEquals(listOf(profile), target.profiles.value)
    }

    @Test fun malformedEnvelopesNestedJsonAndFutureBackupVersionsFailBeforeAnyWrite() = runBlocking {
        for (bad in listOf("[]", "not-json", """{"workspaceConfig":"{"}""", """{"params":{"exp":"{"}}""")) {
            val rig = Rig()
            val before = rig.snapshot()
            val payload = backup(linkedMapOf("good" to "{}", "broken" to bad))
            assertTrue(runCatching { rig.restoreBackup(payload) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, rig.snapshot())
            assertTrue(rig.writes.isEmpty())
        }
        for (version in listOf(0, 3)) {
            val rig = Rig()
            assertTrue(runCatching { rig.restoreBackup(backup().copy(version = version)) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(rig.writes.isEmpty())
        }
    }

    @Test fun failuresAfterEachCommitRestoreEveryTouchedStoreAndRemoveNewEngineKeys() = runBlocking {
        for (phase in listOf("settings", "notifications", "profiles", "engines", "schedules", "alarms")) {
            val rig = Rig()
            rig.store.importSnapshot(mapOf("limbus" to "{\"future\":3}", "untouched" to "{}"))
            val before = rig.snapshot()
            rig.writes.clear()
            rig.afterWrite = { step -> if (step == phase) {
                rig.afterWrite = {}
                throw IOException("$phase commit failed")
            } }

            val failure = runCatching { rig.restoreBackup(backup(mapOf("limbus" to "{}", "new-game" to "{}"))) }.exceptionOrNull()

            assertTrue("$phase: $failure", failure is IOException)
            assertEquals("$phase must restore old values and keys", before, rig.snapshot())
            if (phase != "alarms") assertTrue(rig.writes.none { it.startsWith("alarm") })
        }
    }

    @Test fun cancellationAfterAnEngineCommitStillRestoresOldConfiguration() = runBlocking {
        withTimeout(5_000) {
            val rig = Rig()
            rig.store.importSnapshot(mapOf("limbus" to "{\"future\":3}"))
            val before = rig.snapshot()
            val committed = CompletableDeferred<Unit>()
            rig.afterEngineCommit = {
                rig.afterEngineCommit = {}
                committed.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            val pending = async { rig.restoreBackup(backup(mapOf("limbus" to "{}", "new-game" to "{}"))) }
            committed.await()
            pending.cancelAndJoin()
            assertEquals(before, rig.snapshot())
        }
    }

    @Test fun cancellationDuringSynchronousAlarmCommitStillRollsBackBeforeUnlocking() = runBlocking {
        withTimeout(5_000) {
            val rig = Rig()
            val before = rig.snapshot()
            val ready = CompletableDeferred<Unit>()
            lateinit var pending: Job
            rig.afterWrite = { step -> if (step == "alarms") {
                rig.afterWrite = {}
                pending.cancel() // No thrown exception in the IO block's last synchronous call.
            } }
            pending = async { ready.await(); rig.restoreBackup(backup(mapOf("limbus" to "{}"))) }
            ready.complete(Unit)
            pending.join()
            assertTrue(pending.isCancelled)
            assertEquals(before, rig.snapshot())
        }
    }

    @Test fun oneAlarmRestoreFailureDoesNotPreventOtherOldAlarmsBeingRestored() = runBlocking {
        val rig = Rig()
        val old = rig.strategies.value.single()
        val second = old.copy(id = "second-local-alarm")
        rig.strategies.value = listOf(old, second)
        rig.registeredAlarms += second.id
        var commitFailed = false
        rig.beforeSchedule = { list ->
            if (!commitFailed) { commitFailed = true; throw IOException("new alarms failed") }
            if (list.singleOrNull()?.id == old.id) throw IOException("first old alarm failed")
        }
        val failure = runCatching { rig.restoreBackup(backup()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(setOf(second.id), rig.registeredAlarms)
        assertEquals(listOf(old, second), rig.strategies.value)
    }

    @Test fun cancelledImportWithAFailedUndoKeepsCancellationAndNamesIncompleteRecovery() = runBlocking {
        withTimeout(5_000) {
            val rig = Rig()
            val committed = CompletableDeferred<Unit>()
            val observedFailure = CompletableDeferred<Exception>()
            rig.afterEngineCommit = {
                rig.afterEngineCommit = {}
                rig.afterWrite = { if (it == "profiles") throw IOException("restore failed") }
                committed.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            val pending = async {
                try { rig.restoreBackup(backup(mapOf("limbus" to "{}"))) }
                catch (failure: Exception) { observedFailure.complete(failure); throw failure }
            }
            committed.await()
            pending.cancelAndJoin()
            val failure = observedFailure.await()
            assertTrue("${failure.javaClass.name}: ${failure.message}", failure is IncompleteRestoreCancellationException)
            assertTrue(failure.message.orEmpty().contains("部分旧配置未能恢复"))
            assertTrue(rollbackErrors(failure).any { it is IOException && it.message == "restore failed" })
            assertEquals("ZH", rig.app.value.language) // Undo continues after the profile error.
            assertTrue(rig.store.exportSnapshot().isEmpty())
        }
    }

    @Test fun rollbackFailureIsReportedRatherThanClaimingThatOldSettingsWereRestored() = runBlocking {
        val rig = Rig()
        rig.afterWrite = { if (it == "settings" || it == "notifications") throw IOException("persistent failure") }
        val failure = runCatching { rig.restoreBackup(backup()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("部分旧配置未能恢复"))
        assertTrue(rollbackErrors(failure).any { it is IOException && it.message == "persistent failure" })
    }

    @Test fun importWaitsForLegacyStoresToLoadBeforeMutatingThem() = runBlocking {
        withTimeout(5_000) {
            val rig = Rig()
            rig.profilesLoaded.value = false
            rig.schedulesLoaded.value = false
            val pending = async { rig.restoreBackup(backup(mapOf("limbus" to "{}"))) }
            rig.profileLoadRequested.await()
            assertTrue(rig.writes.isEmpty())
            rig.profilesLoaded.value = true
            rig.scheduleLoadRequested.await()
            assertTrue(rig.writes.isEmpty())
            rig.schedulesLoaded.value = true
            pending.await()
            assertEquals(profile.id, rig.activeProfile.value)
        }
    }

    @Test fun cancellationWhileWaitingForStorageClosesBothDocumentStreams() = runBlocking {
        withTimeout(5_000) {
            val rig = Rig()
            rig.profilesLoaded.value = false
            var inputClosed = false
            var outputClosed = false
            val input = object : ByteArrayInputStream(json.encodeToString(backup()).encodeToByteArray()) {
                override fun close() { inputClosed = true; super.close() }
            }
            val output = object : ByteArrayOutputStream() {
                override fun close() { outputClosed = true; super.close() }
            }
            val importing = async { rig.manager.importFrom(input) }
            rig.profileLoadRequested.await()
            assertTrue("Input is closed before any persistent write", inputClosed)
            // The import owns the manager's mutex while the export waits for it.
            val exporting = async(start = CoroutineStart.UNDISPATCHED) { rig.manager.exportTo(output) }
            exporting.cancelAndJoin()
            importing.cancelAndJoin()
            assertTrue("Cancelling an export waiting for storage must close its document", outputClosed)
            assertTrue(rig.writes.isEmpty())
        }
    }

    @Test fun inputCloseFailureLeavesAllConfigurationUntouched() = runBlocking {
        val rig = Rig()
        val before = rig.snapshot()
        val input = object : ByteArrayInputStream(json.encodeToString(backup()).encodeToByteArray()) {
            override fun close() { throw IOException("document close failed") }
        }
        val failure = runCatching { rig.manager.importFrom(input) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("document close failed", failure!!.message)
        assertTrue(rig.writes.isEmpty())
        assertEquals(before, rig.snapshot())
    }

    // Coroutine stack recovery may wrap the same exception with its original as cause.
    private fun rollbackErrors(failure: Throwable): List<Throwable> =
        generateSequence(failure) { current -> current.cause?.takeUnless { it === current } }
            .take(16).flatMap { it.suppressed.asSequence() }.toList()

    /** Real manager/engine store; legacy repositories and OS alarms are controlled boundaries. */
    private class Rig {
        val app = MutableStateFlow(AppSettings(language = "ZH", mirrorChyanCdk = "local-cdk",
            wakeCredential = "local-pin", customBackgroundEnabled = "true", customBackgroundToken = "local-background"))
        val notification = MutableStateFlow(NotificationSettings(smtpPassword = "local-password"))
        val profiles = MutableStateFlow(listOf(TaskProfile(id = "local-ark", name = "本机方舟", chain = emptyList())))
        val activeProfile = MutableStateFlow("local-ark")
        val strategies = MutableStateFlow(listOf(ScheduleStrategy(id = "local-alarm", name = "本机定时",
            enabled = true, profileId = "local-ark", createdAt = 0)))
        val profilesLoaded = MutableStateFlow(true)
        val schedulesLoaded = MutableStateFlow(true)
        val profileLoadRequested = CompletableDeferred<Unit>()
        val scheduleLoadRequested = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val registeredAlarms = mutableSetOf("local-alarm")
        var afterWrite: (String) -> Unit = {}
        var afterEngineCommit: suspend () -> Unit = {}
        var beforeSchedule: (List<ScheduleStrategy>) -> Unit = {}
        private val preferences = FakePreferencesDataStore()
        private val controlledStore = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                preferences.updateData(transform).also { wrote("engines"); afterEngineCommit() }
        }
        val store = EngineTaskStore(controlledStore)
        private fun wrote(step: String) { writes += step; afterWrite(step) }
        private val settingsManager = mockk<AppSettingsManager> {
            every { settings } returns app
            coEvery { setSettings(any()) } coAnswers { app.value = firstArg(); wrote("settings") }
        }
        private val notificationManager = mockk<NotificationSettingsManager> {
            every { settings } returns notification
            coEvery { updateSettings(any()) } coAnswers { notification.value = firstArg(); wrote("notifications") }
        }
        private val chain = mockk<TaskChainState> {
            every { isLoaded } answers { profileLoadRequested.complete(Unit); profilesLoaded }
            every { this@mockk.profiles } returns this@Rig.profiles
            every { profileId } returns activeProfile
            coEvery { importProfiles(any(), any()) } coAnswers {
                this@Rig.profiles.value = firstArg(); activeProfile.value = secondArg(); wrote("profiles")
            }
        }
        private val schedules = mockk<ScheduleStrategyRepository> {
            every { this@mockk.strategies } returns this@Rig.strategies
            coEvery { snapshot() } coAnswers {
                scheduleLoadRequested.complete(Unit)
                schedulesLoaded.first { it }
                this@Rig.strategies.value
            }
            coEvery { importStrategies(any()) } coAnswers { this@Rig.strategies.value = firstArg(); wrote("schedules") }
        }
        private val alarms = mockk<ScheduleAlarmManager> {
            every { cancel(any()) } answers { registeredAlarms.remove(firstArg()); writes += "alarm.cancel"; Unit }
            every { rescheduleAll(any()) } answers {
                val list = firstArg<List<ScheduleStrategy>>()
                beforeSchedule(list)
                list.forEach { strategy ->
                    registeredAlarms.remove(strategy.id)
                    if (strategy.enabled) registeredAlarms += strategy.id
                }
                wrote("alarms")
            }
        }
        val manager = ConfigBackupManager(settingsManager, notificationManager, chain, schedules, alarms, store)
        suspend fun export(): ByteArray = ByteArrayOutputStream().also { manager.exportTo(it) }.toByteArray()
        suspend fun restoreBackup(backup: ConfigBackup) = manager.importFrom(JsonUtils.common.encodeToString(backup).byteInputStream())
        suspend fun snapshot(): List<Any> = listOf(app.value, notification.value, profiles.value,
            activeProfile.value, strategies.value, preferences.data.first(), registeredAlarms.toSet())
    }
}
