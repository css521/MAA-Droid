package com.maadroid.app.engine.limbus.pipeline

import android.view.KeyEvent
import com.maadroid.app.engine.limbus.action.ActionRegistry
import com.maadroid.app.engine.limbus.action.ActionBackend
import com.maadroid.app.engine.limbus.action.ActionContext
import com.maadroid.app.engine.limbus.action.ActionOutcome
import com.maadroid.app.engine.limbus.action.FakeConfig
import com.maadroid.app.engine.limbus.action.FakeInput
import com.maadroid.app.engine.limbus.action.FakeRecognizer
import com.maadroid.app.engine.limbus.action.FakeTemplateIndex
import com.maadroid.app.engine.limbus.action.LimbusActions
import com.maadroid.app.engine.limbus.action.TestActionContext
import com.maadroid.app.engine.limbus.fixtures.LalcV500Fixtures
import com.maadroid.app.engine.limbus.recognize.Match
import com.maadroid.app.engine.limbus.recognize.GameLanguageObservation
import com.maadroid.app.engine.limbus.recognize.Recognizer
import com.maadroid.app.engine.limbus.recognize.TextMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 拿**上游真实流水线**跑执行器。
 *
 * 前面的用例都用手写的小流水线验证调度语义；这条则证明：133 个真实节点装配完之后，
 * 执行器能在其中走动、动作能被真正调用、识别不中时会按 next/interrupt 收敛而不是卡死。
 * 这是「引擎能跑」与「引擎能编译」之间的差别。
 *
 * 识别用可编程替身而非真 OpenCV：目的是验证**调度与动作**，不是验证匹配算法
 * （那由 TemplateMatcherTest 与真机负责）。上游 JSON 随测试提交，缺失即失败。
 */
class UpstreamPipelineRunTest {

    private lateinit var registry: PipelineRegistry

    @Before
    fun setUp() {
        ActionRegistry.clearForTest()
        LimbusActions.resetForTest()
        LimbusActions.install()

        // LimbusEngine supplies these run options; upstream JSON leaves the three targets unset.
        registry = PipelineRegistry.load(LalcV500Fixtures.taskFiles()).withTargetCounts(
            mapOf("exp_check" to 1, "thread_check" to 1, "mirror_check" to 1),
        )
    }

    private fun pipelineConfig() = FakeConfig()
        .put("exp", "exp_stage", "09")
        .put("exp", "luxcavation_mode", "enter")
        .put("thread", "thread_stage", "60")
        .put("thread", "luxcavation_mode", "enter")

    private fun runnerFor(
        recognizer: Recognizer,
        input: FakeInput,
        config: FakeConfig = pipelineConfig(),
        onLog: (String) -> Unit = {},
    ): PipelineRunner {
        val counters = ConcurrentHashMap<String, Int>()
        val nodeRecognizer = NodeRecognizer(recognizer)
        return PipelineRunner(
            registry = registry,
            contextFactory = { name, node, matches ->
                object : ActionContext by TestActionContext(
                    node = node,
                    nodeName = name,
                    input = input,
                    recognize = recognizer,
                    config = config,
                    templates = FakeTemplateIndex(),
                    recognizeResult = matches,
                ) {
                    // Action and Route create separate contexts, including for the same node.
                    // Both must read/write this run's map; team rotation also reads other nodes.
                    override fun counterOf(nodeName: String): Int = counters[nodeName] ?: 0
                    override fun incrementCounter(nodeName: String): Int =
                        counters.merge(nodeName, 1) { previous, increment -> previous + increment }!!
                }
            },
            recognizeGate = { nodeRecognizer.recognize(it) },
            onLog = onLog,
        )
    }

