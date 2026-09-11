package com.maadroid.app.domain.service

import com.maadroid.app.data.config.MaaPathConfig
import com.maadroid.app.engine.arknights.ArknightsResourcePreparation
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import android.content.Context
import android.os.IBinder
import com.maadroid.app.MaaCoreService
import com.maadroid.app.RemoteService
import com.maadroid.app.constant.DefaultDisplayConfig
import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.data.preferences.TaskChainState
import com.maadroid.app.data.model.LogLevel
import com.maadroid.app.data.resource.ActivityManager
import com.maadroid.app.domain.models.RunMode
import com.maadroid.app.engine.EngineExecutionCoordinator
import com.maadroid.app.engine.arknights.core.AidlMaaCoreClient
import com.maadroid.app.engine.arknights.core.AsstMsg
import com.maadroid.app.engine.arknights.state.MaaExecutionState
import com.maadroid.app.maa.callback.MaaCallbackDispatcher
import com.maadroid.app.maa.callback.TaskChainStatusTracker
import com.maadroid.app.maa.task.MaaTaskParams
import com.maadroid.app.maa.task.MaaTaskType
import com.maadroid.app.manager.RemoteAccessCoordinator
import com.maadroid.app.manager.RemoteAccessState
import com.maadroid.app.manager.RemoteServiceManager
import com.maadroid.app.manager.ShizukuManager
import com.maadroid.app.manager.RootManager
import com.maadroid.app.remote.EngineIds
import io.mockk.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import com.maadroid.app.R
import com.maadroid.app.utils.Misc

