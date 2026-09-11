package com.maadroid.app.engine.limbus

import com.maadroid.app.engine.EngineEvent
import com.maadroid.app.engine.TaskPhase
import com.maadroid.app.engine.limbus.action.ActionBackend
import com.maadroid.app.engine.limbus.action.ActionContext
import com.maadroid.app.engine.limbus.action.ActionOutcome
import com.maadroid.app.engine.limbus.action.ActionRegistry
import com.maadroid.app.engine.limbus.action.FakeInput
import com.maadroid.app.engine.limbus.action.FakeRecognizer
import com.maadroid.app.engine.limbus.action.LimbusActions
import com.maadroid.app.engine.limbus.action.TestActionContext
import com.maadroid.app.engine.limbus.fixtures.LalcV500Fixtures
import com.maadroid.app.engine.limbus.pipeline.NodeRecognizer
import com.maadroid.app.engine.limbus.pipeline.PipelineRegistry
import com.maadroid.app.engine.limbus.recognize.Match
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real upstream routes and check_out_update; only game/battle boundaries are faked. */
class LimbusTaskRunTest {
    @Before fun installActions() {
        ActionRegistry.clearForTest()
        LimbusActions.resetForTest()
        LimbusActions.install()
    }

    @Test fun taskAliasStartsOnceAndCompletionWaitsForAllThreeChecks() = runTest {
        val rig = Rig(mapOf(17 to LimbusTask.EXP), count = 3)
        val battles = mutableListOf<Int>()
        action("exp_select_stage") { ctx ->
            battles += ctx.counterOf("exp_check")
            assertEquals(listOf(EngineEvent.Task(17, "exp", TaskPhase.Started)), rig.taskEvents())
            ActionOutcome.Goto("exp_check")
        }
        action("key") { ctx ->
            assertEquals("exp_quit", ctx.nodeName)
            assertEquals(3, ctx.counterOf("exp_check"))
            assertEquals(TaskPhase.Completed, rig.taskEvents().last().phase)
            ActionOutcome.Finish(true)
        }

        assertTrue(rig.run.execute("exp_entry"))

        assertEquals(listOf(0, 1, 2), battles)
        assertEquals(listOf(EngineEvent.Task(17, "exp", TaskPhase.Started),
            EngineEvent.Task(17, "exp", TaskPhase.Completed)), rig.events)
    }

    @Test fun successfulActionFinishWithoutTaskCheckIsNotTaskCompletion() = runTest {
        val rig = Rig(mapOf(28 to LimbusTask.EXP))
        action("exp_select_stage") { ActionOutcome.Finish(true) }

        assertFalse(rig.run.execute("exp_entry"))

        assertEquals(listOf(TaskPhase.Started, TaskPhase.Failed), rig.taskEvents().map { it.phase })
        assertTrue(rig.failure().reason.contains("未达到完成条件"))
        assertTrue(rig.failure().reason.contains("exp_select_stage"))
    }

    @Test fun naturalEndWithUnvisitedTasksReportsNoStartsOrCompletions() = runTest {
        val rig = Rig(linkedMapOf(4 to LimbusTask.EXP, 9 to LimbusTask.THREAD))

        assertFalse(rig.run.execute("end"))

        assertEquals(listOf(4, 9), rig.taskEvents().map { it.taskId })
        assertTrue(rig.taskEvents().all { it.phase == TaskPhase.Stopped && it.message!!.startsWith("未开始：") })
        assertTrue(rig.failure().reason.contains("exp, thread"))
    }

    @Test fun loginFailureBelongsToTheRunAndDoesNotPretendEveryTaskStarted() = runTest {
        val rig = Rig(linkedMapOf(15 to LimbusTask.MAIL, 23 to LimbusTask.EXP))
        action("init_limbus_window") { ActionOutcome.Finish(false, "无法登录游戏") }

        assertFalse(rig.run.execute())

        assertTrue(rig.taskEvents().all { it.phase == TaskPhase.Stopped })
        assertTrue(rig.failure().reason.contains("无法登录游戏"))
    }

    @Test fun exceptionInTheRunningTaskDoesNotFailAPendingTask() = runTest {
        val rig = Rig(linkedMapOf(31 to LimbusTask.EXP, 32 to LimbusTask.THREAD))
        val failure = IllegalStateException("OCR unavailable")
        action("exp_select_stage") { throw failure }

        assertFalse(rig.run.execute("exp_entry"))

        assertEquals(listOf(TaskPhase.Started, TaskPhase.Failed, TaskPhase.Stopped), rig.taskEvents().map { it.phase })
        assertSame(failure, rig.failure().cause)
    }

    @Test fun externalStopBeforeTheFirstActionDoesNotTouchTheGame() = runTest {
        val rig = Rig(mapOf(18 to LimbusTask.EXP), stopRequested = { true })

        assertFalse(rig.run.execute())

        assertEquals(listOf(TaskPhase.Stopped), rig.taskEvents().map { it.phase })
        assertTrue(rig.input.events.isEmpty())
        assertTrue(rig.counters.isEmpty())
        assertTrue(rig.events.none { it is EngineEvent.Failure })
    }

