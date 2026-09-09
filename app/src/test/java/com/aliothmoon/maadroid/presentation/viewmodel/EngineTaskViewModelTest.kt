package com.aliothmoon.maadroid.presentation.viewmodel

import androidx.lifecycle.ViewModel
import android.view.Surface
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.aliothmoon.maadroid.engine.EngineEvent
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
        every { events() } returns events
        every { previewReady } returns deviceReady
        every { appendTask(any(), any()) } returns 1
    }

    private fun model(
        id: String = "limbus",
        canStart: () -> Boolean = { true },
        factory: (String) -> EngineSession = { session },
    ) = EngineTaskViewModel(id, store, factory, executionState, canStart, scope, dispatcher)

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
        coVerify(exactly = 1) { session.stop() }
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
        coEvery { session.stop() } returns false
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
        coEvery { session.close() } throws IllegalStateException("cleanup failed")
        val model = model()
        model.start()
        dispatcher.runCurrent()
        events.tryEmit(EngineEvent.AllTasksFinished(true))
        dispatcher.runCurrent()

        assertFalse(model.running.value)
        assertNull(executionState.activeEngineId.value)
        assertEquals(0, events.subscriptionCount.value)
        assertTrue(model.status.value.toString().contains("cleanup failed"))
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

    /** 可控地延后 launch，覆盖连续点击早于协程执行的竞态；不依赖 Android Main looper。 */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runCurrent() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }
}