/** Run the real public start/stop path with the Binder boundary replaced, including host admission. */
class MaaCompositionLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private val paths = mockk<MaaPathConfig>()
    private val context = mockk<Context>(relaxed = true)
    private val resources = mockk<MaaResourceLoader>(relaxed = true)
    private val settings = mockk<AppSettingsManager>(relaxed = true)
    private val dispatcher = mockk<UnifiedStateDispatcher>()
    private val watchdog = mockk<AppWatchdog>(relaxed = true)
    private val fps = mockk<GameFpsWatcher>(relaxed = true)
    private val logger = mockk<MaaSessionLogger>(relaxed = true)
    private val notifications = mockk<MaaNotificationCenter>(relaxed = true)
    private val activity = mockk<ActivityManager>(relaxed = true)
    private val pusher = mockk<CoreDataPusher>(relaxed = true)
    private val chain = mockk<TaskChainState>(relaxed = true)
    private val callbacks = mockk<MaaCallbackDispatcher>(relaxed = true)
    private val tracker = mockk<TaskChainStatusTracker>(relaxed = true)
    private val remote = mockk<RemoteService>(relaxed = true)
    private val maa = mockk<MaaCoreService>(relaxed = true)
    private val binder = mockk<IBinder>()
    private val serviceBinder = mockk<IBinder>()
    private val serviceDeaths = MutableSharedFlow<Unit>()
    private val deathRecipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
    @Volatile private var serviceAlive = true
    private lateinit var composition: MaaCompositionService
    @Volatile private var instance = false
    @Volatile private var nativeRunning = false
    @Volatile private var stopAccepted = true
    @Volatile private var stopThrows = false
    private var rejectAppend = false
    private var createCount = 0
    @Volatile private var callback: (Int, String?) -> Unit = { _, _ -> }

    @Before fun setUp() {
        startKoin { modules(module { single { callbacks } }) }
        // Model the dispatcher's terminal side effects, so tests detect early business cleanup
        // as well as an early IDLE/lease release. Nonterminal callbacks still execute inline.
        every { callbacks.onEvent(any(), any(), any()) } answers {
            when (firstArg<Int>()) {
                AsstMsg.AllTasksCompleted.value -> {
                    val stopping = composition.currentRunState() == MaaExecutionState.STOPPING
                    if (!stopping) composition.reportRunState(MaaExecutionState.IDLE)
                    logger.endSession(if (stopping) "STOPPED" else "COMPLETED")
                }
                AsstMsg.TaskChainStopped.value -> {
                    composition.reportRunState(MaaExecutionState.IDLE)
                    logger.endSession("STOPPED")
                }
                AsstMsg.InitFailed.value -> {
                    composition.reportRunState(MaaExecutionState.ERROR)
                    logger.endSession("INIT_FAILED")
                    notifications.notifyStartFailed("native init failed")
                }
            }
        }
        // Intercept platform probes before RemoteAccessCoordinator takes its initial snapshot.
        mockkObject(ShizukuManager, RootManager)
        every { ShizukuManager.isAvailable() } returns true
        every { ShizukuManager.isGranted() } returns true
        every { RootManager.isAvailable() } returns false
        every { RootManager.isGranted() } returns false
        mockkObject(RemoteServiceManager, RemoteAccessCoordinator, TaskExecutionService.Companion)
        mockkConstructor(AidlMaaCoreClient::class)
        every { RemoteServiceManager.state } returns MutableStateFlow(RemoteServiceManager.ServiceState.Disconnected)
        every { RemoteAccessCoordinator.refresh() } returns RemoteAccessState(shizukuAvailable = true, shizukuGranted = true)
        coEvery { RemoteServiceManager.useRemoteService<MaaCompositionService.StartResult>(any(), any()) } coAnswers {
            secondArg<suspend (RemoteService) -> MaaCompositionService.StartResult>()(remote)
        }
        every { TaskExecutionService.start(any()) } just Runs
        every { binder.queryLocalInterface(any()) } returns maa
        every { remote.asBinder() } returns serviceBinder
        every { serviceBinder.isBinderAlive } answers { serviceAlive }
        every { serviceBinder.linkToDeath(any(), 0) } answers { deathRecipients += firstArg<IBinder.DeathRecipient>() }
        every { serviceBinder.unlinkToDeath(any(), 0) } returns true
        every { remote.getEngineService(EngineIds.ARKNIGHTS) } returns binder
        every { maa.asBinder() } returns binder
        every { remote.setVirtualDisplayMode(any()) } returns true
        every { remote.startVirtualDisplay() } returns 7
        every { context.getString(any()) } answers { "string:${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg()) } returns "message"
        every { resources.state } returns MutableStateFlow(MaaResourceLoader.State.Ready)
        coEvery { resources.ensureLoaded(any()) } returns Result.success(Unit)
        coEvery { activity.runIfDirty(any()) } just Runs
        coEvery { pusher.pushUserData() } returns true
        every { settings.runMode } returns MutableStateFlow(RunMode.BACKGROUND)
        every { settings.deployWithPause } returns MutableStateFlow(false)
        every { settings.backgroundResolution } returns MutableStateFlow(DefaultDisplayConfig.ResolutionPreference.P720)
        every { dispatcher.serviceDiedEvent } returns serviceDeaths
        every { watchdog.appDiedEvent } returns MutableSharedFlow()
        every { watchdog.displayDriftEvent } returns MutableSharedFlow()
        every { anyConstructed<AidlMaaCoreClient>().hasInstance() } answers { instance }
        every { anyConstructed<AidlMaaCoreClient>().running() } answers { nativeRunning }
        every { anyConstructed<AidlMaaCoreClient>().createInstance(any()) } answers {
            callback = firstArg()
            createCount++
            instance = true
            true
        }
        every { anyConstructed<AidlMaaCoreClient>().setInstanceOption(any(), any()) } returns true
        every { anyConstructed<AidlMaaCoreClient>().asyncConnect(any()) } answers {
            callback(AsstMsg.AsyncCallInfo.value, """{"what":"Connect","async_call_id":1,"details":{"ret":true}}""")
            1
        }
        every { anyConstructed<AidlMaaCoreClient>().appendTask(any(), any()) } answers { if (rejectAppend) 0 else 1 }
        every { anyConstructed<AidlMaaCoreClient>().start() } answers { nativeRunning = true; true }
        every { anyConstructed<AidlMaaCoreClient>().stop() } answers {
            if (stopThrows) error("Binder transport failure")
            if (stopAccepted) nativeRunning = false
            stopAccepted
        }
        every { anyConstructed<AidlMaaCoreClient>().version() } returns "v6.17.2"
        every { anyConstructed<AidlMaaCoreClient>().setTaskParams(any(), any()) } returns true
        every { paths.cacheResourceDir } returns temp.newFolder().absolutePath
        composition = MaaCompositionService(
            context, resources, settings, mockk(relaxed = true), dispatcher, logger, activity,
            watchdog, fps, chain, mockk(relaxed = true), tracker,
            notifications, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            ArknightsResourcePreparation(resources, paths, pusher, activity), paths,
        )
    }

    @After fun tearDown() {
        stopAccepted = true
        stopThrows = false
        try {
            if (::composition.isInitialized) runBlocking { composition.stop() }
        } finally {
            if (::composition.isInitialized) {
                val ownedScope = MaaCompositionService::class.java.getDeclaredField("scope").let {
                    it.isAccessible = true
                    it.get(composition) as CoroutineScope
                }
                runBlocking { ownedScope.coroutineContext[Job]?.cancelAndJoin() }
            }
            stopKoin()
            unmockkConstructor(AidlMaaCoreClient::class)
            unmockkObject(RemoteServiceManager, RemoteAccessCoordinator, TaskExecutionService.Companion)
            unmockkObject(ShizukuManager, RootManager)
        }
    }

    private suspend fun start() = composition.start(listOf(MaaTaskParams(MaaTaskType.FIGHT, "{}")), "Official")

    private fun die() {
        serviceAlive = false
        instance = false
        nativeRunning = false
        deathRecipients.last().binderDied()
    }

    @Test fun legacyArknightsStartReclaimsIdlePreviewBeforeResourceAndNativeWork() = runBlocking {
        val previewOwner = com.maadroid.app.engine.EngineSession.Companion
        mockkObject(previewOwner)
        var handedOff = false
        coEvery { previewOwner.closeRetainedPreview() } coAnswers {
            assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
            handedOff = true
        }
        coEvery { resources.ensureLoaded(any()) } coAnswers {
            assertTrue(handedOff)
            Result.success(Unit)
        }
        try {
            assertTrue(start() is MaaCompositionService.StartResult.Success)
            coVerify(exactly = 1) { previewOwner.closeRetainedPreview() }
            verify(exactly = 1) { anyConstructed<AidlMaaCoreClient>().start() }
        } finally { unmockkObject(previewOwner) }
    }

    @Test fun failedIdlePreviewHandoffDoesNotStartArknightsOrLeakItsAdmission() = runBlocking {
        val previewOwner = com.maadroid.app.engine.EngineSession.Companion
        mockkObject(previewOwner)
        coEvery { previewOwner.closeRetainedPreview() } throws IllegalStateException("preview close failed")
        try {
            assertTrue(runCatching { start() }.isFailure)
            assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
            coVerify(exactly = 0) { resources.ensureLoaded(any()) }
            verify(exactly = 0) { anyConstructed<AidlMaaCoreClient>().start() }
        } finally { unmockkObject(previewOwner) }
    }

    @Test fun failedStopKeepsDeviceReservedAndAllowsSuccessfulRetry() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
        stopAccepted = false
        assertEquals(MaaCompositionService.StopResult.Failed, composition.stop())
        assertEquals(MaaExecutionState.RUNNING, composition.state.value)
        assertNull(EngineExecutionCoordinator.shared.tryStart(EngineIds.LIMBUS))
        verify(exactly = 0) { notifications.notifyTaskStopped(); watchdog.stopWatching(); fps.stop() }
        stopAccepted = true
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        assertEquals(MaaExecutionState.IDLE, composition.state.value)
        checkNotNull(EngineExecutionCoordinator.shared.tryStart(EngineIds.LIMBUS)).close()
        verify(exactly = 1) { notifications.notifyTaskStopped() }
    }

    @Test fun stopTransportFailureDoesNotReleaseOwnershipOrClaimStopped() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        stopThrows = true
        assertEquals(MaaCompositionService.StopResult.Failed, composition.stop())
        assertEquals(MaaExecutionState.RUNNING, composition.state.value)
        assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) { notifications.notifyTaskStopped() }
    }

    @Test fun appendFailureNeverStartsAndReturnsAdmission() = runBlocking {
        rejectAppend = true
        assertEquals(MaaCompositionService.StartResult.StartError, start())
        assertEquals(MaaExecutionState.ERROR, composition.state.value)
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) { anyConstructed<AidlMaaCoreClient>().start() }
    }

    @Test fun startupCleanupFailureKeepsForegroundAndLogSessionUntilStopRetry() = runBlocking {
        every { anyConstructed<AidlMaaCoreClient>().appendTask(any(), any()) } answers {
            stopAccepted = false
            0
        }
        assertTrue(start() is MaaCompositionService.StartResult.StartError)
        assertEquals(MaaExecutionState.RUNNING, composition.state.value)
        assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 1) { TaskExecutionService.start(context) }
        coVerify(exactly = 0) { logger.endSessionAndWait(any()) }
        verify(exactly = 0) { notifications.notifyStartFailed(any()); notifications.notifyTaskStopped() }
        stopAccepted = true
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 1) { logger.endSession("STOPPED"); notifications.notifyTaskStopped() }
    }

    @Test fun repeatedRunsCreateIndependentEnginesEvenOnTheSameLiveBinder() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        assertEquals(2, createCount)
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        val replacement = mockk<IBinder> { every { queryLocalInterface(any()) } returns maa }
        every { remote.getEngineService(EngineIds.ARKNIGHTS) } returns replacement
        every { maa.asBinder() } returns replacement
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        assertEquals(3, createCount)
    }

    @Test fun cancellationAfterNativeStartStopsCoreAndReturnsAdmission() = runBlocking {
        val nativeStarted = CompletableDeferred<Unit>()
        coEvery { logger.appendAndWait(any<String>(), LogLevel.SUCCESS) } coAnswers {
            nativeStarted.complete(Unit)
            awaitCancellation()
        }
        val startup = launch { start() }
        withTimeout(5_000) { nativeStarted.await() }
        assertTrue(nativeRunning)
        startup.cancelAndJoin()
        assertFalse(nativeRunning)
        assertFalse(composition.isTaskActive)
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        coEvery { logger.appendAndWait(any<String>(), LogLevel.SUCCESS) } just Runs
        assertTrue(start() is MaaCompositionService.StartResult.Success)
    }

    @Test fun userDataFailureIsRejectedBeforeNativeOrDisplayAcquisition() = runBlocking {
        coEvery { pusher.pushUserData() } returns false
        assertTrue(start() is MaaCompositionService.StartResult.ResourceError)
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) {
            remote.startVirtualDisplay()
            anyConstructed<AidlMaaCoreClient>().createInstance(any())
            TaskExecutionService.start(any())
        }
    }

    @Test fun busyNativeInstanceIsPreservedBeforeAnyDisplayChanges() = runBlocking {
        instance = true
        nativeRunning = true
        assertEquals(MaaCompositionService.StartResult.AlreadyRunning, start())
        assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) {
            remote.setVirtualDisplayMode(any())
            remote.setVirtualDisplayResolution(any(), any(), any())
            remote.startVirtualDisplay()
            anyConstructed<AidlMaaCoreClient>().createInstance(any())
            anyConstructed<AidlMaaCoreClient>().stop()
        }
    }

    @Test fun foregroundUsesPhysicalResolutionAndKeepsBackgroundMonitorsOff() = runBlocking {
        mockkObject(Misc)
        every { Misc.getScreenSize(context) } returns (1920 to 1080)
        every { Misc.isAspectRatio16x9(1920, 1080) } returns true
        every { settings.runMode } returns MutableStateFlow(RunMode.FOREGROUND)
        every { remote.startVirtualDisplay() } returns 0
        try {
            assertTrue(start() is MaaCompositionService.StartResult.Success)
            verify { anyConstructed<AidlMaaCoreClient>().asyncConnect(match {
                it.contains("\"width\":1920") && it.contains("\"height\":1080") && it.contains("\"display_id\":0")
            }) }
            verify(exactly = 0) { remote.setVirtualDisplayResolution(any(), any(), any()); watchdog.startWatching(); fps.start() }
            assertEquals(1920, composition.displayResolution.value.width)
            assertEquals(1080, composition.displayResolution.value.height)
        } finally { unmockkObject(Misc) }
    }

    @Test fun allNativeTaskIdsAreRegisteredBeforeSynchronousParameterRefresh() = runBlocking {
        var nextId = 40
        every { anyConstructed<AidlMaaCoreClient>().appendTask(any(), any()) } answers { ++nextId }
        every { callbacks.onEvent(AsstMsg.TaskChainStart.value, any(), any()) } answers {
            verify { tracker.register(41, "Fight", any()); tracker.register(42, "Recruit", any()) }
            assertTrue(thirdArg<(Int, String) -> Boolean>()(41, "{\"times\":3}"))
        }
        every { anyConstructed<AidlMaaCoreClient>().start() } answers {
            nativeRunning = true
            callback(AsstMsg.TaskChainStart.value, """{"taskid":41,"taskchain":"Fight"}""")
            true
        }
        assertTrue(composition.start(listOf(MaaTaskParams(MaaTaskType.FIGHT, "{}"),
            MaaTaskParams(MaaTaskType.RECRUIT, "{}")), "Official") is MaaCompositionService.StartResult.Success)
        verify(exactly = 1) { anyConstructed<AidlMaaCoreClient>().setTaskParams(41, "{\"times\":3}") }
    }

    @Test fun naturalCompletionWaitsForNativeStopThenRetainsTheGameDisplay() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        every { anyConstructed<AidlMaaCoreClient>().stop() } answers {
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            nativeRunning = false
            true
        }
        try {
            nativeRunning = false
            callback(AsstMsg.AllTasksCompleted.value, "{}")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(composition.isTaskActive)
            assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
            verify(exactly = 0) { callbacks.onEvent(AsstMsg.AllTasksCompleted.value, any(), any()); logger.endSession(any()) }
        } finally { finish.countDown() }
        withTimeout(5_000) { composition.state.first { it == MaaExecutionState.IDLE } }
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 1) { callbacks.onEvent(AsstMsg.AllTasksCompleted.value, any(), any()); logger.endSession("COMPLETED") }
        verify(exactly = 0) { remote.stopVirtualDisplay() }
        assertTrue(start() is MaaCompositionService.StartResult.Success)
    }

    @Test fun naturalCompletionWithRejectedStopRetainsLeaseUntilManualRetry() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        stopAccepted = false
        nativeRunning = false
        callback(AsstMsg.AllTasksCompleted.value, "{}")
        verify(timeout = 5_000) { logger.append("string:${R.string.runlog_task_stop_failed}", LogLevel.ERROR) }
        assertTrue(composition.isTaskActive)
        assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) { callbacks.onEvent(AsstMsg.AllTasksCompleted.value, any(), any()); logger.endSession(any()) }
        stopAccepted = true
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 1) { logger.endSession("COMPLETED") }
        verify(exactly = 0) { logger.endSession("STOPPED"); notifications.notifyTaskStopped() }
        verify(exactly = 0) { remote.stopVirtualDisplay() }
    }

    @Test fun synchronousCompletionInsideStartCannotLeaveTheHostRunning() = runBlocking {
        every { anyConstructed<AidlMaaCoreClient>().start() } answers {
            callback(AsstMsg.AllTasksCompleted.value, "{}")
            true
        }
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        withTimeout(5_000) { composition.state.first { it == MaaExecutionState.IDLE } }
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) { watchdog.startWatching(); fps.start() }
    }

    @Test fun oldCallbacksCannotStopTheNextEngineOnTheSameBinder() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        val old = callback
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        old(AsstMsg.TaskChainStart.value, """{"taskid":1}""")
        old(AsstMsg.AllTasksCompleted.value, "{}")
        assertEquals(MaaExecutionState.RUNNING, composition.state.value)
        assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) { callbacks.onEvent(any(), any(), any()) }
    }

    @Test fun stoppedCallbackInsideRejectedStopWaitsForExplicitRetryAndEndsOnce() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        every { anyConstructed<AidlMaaCoreClient>().stop() } answers {
            callback(AsstMsg.TaskChainStopped.value, """{"taskid":1}""")
            false
        }
        assertEquals(MaaCompositionService.StopResult.Failed, composition.stop())
        composition.stopVirtualDisplay()
        assertEquals(MaaExecutionState.RUNNING, composition.state.value)
        assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) {
            callbacks.onEvent(AsstMsg.TaskChainStopped.value, any(), any())
            logger.endSession(any())
            notifications.notifyTaskStopped()
            remote.stopVirtualDisplay()
        }
        every { anyConstructed<AidlMaaCoreClient>().stop() } answers { nativeRunning = false; true }
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        // Queue clearing, rejected stop, explicit retry. No event-driven extra Stop.
        verify(exactly = 3) { anyConstructed<AidlMaaCoreClient>().stop() }
        verify(exactly = 1) {
            callbacks.onEvent(AsstMsg.TaskChainStopped.value, any(), any())
            logger.endSession("STOPPED")
            notifications.notifyTaskStopped()
        }
    }

    @Test fun initFailedWithoutAllTasksFinishedStillWaitsForStopBeforeNotifying() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        stopAccepted = false
        callback(AsstMsg.InitFailed.value, """{"what":"Connect","why":"failed"}""")
        verify(timeout = 5_000) { logger.append("string:${R.string.runlog_task_stop_failed}", LogLevel.ERROR) }
        assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) { callbacks.onEvent(AsstMsg.InitFailed.value, any(), any()); logger.endSession(any()); notifications.notifyStartFailed(any()) }
        stopAccepted = true
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        assertEquals(MaaExecutionState.ERROR, composition.state.value)
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 1) { logger.endSession("INIT_FAILED"); notifications.notifyStartFailed(any()) }
        verify(exactly = 0) { notifications.notifyTaskStopped(); remote.stopVirtualDisplay() }
    }

    @Test fun runningServiceDeathReleasesOnlyItsRunWithoutCallingDeadNativeOrClosingDisplay() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        val oldCallback = callback
        die()
        withTimeout(5_000) { composition.state.first { it == MaaExecutionState.ERROR } }
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        coVerify(exactly = 1) { logger.completeSessionAndWait("SERVICE_DIED", any(), LogLevel.ERROR) }
        verify(exactly = 1) { notifications.notifyServiceDied(); anyConstructed<AidlMaaCoreClient>().stop() }
        verify(exactly = 0) { notifications.notifyTaskStopped(); remote.stopVirtualDisplay() }
        oldCallback(AsstMsg.AllTasksCompleted.value, "{}")
        verify(exactly = 0) { callbacks.onEvent(AsstMsg.AllTasksCompleted.value, any(), any()) }
    }

    @Test fun serviceDeathDuringConnectDoesNotPublishGenericStartupFailure() = runBlocking {
        every { anyConstructed<AidlMaaCoreClient>().asyncConnect(any()) } answers { die(); 1 }
        assertTrue(start() is MaaCompositionService.StartResult.ConnectionError)
        assertEquals(MaaExecutionState.ERROR, composition.state.value)
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        coVerify(exactly = 1) { logger.completeSessionAndWait("SERVICE_DIED", any(), LogLevel.ERROR) }
        verify(exactly = 1) { notifications.notifyServiceDied(); anyConstructed<AidlMaaCoreClient>().stop() }
        verify(exactly = 0) { notifications.notifyStartFailed(any()); notifications.notifyTaskStopped(); anyConstructed<AidlMaaCoreClient>().start() }
    }

    @Test fun deathInsideStopKeepsServiceDeathResultAndDoesNotClaimManualStop() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        every { anyConstructed<AidlMaaCoreClient>().stop() } answers { die(); error("dead Binder") }
        assertEquals(MaaCompositionService.StopResult.Failed, composition.stop())
        assertEquals(MaaExecutionState.ERROR, composition.state.value)
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        coVerify(exactly = 1) { logger.completeSessionAndWait("SERVICE_DIED", any(), LogLevel.ERROR) }
        verify(exactly = 1) { notifications.notifyServiceDied() }
        verify(exactly = 0) { logger.endSession("STOPPED"); notifications.notifyTaskStopped(); remote.stopVirtualDisplay() }
    }

    @Test fun oldDeathRecipientAndDelayedGlobalEventCannotInvalidateNewLiveRun() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        val oldDeath = deathRecipients.last()
        die()
        withTimeout(5_000) { composition.state.first { it == MaaExecutionState.ERROR } }
        serviceAlive = true
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        oldDeath.binderDied()
        val checkedCurrentBinder = CountDownLatch(1)
        every { serviceBinder.isBinderAlive } answers { checkedCurrentBinder.countDown(); true }
        withTimeout(5_000) {
            serviceDeaths.subscriptionCount.first { it > 0 }
            serviceDeaths.emit(Unit)
        }
        assertTrue(checkedCurrentBinder.await(5, TimeUnit.SECONDS))
        assertEquals(MaaExecutionState.RUNNING, composition.state.value)
        assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 1) { notifications.notifyServiceDied() }
        verify(exactly = 2) { anyConstructed<AidlMaaCoreClient>().stop() }
    }

    @Test fun callbackAlreadyInFlightCannotChangeOrStopTheNextRun() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        val oldCallback = callback
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        every { callbacks.onEvent(AsstMsg.TaskChainStart.value, any(), any()) } answers {
            val writeParams = thirdArg<(Int, String) -> Boolean>()
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            composition.reportRunState(MaaExecutionState.ERROR)
            composition.requestStopFromCallback()
            assertFalse(writeParams(1, "{}"))
        }
        val callbackJob = async(Dispatchers.IO) {
            oldCallback(AsstMsg.TaskChainStart.value, """{"taskid":1}""")
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
            assertTrue(start() is MaaCompositionService.StartResult.Success)
        } finally { resume.countDown() }
        withTimeout(5_000) { callbackJob.await() }
        assertEquals(MaaExecutionState.RUNNING, composition.state.value)
        assertEquals(EngineIds.ARKNIGHTS, EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) { anyConstructed<AidlMaaCoreClient>().setTaskParams(any(), any()) }
    }

    @Test fun dirtyResourceFailureIsNotSwallowedBeforeEnginePreparation() = runBlocking {
        coEvery { activity.runIfDirty(any()) } coAnswers { firstArg<suspend () -> Unit>()() }
        coEvery { resources.load("Official") } returns Result.failure(IllegalStateException("dirty reload failed"))
        assertTrue(start() is MaaCompositionService.StartResult.ResourceError)
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        coVerify(exactly = 1) { activity.runIfDirty(any()); resources.load("Official") }
        coVerify(exactly = 0) { resources.ensureLoaded(any()); pusher.pushUserData() }
        verify(exactly = 0) { remote.startVirtualDisplay(); anyConstructed<AidlMaaCoreClient>().createInstance(any()) }
    }

    @Test fun initFailedDuringSuccessfulConnectPreventsTaskStart() = runBlocking {
        every { anyConstructed<AidlMaaCoreClient>().asyncConnect(any()) } answers {
            callback(AsstMsg.AsyncCallInfo.value, """{"what":"Connect","async_call_id":1,"details":{"ret":true}}""")
            callback(AsstMsg.InitFailed.value, "{}")
            1
        }
        assertTrue(start() is MaaCompositionService.StartResult.ConnectionError)
        assertEquals(MaaExecutionState.ERROR, composition.state.value)
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) { anyConstructed<AidlMaaCoreClient>().appendTask(any(), any()); anyConstructed<AidlMaaCoreClient>().start() }
        verify(exactly = 1) { logger.endSession("INIT_FAILED"); notifications.notifyStartFailed(any()) }
    }

    @Test fun unsuccessfulCompletionRetainsErrorAcrossFailedStopAndRetry() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        stopAccepted = false
        callback(AsstMsg.TaskChainError.value, """{"taskid":1}""")
        callback(AsstMsg.AllTasksCompleted.value, "{}")
        verify(timeout = 5_000) { logger.append("string:${R.string.runlog_task_stop_failed}", LogLevel.ERROR) }
        stopAccepted = true
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        assertEquals(MaaExecutionState.ERROR, composition.state.value)
        assertNull(EngineExecutionCoordinator.shared.activeEngineId.value)
        verify(exactly = 0) { notifications.notifyTaskStopped() }
    }
}