    @Test fun runnerStopIsStoppedRatherThanFailed() = runTest {
        val rig = Rig(mapOf(61 to LimbusTask.EXP))
        action("exp_select_stage") {
            rig.run.stop()
            ActionOutcome.Continue
        }

        assertFalse(rig.run.execute("exp_entry"))

        assertEquals(listOf(TaskPhase.Started, TaskPhase.Stopped), rig.taskEvents().map { it.phase })
        assertTrue(rig.events.none { it is EngineEvent.Failure })
    }

    @Test fun coroutineCancellationPropagatesAndStopsUnfinishedTasks() = runTest {
        val rig = Rig(linkedMapOf(7 to LimbusTask.EXP, 8 to LimbusTask.THREAD))
        val running = CompletableDeferred<Unit>()
        action("exp_select_stage") {
            running.complete(Unit)
            awaitCancellation()
        }
        var returnedNormally = false
        val job = launch {
            rig.run.execute("exp_entry")
            returnedNormally = true
        }
        running.await()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(returnedNormally)
        assertEquals(listOf(TaskPhase.Started, TaskPhase.Stopped, TaskPhase.Stopped), rig.taskEvents().map { it.phase })
        assertTrue(rig.events.none { it is EngineEvent.Failure })
    }

    @Test fun zeroCountCannotTurnASelectedTaskIntoASuccessfulNoOp() = runTest {
        val rig = Rig(mapOf(55 to LimbusTask.EXP), count = 0)

        assertFalse(rig.run.execute())

        assertEquals(listOf(TaskPhase.Stopped), rig.taskEvents().map { it.phase })
        assertTrue(rig.input.events.isEmpty())
        assertTrue(rig.counters.isEmpty())
        assertTrue(rig.failure().reason.contains("exp"))
    }

    @Test fun duplicateTypesAreRejectedInsteadOfCompletingTwoIdsForOneRun() = runTest {
        val rig = Rig(linkedMapOf(41 to LimbusTask.EXP, 42 to LimbusTask.EXP))

        assertFalse(rig.run.execute())

        assertEquals(listOf(41, 42), rig.taskEvents().map { it.taskId })
        assertTrue(rig.taskEvents().all { it.phase == TaskPhase.Stopped })
        assertTrue(rig.failure().reason.contains("同类任务只能追加一次"))
        assertTrue(rig.input.events.isEmpty())
    }

    @Test fun retainedMirrorSettlementCanCompleteWithoutRevisitingTheMirrorEntry() = runTest {
        val rig = Rig(mapOf(88 to LimbusTask.MIRROR))
        rig.recognize.onTemplate("exploration_complete", Match(640, 360, .95))
        // Settlement UI has its own action coverage. Its upstream next=mirror_check and
        // recovery's Goto(mirror_defeat), including the check action/counter, stay real.
        action("mirror_defeat") { ActionOutcome.Continue }
        action("enter_enkephalin") { ActionOutcome.Finish(true) }

        assertTrue(rig.run.execute("back_to_init_page"))

        assertEquals(1, rig.counters["mirror_check"])
        assertTrue(rig.logs.none { "节点 mirror_entry" in it })
        assertEquals(listOf(EngineEvent.Task(88, "mirror", TaskPhase.Started),
            EngineEvent.Task(88, "mirror", TaskPhase.Completed)), rig.events)
    }

    private fun action(name: String, execute: suspend (ActionContext) -> ActionOutcome) {
        ActionRegistry.register(name, object : ActionBackend {
            override suspend fun execute(ctx: ActionContext) = execute.invoke(ctx)
        })
    }

    private class Rig(selected: Map<Int, LimbusTask>, count: Int = 1, stopRequested: () -> Boolean = { false }) {
        val counters = mutableMapOf<String, Int>()
        val events = mutableListOf<EngineEvent>()
        val logs = mutableListOf<String>()
        val input = FakeInput()
        val recognize = FakeRecognizer().apply {
            onTemplate("main_drive_no_text", Match(978, 649, .95))
            onTemplate("main_drive_with_text", Match(978, 649, .95))
            onTemplate("main_window_no_text", Match(825, 647, .95))
            onTemplate("inferno", Match(1136, 148, .95))
            onTemplate("luxcavation", Match(640, 100, .95))
        }
        private val registry = PipelineRegistry.load(LalcV500Fixtures.taskFiles())
            .withEnabled(LimbusTask.allNodeNames().associateWith { node -> selected.values.any { it.nodeName == node } })
            .withAndroidMailEntry()
            .withTargetCounts(mapOf("exp_check" to count, "thread_check" to count, "mirror_check" to count))
        private val gate = NodeRecognizer(recognize)
        val run = LimbusTaskRun(
            registry, selected,
            contextFactory = { name, node, matches ->
                object : ActionContext by TestActionContext(node, name, input, recognize, recognizeResult = matches) {
                    override fun counterOf(nodeName: String) = counters[nodeName] ?: 0
                    override fun incrementCounter(nodeName: String) =
                        ((counters[nodeName] ?: 0) + 1).also { counters[nodeName] = it }
                }
            },
            recognizeGate = gate::recognize,
            emit = events::add,
            isStopRequested = stopRequested,
            onLog = logs::add,
        ).also { it.pipeline.delayer = {} }

        fun taskEvents() = events.filterIsInstance<EngineEvent.Task>()
        fun failure() = events.filterIsInstance<EngineEvent.Failure>().single()
    }
}