    @Test fun realStageActionWaitsForMobileTeamPageThenUsesUpstreamChooseTeam() = runTest {
        for (section in listOf("exp", "thread")) {
            val base = FakeRecognizer().apply { textHits = listOf(TextMatch(if (section == "exp") "09" else "60", 800, 205, .95)) }
            var observations = 0
            val reader = object : Recognizer by base {
                override suspend fun observeTeamSelection(): Match? =
                    if (++observations < 3) null else Match(1176, 520, .828)
            }
            val chosen = mutableListOf<String>()
            // The supplied phone evidence stops at team selection. Do not pretend a battle ran.
            ActionRegistry.register("choose_team", object : ActionBackend {
                override suspend fun execute(ctx: ActionContext): ActionOutcome {
                    chosen += ctx.nodeName
                    return ActionOutcome.Finish(true)
                }
            })
            val input = FakeInput()
            val logs = mutableListOf<String>()
            val runner = runnerFor(reader, input, onLog = logs::add).also { it.delayer = {} }
            assertNull(runner.run("${section}_select_stage"))
            assertEquals(listOf("${section}_choose_team"), chosen)
            assertEquals(4, observations) // three observations in action, one in the real next gate
            // 纺锤末次点击横向固定为该行 Enter 的 785（手机上难度标签不可点），
            // 纵向跟随 OCR 命中；经验仍沿用上游的命中坐标 + 偏移
            assertEquals(if (section == "exp") listOf(810 to 480) else
                listOf(140 to 330, 370 to 480, 785 to 205), input.clicks())
            assertTrue(logs.none { "exp_can_not_skip_battle 执行动作" in it })
        }
    }

