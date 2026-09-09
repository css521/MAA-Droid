package com.aliothmoon.maadroid.domain.service

import android.content.Context
import android.os.IBinder
import com.aliothmoon.maadroid.MaaCoreService
import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.constant.DefaultDisplayConfig
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.data.preferences.TaskChainState
import com.aliothmoon.maadroid.data.model.LogLevel
import com.aliothmoon.maadroid.data.resource.ActivityManager
import com.aliothmoon.maadroid.domain.models.RunMode
import com.aliothmoon.maadroid.engine.EngineExecutionCoordinator
import com.aliothmoon.maadroid.engine.arknights.core.AidlMaaCoreClient
import com.aliothmoon.maadroid.engine.arknights.core.AsstMsg
import com.aliothmoon.maadroid.engine.arknights.state.MaaExecutionState
import com.aliothmoon.maadroid.maa.task.MaaTaskParams
import com.aliothmoon.maadroid.maa.task.MaaTaskType
import com.aliothmoon.maadroid.manager.RemoteAccessCoordinator
import com.aliothmoon.maadroid.manager.RemoteAccessState
import com.aliothmoon.maadroid.manager.RemoteServiceManager
import com.aliothmoon.maadroid.manager.ShizukuManager
import com.aliothmoon.maadroid.manager.RootManager
import com.aliothmoon.maadroid.remote.EngineIds
import io.mockk.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Run the real public start/stop path with the Binder boundary replaced, including host admission. */
class MaaCompositionLifecycleTest {
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
    private val remote = mockk<RemoteService>(relaxed = true)
    private val maa = mockk<MaaCoreService>(relaxed = true)
    private val binder = mockk<IBinder>()
    private lateinit var composition: MaaCompositionService
    private var instance = false
    private var nativeRunning = false
    private var stopAccepted = true
    private var stopThrows = false
    private var rejectAppend = false
    private var createCount = 0
    private var callback: (Int, String?) -> Unit = { _, _ -> }

    @Before fun setUp() {
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
        every { dispatcher.serviceDiedEvent } returns MutableSharedFlow()
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
        composition = MaaCompositionService(
            context, resources, settings, mockk(relaxed = true), dispatcher, logger, activity,
            watchdog, fps, chain, mockk(relaxed = true), mockk(relaxed = true),
            notifications, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), pusher,
        )
    }

    @After fun tearDown() {
        stopAccepted = true
        stopThrows = false
        try {
            if (::composition.isInitialized) runBlocking { composition.stop() }
        } finally {
            unmockkConstructor(AidlMaaCoreClient::class)
            unmockkObject(RemoteServiceManager, RemoteAccessCoordinator, TaskExecutionService.Companion)
            unmockkObject(ShizukuManager, RootManager)
        }
    }

    private suspend fun start() = composition.start(listOf(MaaTaskParams(MaaTaskType.FIGHT, "{}")), "Official")

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
        coEvery { pusher.pushUserData() } returns false
        stopAccepted = false
        assertTrue(start() is MaaCompositionService.StartResult.ResourceError)
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

    @Test fun repeatedRunsReuseOnlyTheSameLiveBinderSession() = runBlocking {
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        assertEquals(1, createCount)
        assertEquals(MaaCompositionService.StopResult.Success, composition.stop())
        val replacement = mockk<IBinder> { every { queryLocalInterface(any()) } returns maa }
        every { remote.getEngineService(EngineIds.ARKNIGHTS) } returns replacement
        every { maa.asBinder() } returns replacement
        assertTrue(start() is MaaCompositionService.StartResult.Success)
        assertEquals(2, createCount)
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
}
