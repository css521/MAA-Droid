package com.aliothmoon.maadroid.presentation.viewmodel

import androidx.lifecycle.ViewModel
import android.view.Surface
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.aliothmoon.maadroid.engine.EngineEvent
import com.aliothmoon.maadroid.engine.EngineDeviceSession
import com.aliothmoon.maadroid.engine.EngineSession
import com.aliothmoon.maadroid.engine.EngineTaskStore
import com.aliothmoon.maadroid.presentation.state.EngineTaskExecutionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class EngineTaskViewModelTest {
    private val dispatcher = QueuedDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val executionState = EngineTaskExecutionState()
    private val store = mockk<EngineTaskStore> {
        every { flow(any()) } returns MutableStateFlow(EngineTaskStore.EngineTasks())
        coEvery { selectedTasks(any()) } returns listOf("task" to "{}")
        coEvery { current(any()) } returns EngineTaskStore.EngineTasks(workspaceConfig = """{"taskConfigs":{"EXP":{"enabled":false},"Thread":{"enabled":false},"Mirror":{"enabled":false}},"teamConfigs":{}}""")
        coEvery { setWorkspaceConfig(any(), any()) } returns Unit
    }
    private val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
    private val deviceReady = MutableStateFlow(false)
    private val session = mockk<EngineSession>(relaxed = true) {
        coEvery { prepare() } returns null
        coEvery { start() } returns true
        coEvery { stop() } returns true
        coEvery { finishTask() } returns true
        every { events() } returns events
        every { previewReady } returns deviceReady
        every { gamePackageName } returns "com.ProjectMoon.LimbusCompany"
        every { appendTask(any(), any()) } returns 1
    }

    private fun model(
        id: String = "limbus",
        canStart: () -> Boolean = { true },
        factory: (String) -> EngineSession = { session },
        quickActions: EngineTaskQuickActions? = null,
    ) = EngineTaskViewModel(id, store, factory, executionState, canStart, scope, dispatcher, quickActions)

    @After
    fun tearDown() {
        scope.cancel()
        dispatcher.runCurrent()
    }

    @Test
    fun rapidStartClicksPrepareAndStartOnlyOnce() {
        val model = model()
        model.start()
        model.start()
        assertTrue(model.running.value)
        dispatcher.runCurrent()

        coVerify(exactly = 1) { session.prepare() }
        coVerify(exactly = 1) { session.start() }
        verify(exactly = 1) { session.appendTask("task", "{}") }
    }

    @Test
    fun stoppingDuringPreparationNeverStartsTheCancelledRun() {
        val prepared = CompletableDeferred<Unit>()
        coEvery { session.prepare() } coAnswers { prepared.await(); null }
        val model = model()
        model.start()
        dispatcher.runCurrent()
        model.stop()
        model.stop()
        dispatcher.runCurrent()
        prepared.complete(Unit)
        dispatcher.runCurrent()

        coVerify(exactly = 0) { session.start() }
        verify(exactly = 0) { session.appendTask(any(), any()) }
        coVerify(exactly = 1) { session.finishTask() }
        assertFalse(model.running.value)
        assertNull(executionState.activeEngineId.value)
    }

    @Test
    fun anotherEngineCannotAcquireTheDeviceUntilTheCurrentOneStops() {
        val first = model("first")
        val secondFactory = mockk<(String) -> EngineSession>()
        every { secondFactory.invoke(any()) } returns session
        val second = model("second", factory = secondFactory)
        first.start()
        second.start()
        dispatcher.runCurrent()

        assertTrue(first.running.value)
        assertFalse(second.running.value)
        verify(exactly = 0) { secondFactory.invoke(any()) }
        coVerify(exactly = 0) { session.stop() }

        first.stop()
        dispatcher.runCurrent()
        second.start()
        dispatcher.runCurrent()
        assertTrue(second.running.value)
        assertEquals("second", executionState.activeEngineId.value)
    }

    @Test
    fun arknightsBusyGuardDoesNotCreateOrStopAnEngineSession() {
        val factory = mockk<(String) -> EngineSession>()
        val model = model(canStart = { false }, factory = factory)
        model.start()
        dispatcher.runCurrent()

        assertFalse(model.running.value)
        assertNull(executionState.activeEngineId.value)
        verify(exactly = 0) { factory.invoke(any()) }
        coVerify(exactly = 0) { session.stop() }
    }

    @Test
    fun failedStopKeepsTheDeviceReserved() {
        val model = model()
        model.start()
        dispatcher.runCurrent()
        coEvery { session.finishTask() } returns false
        model.stop()
        dispatcher.runCurrent()

        assertTrue(model.running.value)
        assertFalse(model.stopping.value)
        assertEquals("limbus", executionState.activeEngineId.value)
        coVerify(exactly = 0) { session.close() }
    }

    @Test
    fun finishedRunsDoNotAccumulateEventSubscriptions() {
        val model = model()
        repeat(2) {
            model.start()
            dispatcher.runCurrent()
            assertEquals(1, events.subscriptionCount.value)
            assertTrue(events.tryEmit(EngineEvent.AllTasksFinished(success = true)))
            dispatcher.runCurrent()
            assertFalse(model.running.value)
            assertEquals(0, events.subscriptionCount.value)
            assertNull(executionState.activeEngineId.value)
        }
        coVerify(exactly = 2) { session.start() }
        coVerify(exactly = 0) { session.stop() }
    }

    @Test
    fun returningToTheSameEngineReusesItsRunningViewModel() {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = model() as T
        }
        try {
            val provider = ViewModelProvider(owner, factory)
            val original = provider["engine_task:limbus", EngineTaskViewModel::class.java]
            original.onToggleExpand("task")
            original.start()
            dispatcher.runCurrent()
            val restored = ViewModelProvider(owner, factory)["engine_task:limbus", EngineTaskViewModel::class.java]

            assertSame(original, restored)
            assertTrue(restored.running.value)
            assertEquals("task", restored.expandedTaskType.value)
            coVerify(exactly = 1) { session.start() }
            coVerify(exactly = 0) { session.stop() }
            coVerify(exactly = 0) { session.close() }
        } finally {
            owner.viewModelStore.clear()
        }
    }

    @Test
    fun identicalTaskTypesKeepSeparateEngineConfigurationAndExpansion() {
        val firstTasks = EngineTaskStore.EngineTasks(params = mapOf("task" to "first"))
        val secondTasks = EngineTaskStore.EngineTasks(params = mapOf("task" to "second"))
        every { store.flow("first") } returns MutableStateFlow(firstTasks)
        every { store.flow("second") } returns MutableStateFlow(secondTasks)
        val first = model("first")
        val second = model("second")
        dispatcher.runCurrent()
        first.onToggleExpand("task")

        assertEquals(firstTasks, first.tasks.value)
        assertEquals(secondTasks, second.tasks.value)
        assertEquals("task", first.expandedTaskType.value)
        assertNull(second.expandedTaskType.value)
    }

    @Test
    fun nativeLinkageFailureBecomesStatusAndReleasesReservation() {
        coEvery { session.prepare() } throws UnsatisfiedLinkError("missing native symbol")
        val model = model()
        model.start()
        dispatcher.runCurrent()

        assertFalse(model.running.value)
        assertNull(executionState.activeEngineId.value)
        assertTrue(model.status.value.toString().contains("missing native symbol"))
        coVerify(exactly = 1) { session.close() }
        coVerify(exactly = 0) { session.start() }
    }

    @Test
    fun cleanupFailureOnCompletedRunDoesNotCrashEventCollector() {
        coEvery { session.finishTask() } throws IllegalStateException("cleanup failed")
        val model = model()
        model.start()
        dispatcher.runCurrent()
        events.tryEmit(EngineEvent.AllTasksFinished(true))
        dispatcher.runCurrent()

        assertTrue(model.running.value)
        assertEquals("limbus", executionState.activeEngineId.value)
        assertEquals(1, events.subscriptionCount.value)
        assertTrue(model.status.value.toString().contains("cleanup failed"))
        assertTrue(model.diagnosticFailure.value.toString().contains("cleanup failed"))
        coEvery { session.finishTask() } returns true
        model.stop()
        dispatcher.runCurrent()
        assertFalse(model.running.value)
        assertNull(executionState.activeEngineId.value)
        assertEquals(0, events.subscriptionCount.value)
    }

    @Test
    fun completionAndUserStopKeepPreviewAndManualInputWhileReleasingTaskAdmission() {
        for (naturalCompletion in listOf(true, false)) {
            val manual = mockk<EngineDeviceSession.ManualInput>(relaxed = true)
            every { session.openManualInput() } returns manual
            val model = model()
            model.start()
            dispatcher.runCurrent()
            deviceReady.value = true
            dispatcher.runCurrent()
            if (naturalCompletion) events.tryEmit(EngineEvent.AllTasksFinished(true)) else model.stop()
            dispatcher.runCurrent()
            assertFalse(model.running.value)
            assertTrue(model.previewReady.value)
            assertNull(executionState.activeEngineId.value)
            val input = model.openPreviewInput()!!
            input.touchDown(500, 400, 8)
            input.touchUp(500, 400, 8)
            dispatcher.runCurrent()
            verify { manual.touchDown(500, 400, 8); manual.touchUp(500, 400, 8) }
            coVerify(exactly = 0) { session.close() }
            deviceReady.value = false // A later game's handoff updates the old preview observer.
            dispatcher.runCurrent()
            assertFalse(model.previewReady.value)
            assertNull(model.openPreviewInput())
        }
    }

    private fun quickActions(closeOnEnd: Boolean = false) = mockk<EngineTaskQuickActions> {
        every { mutedPackage } returns MutableStateFlow("")
        every { closeOnTaskEnd } returns closeOnEnd
        coEvery { onGameReady(any()) } returns true
        coEvery { toggleGameSound(any()) } returns true
        coEvery { onGameClosed(any()) } returns true
        every { turnScreenOff() } returns Unit
    }

    @Test
    fun failedReplacementPreparationRestoresPreviousPreviewAndItsSubscription() {
        val manual = mockk<EngineDeviceSession.ManualInput>(relaxed = true)
        every { session.openManualInput() } returns manual
        var next = session
        val model = model(factory = { next })
        val surface = mockk<Surface>()
        model.onPreviewSurfaceAvailable(surface)
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        model.stop()
        dispatcher.runCurrent()
        val failed = mockk<EngineSession>(relaxed = true) {
            every { previewReady } returns MutableStateFlow(false)
            every { events() } returns MutableSharedFlow()
            coEvery { prepare() } returns "resource preparation failed"
        }
        next = failed
        model.start()
        dispatcher.runCurrent()
        assertFalse(model.running.value)
        assertTrue(model.previewReady.value)
        assertTrue(model.status.value.toString().contains("resource preparation failed"))
        coVerify(exactly = 0) { session.close() }
        coVerify(exactly = 1) { failed.close() }
        verify(atLeast = 2) { session.setPreviewSurface(surface) }
        model.openPreviewInput()!!.touchDown(3, 4, 8)
        dispatcher.runCurrent()
        verify { manual.touchDown(3, 4, 8) }
        deviceReady.value = false
        dispatcher.runCurrent()
        assertFalse(model.previewReady.value)
    }

    @Test
    fun cancellingReplacementBeforeTransferKeepsTheOldPlayablePreview() {
        var next = session
        val model = model(factory = { next })
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        model.stop()
        dispatcher.runCurrent()
        val pending = mockk<EngineSession>(relaxed = true) {
            every { previewReady } returns MutableStateFlow(false)
            every { events() } returns MutableSharedFlow()
            coEvery { prepare() } coAnswers { CompletableDeferred<Unit>().await(); null }
        }
        next = pending
        model.start()
        dispatcher.runCurrent()
        model.stop()
        dispatcher.runCurrent()
        assertFalse(model.running.value)
        assertTrue(model.previewReady.value)
        coVerify(exactly = 0) { session.close() }
        coVerify(exactly = 0) { pending.start() }
    }

    @Test
    fun failedPrepareAfterTransferNeverRestoresTheConsumedPreview() {
        var next = session
        val actions = quickActions()
        val model = model(factory = { next }, quickActions = actions)
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        model.stop()
        dispatcher.runCurrent()
        next = mockk<EngineSession>(relaxed = true) {
            every { previewReady } returns MutableStateFlow(false)
            every { events() } returns MutableSharedFlow()
            every { gamePackageName } returns null
            coEvery { prepare() } coAnswers { deviceReady.value = false; "connect failed after transfer" }
        }
        model.start()
        dispatcher.runCurrent()
        assertFalse(model.previewReady.value)
        assertFalse(model.running.value)
        coVerify(exactly = 0) { session.close() }
        coVerify(exactly = 1) { actions.onGameClosed("com.ProjectMoon.LimbusCompany") }
    }

    @Test
    fun quickActionsUseTheActualPackageAndWorkOnRetainedPreview() {
        val actions = quickActions(closeOnEnd = true)
        val model = model(quickActions = actions)
        model.onToggleGameSound() // No game before preparation.
        dispatcher.runCurrent()
        coVerify(exactly = 0) { actions.toggleGameSound(any()) }
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        model.stop()
        dispatcher.runCurrent()
        coVerify(exactly = 0) { session.closeGame() } // Automatic-close setting is not user stop.
        model.onToggleGameSound()
        model.onScreenOff()
        dispatcher.runCurrent()
        coVerify { actions.onGameReady("com.ProjectMoon.LimbusCompany") }
        coVerify { actions.toggleGameSound("com.ProjectMoon.LimbusCompany") }
        verify { actions.turnScreenOff() }
        model.onCloseGame()
        dispatcher.runCurrent()
        coVerify(exactly = 1) { session.closeGame() }
        coVerify { actions.onGameClosed("com.ProjectMoon.LimbusCompany") }
        assertFalse(model.previewReady.value)
        assertFalse(model.stopping.value)
    }

    @Test
    fun closeGameFailureKeepsPreviewAndCanBeRetriedWithoutCrashHelp() {
        val model = model(quickActions = quickActions())
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        coEvery { session.closeGame() } throws IllegalStateException("close rejected")
        model.onCloseGame()
        dispatcher.runCurrent()
        assertTrue(model.previewReady.value)
        assertFalse(model.stopping.value)
        assertNull(model.diagnosticFailure.value)
        assertTrue(model.status.value.toString().contains("close rejected"))
        coVerify(exactly = 0) { session.close() }
        coEvery { session.closeGame() } returns Unit
        model.onCloseGame()
        dispatcher.runCurrent()
        assertFalse(model.running.value)
        assertFalse(model.previewReady.value)
    }

    @Test
    fun automaticCompletionUsesCloseGameOnlyWhenItsSettingIsEnabled() {
        val actions = quickActions()
        val model = model(quickActions = actions)
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        every { actions.closeOnTaskEnd } returns true // A setting changed during this run.
        events.tryEmit(EngineEvent.AllTasksFinished(true))
        dispatcher.runCurrent()
        coVerify(exactly = 1) { session.closeGame() }
        assertFalse(model.running.value)
        assertFalse(model.previewReady.value)
        assertNull(executionState.activeEngineId.value)
    }

    @Test
    fun failedAutomaticCloseFallsBackToPlayableStoppedPreview() {
        val model = model(quickActions = quickActions(closeOnEnd = true))
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        coEvery { session.closeGame() } throws IllegalStateException("close failed")
        events.tryEmit(EngineEvent.AllTasksFinished(true))
        dispatcher.runCurrent()
        assertTrue(model.previewReady.value)
        assertFalse(model.running.value)
        assertNull(model.diagnosticFailure.value)
        coVerify(exactly = 1) { session.finishTask() }
        coVerify(exactly = 0) { session.close() }
    }

    @Test
    fun fpsReadsTheRetainedSessionAndDoesNotInventAnUnavailableSample() {
        val model = model()
        var sample: Float? = -1f
        fun read() {
            scope.launch { sample = model.readGameFps() }
            dispatcher.runCurrent()
        }
        read()
        assertNull(sample)
        verify(exactly = 0) { session.readGameFps() }
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        model.stop()
        dispatcher.runCurrent()
        every { session.readGameFps() } returns 59.5f
        read()
        assertEquals(59.5f, sample)
        every { session.readGameFps() } returns -1f
        read()
        assertNull(sample)
        every { session.readGameFps() } throws kotlinx.coroutines.CancellationException("cancelled")
        val job = scope.launch { model.readGameFps() }
        dispatcher.runCurrent()
        assertTrue(job.isCancelled)
    }

    @Test
    fun warningsAndSuccessfulCompletionDoNotShowFailureHelpButFailuresDo() {
        val model = model()
        model.start()
        dispatcher.runCurrent()
        events.tryEmit(EngineEvent.Log(com.aliothmoon.maadroid.engine.LogLevel.Warn, "recognition recovered"))
        dispatcher.runCurrent()
        assertNull(model.diagnosticFailure.value)
        events.tryEmit(EngineEvent.Failure("recognition failed"))
        dispatcher.runCurrent()
        assertTrue(model.diagnosticFailure.value.toString().contains("recognition failed"))
        events.tryEmit(EngineEvent.AllTasksFinished(false))
        dispatcher.runCurrent()
        assertTrue(model.diagnosticFailure.value.toString().contains("recognition failed"))
        model.start()
        dispatcher.runCurrent()
        assertNull(model.diagnosticFailure.value)
        events.tryEmit(EngineEvent.AllTasksFinished(true))
        dispatcher.runCurrent()
        assertNull(model.diagnosticFailure.value)
    }

    @Test
    fun pipelineTaskFailureKeepsItsReasonAndErrorLevelAfterCompletion() {
        val model = model()
        model.start()
        dispatcher.runCurrent()
        val reason = "暂时无法识别主页导航"
        events.tryEmit(EngineEvent.Task(1, "exp", com.aliothmoon.maadroid.engine.TaskPhase.Failed, reason))
        events.tryEmit(EngineEvent.AllTasksFinished(false))
        dispatcher.runCurrent()
        assertTrue(model.diagnosticFailure.value.toString().contains(reason))
        assertTrue(model.logs.value.contains("[Error] exp: $reason"))
        assertFalse(model.running.value)
        coVerify(exactly = 1) { session.finishTask() }
    }

    @Test
    fun previewCanPrecedeStartAndTabDetachNeverStopsTheGame() {
        val surface = mockk<Surface>()
        val model = model()
        model.onPreviewSurfaceAvailable(surface)
        model.start()
        dispatcher.runCurrent()
        verify { session.setPreviewSurface(surface) }
        deviceReady.value = true
        dispatcher.runCurrent()
        assertTrue(model.previewReady.value)

        model.onPreviewSurfaceDestroyed(surface)
        dispatcher.runCurrent()
        verify { session.setPreviewSurface(null) }
        assertTrue(model.running.value)
        coVerify(exactly = 0) { session.stop() }
        coVerify(exactly = 0) { session.close() }
    }

    @Test
    fun collapsingAndReopeningPreviewKeepsTheSameRunningSession() {
        val model = model()
        // The task can start with the preview collapsed, before any Surface exists.
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()

        repeat(2) {
            val surface = mockk<Surface>()
            model.onPreviewSurfaceAvailable(surface)
            dispatcher.runCurrent()
            verify(exactly = 1) { session.setPreviewSurface(surface) }

            model.onPreviewSurfaceDestroyed(surface)
            dispatcher.runCurrent()
            verifyOrder {
                session.setPreviewSurface(surface)
                session.setPreviewSurface(null)
            }
            assertTrue(model.running.value)
            assertTrue(model.previewReady.value)
            assertEquals("limbus", executionState.activeEngineId.value)
        }

        val reopened = mockk<Surface>()
        model.onPreviewSurfaceAvailable(reopened)
        dispatcher.runCurrent()
        verify(exactly = 1) { session.setPreviewSurface(reopened) }
        coVerify(exactly = 1) { session.prepare() }
        coVerify(exactly = 1) { session.start() }
        verify(exactly = 1) { session.appendTask("task", "{}") }
        coVerify(exactly = 0) { session.stop() }
        coVerify(exactly = 0) { session.close() }
    }

    @Test
    fun staleSurfaceDisposalDoesNotDetachItsReplacement() {
        val old = mockk<Surface>()
        val next = mockk<Surface>()
        val model = model()
        model.onPreviewSurfaceAvailable(old)
        model.start()
        dispatcher.runCurrent()
        model.onPreviewSurfaceAvailable(next)
        model.onPreviewSurfaceDestroyed(old)
        dispatcher.runCurrent()
        verify { session.setPreviewSurface(next) }
        verify(exactly = 0) { session.setPreviewSurface(null) }
    }

    @Test
    fun previewInputIsUnavailableBeforePreparationAndWhileStopping() {
        val model = model()
        assertNull(model.openPreviewInput())
        model.start()
        dispatcher.runCurrent()
        assertNull(model.openPreviewInput())
        deviceReady.value = true
        dispatcher.runCurrent()
        model.stop()
        assertNull(model.openPreviewInput())
        verify(exactly = 0) { session.openManualInput() }
    }

    @Test
    fun manualInputEventsAreSerializedAndClosingDropsUnsentGestures() {
        val manual = mockk<EngineDeviceSession.ManualInput>(relaxed = true)
        every { session.openManualInput() } returns manual
        val model = model()
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        val input = model.openPreviewInput()!!
        input.touchDown(100, 200, 8)
        input.touchMove(200, 300, 8)
        input.touchUp(200, 300, 8)
        dispatcher.runCurrent()
        verifyOrder {
            manual.touchDown(100, 200, 8)
            manual.touchMove(200, 300, 8)
            manual.touchUp(200, 300, 8)
        }
        input.touchDown(300, 400, 9)
        input.close()
        input.touchDown(400, 500, 10)
        dispatcher.runCurrent()
        verify(exactly = 0) { manual.touchDown(any(), any(), 9) }
        verify(exactly = 0) { manual.touchDown(any(), any(), 10) }
        verify(atLeast = 1) { manual.close() }
        coVerify(exactly = 0) { session.stop() }
        coVerify(exactly = 0) { session.close() }
    }

    @Test
    fun previewControllerKeepsItsCapturedLeaseAcrossSessionReplacement() {
        val oldManual = mockk<EngineDeviceSession.ManualInput>(relaxed = true)
        val nextManual = mockk<EngineDeviceSession.ManualInput>(relaxed = true)
        val nextSession = mockk<EngineSession>(relaxed = true) {
            coEvery { prepare() } returns null
            coEvery { start() } returns true
            every { events() } returns MutableSharedFlow()
            every { previewReady } returns MutableStateFlow(true)
            every { appendTask(any(), any()) } returns 1
            every { openManualInput() } returns nextManual
        }
        every { session.openManualInput() } returns oldManual
        var factorySession = session
        val model = model(factory = { factorySession })
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        val oldInput = model.openPreviewInput()!!
        model.stop()
        dispatcher.runCurrent()
        factorySession = nextSession
        model.start()
        dispatcher.runCurrent()
        // The new session's prepare owns transfer; the VM must not discard the old game.
        coVerify(exactly = 0) { session.close() }
        val nextInput = model.openPreviewInput()!!
        nextInput.touchDown(100, 200, 8)
        oldInput.touchMove(1, 2, 8)
        oldInput.close()
        dispatcher.runCurrent()
        verify(exactly = 1) { nextManual.touchDown(100, 200, 8) }
        verify(exactly = 0) { nextManual.touchMove(any(), any(), any()) }
        verify(exactly = 0) { nextManual.close() }
        nextInput.close()
        dispatcher.runCurrent()
    }

    @Test
    fun cancellingTheViewModelBeforeTheInputWorkerRunsStillReleasesItsController() {
        val manual = mockk<EngineDeviceSession.ManualInput>(relaxed = true)
        every { session.openManualInput() } returns manual
        val model = model()
        model.start()
        dispatcher.runCurrent()
        deviceReady.value = true
        dispatcher.runCurrent()
        model.openPreviewInput()!!.touchDown(100, 200, 8)
        scope.cancel()
        dispatcher.runCurrent()
        verify(exactly = 0) { manual.touchDown(any(), any(), any()) }
        verify(atLeast = 1) { manual.close() }
    }

    /** 可控地延后 launch，覆盖连续点击早于协程执行的竞态；不依赖 Android Main looper。 */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runCurrent() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }
}