    @Test fun realSkipEntryWaitsForItsDialogWithoutSelectingTeamOrRepeatingClick() = runTest {
        val reader = FakeRecognizer().apply {
            textHits = listOf(TextMatch("09", 1000, 205, .95))
            onTemplateSequence("skip_battle", emptyList(), emptyList())
            onTemplate("skip_battle", Match(640, 360, .95))
        }
        val reached = mutableListOf<String>()
        ActionRegistry.register("click", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome {
                reached += ctx.nodeName
                return ActionOutcome.Finish(true)
            }
        })
        val input = FakeInput()
        val config = pipelineConfig().put("exp", "luxcavation_mode", "skip battle")
        val runner = runnerFor(reader, input, config).also { it.delayer = {} }
        assertNull(runner.run("exp_select_stage"))
        assertEquals(listOf("exp_skip_battle"), reached)
        assertEquals(listOf(1010 to 515), input.clicks())
    }

    @Test fun unknownPageFailsAtStageEntryAndDoesNotClaimSkipIsLocked() = runTest {
        for (mode in listOf("enter", "skip battle")) {
            val reader = FakeRecognizer().apply { textHits = listOf(TextMatch("09", 1000, 205, .95)) }
            val input = FakeInput()
            val logs = mutableListOf<String>()
            val runner = runnerFor(reader, input, pipelineConfig().put("exp", "luxcavation_mode", mode), logs::add)
                .also { it.delayer = {} }
            val failure = runner.run("exp_select_stage")
            assertTrue(failure.orEmpty().contains("未能确认"))
            assertFalse(failure.orEmpty().contains("未解锁"))
            assertEquals(listOf(1010 to if (mode == "enter") 480 else 515), input.clicks())
            assertTrue(logs.none { "exp_can_not_skip_battle 执行动作" in it || "choose_team 执行动作" in it })
            // enter 分支已改走文字判据（上游 details 素材在安卓上匹配不到，OCR 读 "Details" 稳定），
            // 所以这里数的是 OCR 调用；skip battle 分支仍用模板。
            if (mode == "enter") assertEquals(10, reader.textCalls.count { it == "Details" })
            else assertEquals(10, reader.templateCalls.count { it == "skip_battle" })
        }
    }

    @Test fun cancellingDuringPageObservationStopsWithoutFurtherInput() = runTest {
        val base = FakeRecognizer().apply { textHits = listOf(TextMatch("09", 1000, 205, .95)) }
        var observations = 0
        val reader = object : Recognizer by base {
            override suspend fun observeTeamSelection(): Match? {
                if (++observations == 2) throw CancellationException("cancel team wait")
                return null
            }
        }
        val input = FakeInput()
        val runner = runnerFor(reader, input).also { it.delayer = {} }
        try {
            runner.run("exp_select_stage")
            throw AssertionError("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(2, observations)
            assertEquals(listOf(1010 to 480), input.clicks())
            assertFalse(runner.isRunning)
        }
    }

    @Test fun realCheckNodeRepeatsUntilTargetCountThenTakesItsExit() = runTest {
        registry = registry.withTargetCounts(mapOf("exp_check" to 3))
        val repeatedAt = mutableListOf<Int>()
        val exitedAt = mutableListOf<Int>()
        // Only the battle/UI boundary is replaced. exp_check, its check_out_update action,
        // origin/next routing, and target_count handling all come from the real pipeline.
        ActionRegistry.register("exp_select_stage", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome {
                repeatedAt += ctx.counterOf("exp_check")
                return ActionOutcome.Goto("exp_check")
            }
        })
        ActionRegistry.register("key", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome {
                assertEquals("exp_quit", ctx.nodeName)
                exitedAt += ctx.counterOf("exp_check")
                return ActionOutcome.Finish(true)
            }
        })
        val logs = mutableListOf<String>()
        val runner = runnerFor(FakeRecognizer(), FakeInput(), onLog = logs::add).also { it.delayer = {} }

        assertNull(runner.run("exp_check"))
        assertEquals(listOf(1, 2), repeatedAt)
        assertEquals(listOf(3), exitedAt)
        assertEquals(3, logs.count { it == "节点 exp_check 执行动作 check_out_update" })
        assertTrue(logs.none { "死循环" in it || "节点 error_handler 执行动作" in it })
    }

    @Test fun confirmedLanguageReturnsToRealLuxcavationRouteWithoutErrorHandlerLoop() = runTest {
        for (task in listOf("exp", "thread")) {
            val fake = FakeRecognizer().apply {
                onTemplate("main_drive_no_text", Match(978, 649, .95))
                onTemplate("inferno", Match(1136, 148, .93))
                onTemplate("luxcavation", Match(640, 100, .95))
                // The inverse language gate misses during the transition, as in build 749.
            }
            var observations = 0
            val recognizer = object : Recognizer by fake {
                override suspend fun observeGameLanguage() =
                    if (++observations == 1) GameLanguageObservation.Uncertain else GameLanguageObservation.Confirmed
            }
            val stages = mutableListOf<String>()
            // Stop at stage selection: the supplied frames cover navigation, not a battle.
            ActionRegistry.register("${task}_select_stage", object : ActionBackend {
                override suspend fun execute(ctx: ActionContext): ActionOutcome {
                    stages += ctx.nodeName
                    return ActionOutcome.Finish(true)
                }
            })
            val input = FakeInput()
            val logs = mutableListOf<String>()
            assertEquals(listOf("error_handler"), registry.interruptsOf("game_language_confirm"))
            val runner = runnerFor(recognizer, input, onLog = logs::add).also { it.delayer = {} }

            assertNull(runner.run("${task}_entry"))
            assertEquals(2, observations)
            assertEquals(listOf("${task}_select_stage"), stages)
            assertEquals(listOf(978 to 649, 440 to 160), input.clicks())
            assertTrue(logs.any { "节点 game_language_confirm 执行动作 report_error" in it })
            assertTrue(logs.none { "节点 error_handler 执行动作" in it })
        }
    }

    @Test fun unconfirmedLanguageNeverResumesParentTask() = runTest {
        for (observation in listOf(GameLanguageObservation.Uncertain, GameLanguageObservation.Mismatch("zh", "en"))) {
            val fake = FakeRecognizer().apply {
                onTemplate("main_drive_no_text", Match(978, 649, .95))
                onTemplate("inferno", Match(1136, 148, .93))
            }
            var observations = 0
            val recognizer = object : Recognizer by fake {
                override suspend fun observeGameLanguage(): GameLanguageObservation {
                    observations++
                    return observation
                }
            }
            val input = FakeInput()
            val logs = mutableListOf<String>()
            val runner = runnerFor(recognizer, input, onLog = logs::add).also { it.delayer = {} }
            val failure = runner.run("exp_entry")
            val uncertain = observation == GameLanguageObservation.Uncertain
            assertTrue(failure?.contains(if (uncertain) "暂时无法识别主页导航" else "游戏画面为英文") == true)
            assertEquals(if (uncertain) 3 else 1, observations)
            assertEquals(listOf(978 to 649), input.clicks())
            assertTrue(logs.none { "节点 exp_enter 执行动作" in it || "节点 error_handler 执行动作" in it })
        }
    }

    @Test
    fun `真实流水线装配出 133 个节点`() {
        assertEquals(133, registry.size)
    }

    @Test
    fun `主页可识别且未启用任务时 main 正常走到 end`() = runTest {
        assertNotNull("上游流水线的入口应当是 main", registry["main"])
        // Task selection changes enable flags, not the entry node. A recognized home page
        // bypasses back_to_init_page; with no task branch enabled, task_center selects end.
        registry = registry.withEnabled(
            registry.require("task_center").next.filterNot { it == "end" }.associateWith { false },
        )
        val recognizer = FakeRecognizer().apply {
            onTemplate("main_drive_no_text", Match(978, 649, .95))
        }
        val input = FakeInput()
        val logs = mutableListOf<String>()
        val runner = runnerFor(recognizer, input, onLog = logs::add).also { it.delayer = {} }

        assertNull(runner.run("main"))
        assertEquals(1, logs.count { it == "节点 main 执行动作 init_limbus_window" })
        assertEquals(1, logs.count { it == "节点 end 执行动作 empty" })
        assertTrue(logs.none { it == "节点 back_to_init_page 执行动作 back_to_init_page" })
        assertTrue(input.events.isEmpty())
        assertFalse(runner.isRunning)
    }

    @Test
    fun `持续无法识别主页时第21次恢复明确失败且只发送17次Esc`() = runTest {
        // Shared counters preserve the recovery budget across newly created node contexts.
        // Attempts 1..3 wait, 4..20 send Esc, and 21 fails before sending further input.
        // The generic MAX_STEPS guard is covered separately by PipelineRunnerTest.
        val input = FakeInput()
        val logs = mutableListOf<String>()
        val runner = runnerFor(FakeRecognizer(), input, onLog = logs::add).also { it.delayer = {} }

        assertEquals(
            "持续无法识别登录或主页，请检查游戏语言设置，放大画面处理弹窗后重试，并导出日志",
            runner.run("main"),
        )
        assertEquals(21, logs.count { it == "节点 back_to_init_page 执行动作 back_to_init_page" })
        assertEquals(List(17) { KeyEvent.KEYCODE_ESCAPE }, input.keyPresses())
        assertTrue(input.clicks().isEmpty())
        assertTrue(logs.none { it == "节点 end 执行动作 empty" })
        assertFalse(runner.isRunning)
    }

    @Test
    fun `识别命中时动作被真正执行并注入输入`() = runTest {
        // 让所有模板都命中，流水线就会从 main 走进 check_and_get_mails、
        // error_server_error_retry_confirm 等带 click 的节点
        val rec = FakeRecognizer()
        registry.referencedTemplates().forEach { rec.onTemplate(it, Match(640, 360, 0.95)) }
        val input = FakeInput()

        val reason = runnerFor(rec, input).run("main")

        assertNotNull("全命中同样会一直往下走，最终被步数保险兜住", reason)
        // 这条断言当初揪出了真 bug：10 个 click 节点没配 target，
        // 早先的实现把它当成「无目标」而什么都不点，整条链路静默失效
        assertTrue("动作应当真的注入了输入", input.clicks().isNotEmpty())
    }

    @Test
    fun `未实现的纯路由动作被放过而不报错`() = runTest {
        val logs = mutableListOf<String>()
        val rec = FakeRecognizer()
        // error_handler 是 10 个纯路由动作之一，没有实现体
        rec.onTemplate("connecting", Match(1, 1, 0.9))

        runnerFor(rec, FakeInput(), onLog = logs::add).run("main")

        // 纯路由不该被当成错误
        assertTrue("不该把纯路由报成错误", logs.none { "错误" in it || "异常" in it })
    }

    @Test
    fun `流水线引用的动作全部有实现体或属于纯路由`() {
        val referenced = registry.referencedActions()
        val missing = referenced.filter { name ->
            ActionRegistry[name] == null && registry[name] == null
        }
        // 上游要求 action 名同时是已注册节点名；缺实现体的必须是流水线里的节点（纯路由）
        assertTrue("存在既无实现体又非节点的动作: $missing", missing.isEmpty())
    }
}
