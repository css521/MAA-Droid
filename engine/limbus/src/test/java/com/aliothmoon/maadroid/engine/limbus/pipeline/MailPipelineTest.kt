package com.aliothmoon.maadroid.engine.limbus.pipeline

import android.view.KeyEvent
import com.aliothmoon.maadroid.engine.InputSink
import com.aliothmoon.maadroid.engine.EngineEvent
import com.aliothmoon.maadroid.engine.TaskPhase
import com.aliothmoon.maadroid.engine.limbus.LimbusTask
import com.aliothmoon.maadroid.engine.limbus.LimbusTaskRun
import com.aliothmoon.maadroid.engine.limbus.StoppedException
import com.aliothmoon.maadroid.engine.limbus.action.ActionBackend
import com.aliothmoon.maadroid.engine.limbus.action.ActionContext
import com.aliothmoon.maadroid.engine.limbus.action.ActionOutcome
import com.aliothmoon.maadroid.engine.limbus.action.ActionRegistry
import com.aliothmoon.maadroid.engine.limbus.action.FakeInput
import com.aliothmoon.maadroid.engine.limbus.action.FakeRecognizer
import com.aliothmoon.maadroid.engine.limbus.action.LimbusActions
import com.aliothmoon.maadroid.engine.limbus.action.TestActionContext
import com.aliothmoon.maadroid.engine.limbus.config.LimbusWorkspaceConfig
import com.aliothmoon.maadroid.engine.limbus.fixtures.LalcV500Fixtures
import com.aliothmoon.maadroid.engine.limbus.recognize.Crop
import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import com.aliothmoon.maadroid.engine.limbus.recognize.MailboxObservation
import com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MailPipelineTest {
    @Before fun installActions() {
        ActionRegistry.clearForTest()
        LimbusActions.resetForTest()
        LimbusActions.install()
        // Energy conversion is independent of mail. Every mail action, including click,
        // key, waiting and check_out_update, still uses its production implementation.
        ActionRegistry.register("enter_enkephalin", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext) = ActionOutcome.Continue
        })
    }

    @Test fun selectedMailWithoutANotificationDotStillOpensClaimsAndCompletesOnce() = runTest {
        val mailbox = Mailbox(hasMail = true, hasNotification = false)
        val selected = selection(mail = true)
        assertEquals(listOf(LimbusTask.MAIL), selected)

        assertNull(mailbox.runner(selected).run(LimbusTask.ENTRY_NODE))

        assertEquals(1, mailbox.opened)
        assertEquals(1, mailbox.claimed)
        assertEquals(1, mailbox.counters["check_mail"])
        assertEquals(listOf(825 to 647, 1120 to 125, 950 to 270, 640 to 470, 776 to 551), mailbox.recorded.clicks())
        assertTrue(mailbox.recorded.keyPresses().isEmpty())
        assertEquals(View.HOME, mailbox.view)
        assertEquals(1, mailbox.logs.count { it == "节点 end 执行动作 empty" })
        assertTrue(mailbox.logs.contains("打开邮箱检查并领取邮件"))
    }

    @Test fun emptyMailboxExitsWithoutClaimingOrEnteringAnErrorLoop() = runTest {
        val mailbox = Mailbox(hasMail = false, hasNotification = false)

        assertNull(mailbox.runner(selection(mail = true)).run(LimbusTask.ENTRY_NODE))

        assertEquals(1, mailbox.opened)
        assertEquals(0, mailbox.claimed)
        assertEquals(1, mailbox.counters["check_mail"])
        assertEquals(listOf(825 to 647, 1120 to 125, 776 to 551), mailbox.recorded.clicks())
        assertTrue(mailbox.recorded.keyPresses().isEmpty())
        assertEquals(1, mailbox.logs.count { it == "节点 end 执行动作 empty" })
        assertFalse(mailbox.logs.any { it == "节点 claim_mail 执行动作 click" })
    }

    @Test fun disabledMailDoesNotOpenEvenWhenANotificationExists() = runTest {
        val mailbox = Mailbox(hasMail = true, hasNotification = true)

        assertNull(mailbox.runner(selection(mail = false)).run(LimbusTask.ENTRY_NODE))

        assertEquals(0, mailbox.opened)
        assertEquals(0, mailbox.claimed)
        assertNull(mailbox.counters["check_mail"])
        assertTrue(mailbox.recorded.events.isEmpty())
        assertEquals(1, mailbox.logs.count { it == "节点 end 执行动作 empty" })
    }

    @Test fun closeTapCannotCompleteMailUntilTheMailboxIsGoneAndHomeIsVisible() = runTest {
        for (afterClose in listOf(View.MAILBOX, View.PENDING)) {
            val mailbox = Mailbox(hasMail = false, hasNotification = false, afterClose = afterClose)

            assertEquals("邮箱仍未关闭或主页尚未就绪，请检查游戏画面后重试",
                mailbox.runner(selection(mail = true)).run(LimbusTask.ENTRY_NODE))

            assertEquals(1, mailbox.recorded.clicks().count { it == 776 to 551 })
            assertNull(mailbox.counters["check_mail"])
            assertFalse(mailbox.logs.any { it == "节点 end 执行动作 empty" })
            assertTrue(mailbox.recorded.keyPresses().isEmpty())
        }
    }

    @Test fun completedMailIsNotReenteredBeforeTheNextSelectedTask() = runTest {
        val mailbox = Mailbox(hasMail = true, hasNotification = true)
        var stageSelections = 0
        // Stop at the EXP boundary; stage OCR is covered by its own action tests.
        ActionRegistry.register("exp_select_stage", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome {
                stageSelections++
                assertEquals(1, ctx.counterOf("check_mail"))
                assertEquals(1, mailbox.opened)
                return ActionOutcome.Finish(true)
            }
        })

        assertNull(mailbox.runner(selection(mail = true, exp = true)).run(LimbusTask.ENTRY_NODE))

        assertEquals(1, stageSelections)
        assertEquals(1, mailbox.opened)
        assertEquals(1, mailbox.claimed)
        assertEquals(1, mailbox.counters["check_mail"])
    }

    @Test fun expFailureKeepsCompletedMailAndNeverStartsTheRemainingTasks() = runTest {
        val mailbox = Mailbox(hasMail = true, hasNotification = false)
        val events = mutableListOf<EngineEvent>()
        val selected = linkedMapOf(11 to LimbusTask.MAIL, 27 to LimbusTask.EXP,
            38 to LimbusTask.THREAD, 49 to LimbusTask.REWARD)
        ActionRegistry.register("exp_select_stage", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome {
                assertEquals(1, ctx.counterOf("check_mail"))
                assertEquals(listOf(
                    EngineEvent.Task(11, "mail", TaskPhase.Started),
                    EngineEvent.Task(11, "mail", TaskPhase.Completed),
                    EngineEvent.Task(27, "exp", TaskPhase.Started),
                ), events.filterIsInstance<EngineEvent.Task>())
                return ActionOutcome.Finish(false, "找不到 09")
            }
        })

        assertFalse(mailbox.taskRun(selected, events::add).execute())

        assertEquals(1, mailbox.opened)
        assertEquals(1, mailbox.claimed)
        val tasks = events.filterIsInstance<EngineEvent.Task>()
        assertEquals(listOf(11, 11, 27, 27, 38, 49), tasks.map { it.taskId })
        assertEquals(listOf(TaskPhase.Started, TaskPhase.Completed, TaskPhase.Started,
            TaskPhase.Failed, TaskPhase.Stopped, TaskPhase.Stopped), tasks.map { it.phase })
        assertTrue(tasks.last().message!!.startsWith("未开始："))
        assertTrue(events.filterIsInstance<EngineEvent.Failure>().single().reason.contains("找不到 09"))
    }

    @Test fun stoppingAfterMailKeepsItsSuccessWithoutReportingAnExpFailure() = runTest {
        val mailbox = Mailbox(hasMail = true, hasNotification = false)
        val events = mutableListOf<EngineEvent>()
        ActionRegistry.register("exp_select_stage", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome = throw StoppedException()
        })

        assertFalse(mailbox.taskRun(linkedMapOf(51 to LimbusTask.MAIL, 62 to LimbusTask.EXP), events::add).execute())

        assertEquals(listOf(TaskPhase.Started, TaskPhase.Completed, TaskPhase.Started, TaskPhase.Stopped),
            events.filterIsInstance<EngineEvent.Task>().map { it.phase })
        assertTrue(events.none { it is EngineEvent.Failure })
    }

    @Test fun emptyMailboxCompletesTheSelectedTaskWithItsActualId() = runTest {
        val mailbox = Mailbox(hasMail = false, hasNotification = false)
        val events = mutableListOf<EngineEvent>()

        assertTrue(mailbox.taskRun(mapOf(1001 to LimbusTask.MAIL), events::add).execute())

        assertEquals(1, mailbox.opened)
        assertEquals(0, mailbox.claimed)
        assertEquals(listOf(EngineEvent.Task(1001, "mail", TaskPhase.Started),
            EngineEvent.Task(1001, "mail", TaskPhase.Completed)), events)
    }

    @Test fun unconfirmedClaimFailsWithoutEscapingOrClaimingAgainOnTheHomePage() = runTest {
        val mailbox = Mailbox(hasMail = true, hasNotification = false, showClaimResult = false)

        assertEquals(
            "未能确认邮件领取结果，请放大游戏画面检查邮箱后重试",
            mailbox.runner(selection(mail = true)).run(LimbusTask.ENTRY_NODE),
        )

        assertEquals(1, mailbox.opened)
        assertEquals(1, mailbox.claimed)
        assertNull(mailbox.counters["check_mail"])
        assertTrue(mailbox.recorded.keyPresses().isEmpty())
        assertFalse(mailbox.logs.any { it == "节点 end 执行动作 empty" })
    }

    @Test fun mailAdaptationIsPerRunAndPreservesTheOriginalUpstreamRegistry() {
        val original = PipelineRegistry.load(LalcV500Fixtures.taskFiles())
        val disabled = original.withEnabled(mapOf(LimbusTask.MAIL.nodeName to false)).withAndroidMailEntry()
        val enabled = original.withEnabled(mapOf(LimbusTask.MAIL.nodeName to true)).withAndroidMailEntry()

        assertEquals(133, original.size)
        assertEquals(original.size, enabled.size)
        assertEquals("check_and_get_mails", original.require("task_center").next.first())
        assertEquals("template_match", original.require("check_and_get_mails").recognition)
        assertEquals(listOf("claim_mail"), original.require("check_and_get_mails").next)
        assertEquals("mail_entry", enabled.require("task_center").next.first())
        assertEquals("direct", enabled.require("check_and_get_mails").recognition)
        assertEquals(listOf("claim_mail", "exit_mailbox"), enabled.require("check_and_get_mails").next)
        assertEquals("mail_entry", enabled.require("check_mail").str("disable_node"))
        assertFalse(disabled.require("mail_entry").enable)
        assertTrue(enabled.require("mail_entry").enable)
        assertTrue(original.require("mail_entry").enable)
    }

    @Test fun mailboxClickRequiresBothHomeAnchors() = runTest {
        val open = PipelineRegistry.load(LalcV500Fixtures.taskFiles()).withAndroidMailEntry()
            .require("check_and_get_mails")
        for (onlyAnchor in listOf("main_window_no_text", "main_drive_no_text")) {
            val input = FakeInput()
            val rec = FakeRecognizer().apply { onTemplate(onlyAnchor, Match(900, 650, .95)) }
            val ctx = TestActionContext(open, "check_and_get_mails", input, rec)

            assertEquals(
                ActionOutcome.Finish(false, "无法确认邮件入口所在的主页，请放大游戏画面检查弹窗后重试"),
                ActionRegistry["click"]!!.execute(ctx),
            )
            assertTrue(input.events.isEmpty())
        }
    }

    @Test fun futureMailRoutingRecognitionOrTargetsAreRejectedInsteadOfOverwritten() {
        for ((name, changes) in listOf(
            "check_and_get_mails" to """{"recognition":"color_template_match"}""",
            "claim_mail" to """{"params":{"template":"no_mail_in_storage","target":[900,300]}}""",
            "confirm_reward" to """{"next":["exit_mailbox"]}""",
        )) {
            val registry = withMailNode(name, changes)
            val failure = assertThrows(IllegalStateException::class.java) { registry.withAndroidMailEntry() }
            assertTrue(failure.message.orEmpty().contains(name))
            assertTrue(failure.message.orEmpty().contains("请升级 App"))
            assertNull(registry.require(name).str("android_mail_flow"))
        }
    }

    @Test fun changedMailShapeDoesNotBlockExpWhenMailIsDisabled() = runTest {
        val source = withMailNode("claim_mail", """{
            "params":{"template":"no_mail_in_storage","target":[900,300]}
        }""")
        val disabled = source.withEnabled(mapOf(LimbusTask.MAIL.nodeName to false)).withAndroidMailEntry()
        assertFalse(disabled.require("mail_entry").enable)
        assertFalse(disabled.require("check_and_get_mails").enable)
        assertEquals(source.require("task_center").next, disabled.require("task_center").next)
        assertEquals("template_match", disabled.require("check_and_get_mails").recognition)
        assertNull(disabled.require("claim_mail").str("android_mail_flow"))
        assertTrue(source.require("mail_entry").enable)

        val mailbox = Mailbox(hasMail = true, hasNotification = true)
        var stages = 0
        ActionRegistry.register("exp_select_stage", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome {
                stages++
                return ActionOutcome.Finish(true)
            }
        })

        assertNull(mailbox.runner(selection(mail = false, exp = true), source).run(LimbusTask.ENTRY_NODE))
        assertEquals(1, stages)
        assertEquals(0, mailbox.opened)
        assertEquals(0, mailbox.claimed)
        assertNull(mailbox.counters["check_mail"])
    }

    @Test fun ignoredMailMetadataAndResourceTimingArePreserved() {
        val registry = withMailNode("claim_mail", """{
            "desc":"updated description", "rate_limit":0.25,
            "params":{"template":"no_mail_in_storage","target":[950,270],"future_note":"retained"}
        }""")
        val claim = registry.withAndroidMailEntry().require("claim_mail")
        assertEquals("updated description", claim.desc)
        assertEquals(0.25, claim.rateLimit, 0.0)
        assertEquals("retained", claim.str("future_note"))
        assertEquals(JsonPrimitive(true), claim.params["android_mail_flow"])
    }

    private fun withMailNode(name: String, changes: String): PipelineRegistry {
        val files = LalcV500Fixtures.taskFiles().toMutableMap()
        val mail = Json.parseToJsonElement(files.getValue("mail.json")).jsonObject
        val node = JsonObject(mail.getValue(name).jsonObject + Json.parseToJsonElement(changes).jsonObject)
        files["mail.json"] = JsonObject(mail + (name to node)).toString()
        return PipelineRegistry.load(files)
    }

    private fun selection(mail: Boolean, exp: Boolean = false): List<LimbusTask> {
        var config = LimbusWorkspaceConfig()
        for (name in listOf("Mail", "EXP", "Thread", "Mirror", "Reward")) {
            val enabled = (name == "Mail" && mail) || (name == "EXP" && exp)
            config = config.withTask(name, config.task(name).copy(enabled = enabled))
        }
        return config.selectedTasks().map { (type, _) -> requireNotNull(LimbusTask.ofType(type)) }
    }

    private enum class View { HOME, DRIVE, MAILBOX, REWARD, PENDING, EXP }

    /** UI state changes only when production actions inject the expected input. */
    private class Mailbox(
        var hasMail: Boolean,
        private val hasNotification: Boolean,
        private val showClaimResult: Boolean = true,
        private val afterClose: View = View.HOME,
    ) {
        var view = View.HOME
        var opened = 0
        var claimed = 0
        val recorded = FakeInput()
        val counters = mutableMapOf<String, Int>()
        val logs = mutableListOf<String>()
        private var recognitionCount = 0

        private val input = object : InputSink by recorded {
            override fun touchUp(x: Int, y: Int, contact: Int) {
                recorded.touchUp(x, y, contact)
                when (x to y) {
                    825 to 647 -> view = View.HOME
                    978 to 649 -> view = View.DRIVE
                    1120 to 125 -> {
                        assertEquals(View.HOME, view)
                        view = View.MAILBOX
                        opened++
                    }
                    950 to 270 -> {
                        assertEquals(View.MAILBOX, view)
                        assertTrue("must not claim an empty mailbox", hasMail)
                        hasMail = false
                        view = if (showClaimResult) View.REWARD else View.PENDING
                        claimed++
                    }
                    640 to 470 -> {
                        assertEquals(View.REWARD, view)
                        view = View.MAILBOX
                    }
                    776 to 551 -> {
                        assertEquals(View.MAILBOX, view)
                        view = afterClose
                    }
                    440 to 160 -> view = View.EXP
                }
            }

            override fun keyUp(keyCode: Int) {
                recorded.keyUp(keyCode)
                if (keyCode == KeyEvent.KEYCODE_ESCAPE) view = when (view) {
                    View.REWARD -> View.MAILBOX
                    View.MAILBOX -> View.HOME
                    else -> error("unexpected Escape on $view")
                }
            }
        }

        private val recognize = object : Recognizer by FakeRecognizer() {
            override suspend fun observeMailbox(): MailboxObservation? =
                if (view == View.MAILBOX) MailboxObservation(Match(776, 551, .95), empty = !hasMail) else null

            override suspend fun templateMatch(
                template: String, threshold: Double, crop: Crop?, maskTemplate: Crop?, screenshotScale: Double,
            ): List<Match> {
                check(++recognitionCount <= 200) { "mail did not complete: $logs" }
                val match = when (template) {
                    "main_drive_no_text", "main_drive_with_text" -> if (view in listOf(View.HOME, View.DRIVE)) Match(978, 649, .95) else null
                    "main_window_no_text" -> if (view in listOf(View.HOME, View.DRIVE)) Match(825, 647, .95) else null
                    "red_exclaimation" -> if (view == View.HOME && hasNotification) Match(1130, 110, .95) else null
                    "no_mail_in_storage" -> if (view == View.MAILBOX && !hasMail) Match(640, 360, .95) else null
                    "rewards_acquired_confirm" -> if (view == View.REWARD) Match(640, 470, .95) else null
                    // exp_entry is inverse(inferno): it must miss on Window, then hit
                    // only after the production main_drive_confirm click selects Drive.
                    "inferno" -> if (view == View.DRIVE) Match(1136, 148, .95) else null
                    "luxcavation" -> if (view == View.EXP) Match(640, 100, .95) else null
                    else -> null
                }
                return listOfNotNull(match)
            }
        }

        fun runner(
            selected: List<LimbusTask>,
            source: PipelineRegistry = PipelineRegistry.load(LalcV500Fixtures.taskFiles()),
        ): PipelineRunner {
            val registry = source
                .withEnabled(LimbusTask.allNodeNames().associateWith { node -> selected.any { it.nodeName == node } })
                .withAndroidMailEntry()
            val gate = NodeRecognizer(recognize)
            return PipelineRunner(
                registry,
                contextFactory = { name, node, matches ->
                    object : ActionContext by TestActionContext(node, name, input, recognize, recognizeResult = matches) {
                        override fun counterOf(nodeName: String) = counters[nodeName] ?: 0
                        override fun incrementCounter(nodeName: String): Int =
                            ((counters[nodeName] ?: 0) + 1).also { counters[nodeName] = it }
                        override fun log(message: String) { logs += message }
                    }
                },
                recognizeGate = gate::recognize,
                onLog = logs::add,
            ).also { it.delayer = {} }
        }

        fun taskRun(selected: Map<Int, LimbusTask>, events: (EngineEvent) -> Unit): LimbusTaskRun {
            val registry = PipelineRegistry.load(LalcV500Fixtures.taskFiles())
                .withEnabled(LimbusTask.allNodeNames().associateWith { node -> selected.values.any { it.nodeName == node } })
                .withAndroidMailEntry()
                .withTargetCounts(mapOf("exp_check" to 1, "thread_check" to 1, "mirror_check" to 1))
            val gate = NodeRecognizer(recognize)
            return LimbusTaskRun(
                registry, selected,
                contextFactory = { name, node, matches ->
                    object : ActionContext by TestActionContext(node, name, input, recognize, recognizeResult = matches) {
                        override fun counterOf(nodeName: String) = counters[nodeName] ?: 0
                        override fun incrementCounter(nodeName: String): Int =
                            ((counters[nodeName] ?: 0) + 1).also { counters[nodeName] = it }
                        override fun log(message: String) { logs += message }
                    }
                },
                recognizeGate = gate::recognize,
                emit = events,
                onLog = logs::add,
            ).also { it.pipeline.delayer = {} }
        }
    }
}
