package com.aliothmoon.maadroid.engine.limbus.pipeline

import com.aliothmoon.maadroid.engine.limbus.action.ActionBackend
import com.aliothmoon.maadroid.engine.limbus.action.ActionContext
import com.aliothmoon.maadroid.engine.limbus.action.ActionOutcome
import com.aliothmoon.maadroid.engine.limbus.action.ActionRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.aliothmoon.maadroid.engine.InputSink
import com.aliothmoon.maadroid.engine.limbus.action.TestActionContext
import com.aliothmoon.maadroid.engine.limbus.recognize.Crop
import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer

/**
 * 流水线调度语义测试。
 *
 * 这些语义全部来自上游 workflow/task_pipeline.py，任一条走偏都会让流水线行为漂移，
 * 而漂移在真机上表现为「卡在某个界面」或「反复点同一个按钮」，极难定位。
 * 用假识别器在纯 JVM 上钉住，避免只能靠真机试错。
 */
class PipelineRunnerTest {

    /** 记录动作执行顺序 */
    private val trace = mutableListOf<String>()

    /** 由测试指定哪些节点「识别命中」 */
    private var hits: Set<String> = emptySet()

    private fun recordingAction(tag: String) = object : ActionBackend {
        override suspend fun execute(ctx: ActionContext): ActionOutcome {
            trace += tag
            return ActionOutcome.Continue
        }
    }

    @Before
    fun setUp() {
        trace.clear()
        ActionRegistry.clearForTest()
        ActionRegistry.register("empty", recordingAction("EMPTY"))
    }

    @After
    fun tearDown() = ActionRegistry.clearForTest()

    private fun runner(reg: PipelineRegistry) = PipelineRunner(
        registry = reg,
        contextFactory = { name, node, matches -> fakeContext(node, name, matches) },
        recognizeGate = { node ->
            val name = reg.names().first { reg[it] === node }
            RecognizeOutcome(hit = name in hits)
        },
    ).also { it.delayer = { /* 测试里不真睡 */ } }

    // 一个最小流水线：main → a / b，interrupt 用 error_handler
    private fun basicRegistry() = PipelineRegistry.load(
        mapOf(
            "main.json" to """
                {
                  "empty":         { "action": "empty" },
                  "error_handler": { "action": "act_err" },
                  "act_err":       { "action": "empty" },
                  "act_a":         { "action": "empty" },
                  "act_b":         { "action": "empty" },
                  "a":    { "action": "act_a", "rate_limit": 0 },
                  "b":    { "action": "act_b", "rate_limit": 0 },
                  "main": { "action": "empty", "rate_limit": 0, "next": ["a", "b"] }
                }
            """.trimIndent(),
        )
    )

    /**
     * 上游经验本的形状：`next` 末位是一个没有 `recognition` 字段的节点，
     * 它默认 [PipelineNode.RECOGNITION_DIRECT] 因而**永不失败**，动作是 report_error。
     * 于是「两个真候选都没匹配上」被上报成「该跳过未解锁 | Can not Skip Battle」
     * ——识别失败伪装成业务结论，真机排查方向被整个带偏
     * （见 luxcavation.json 的 exp_can_not_skip_battle）。
     *
     * 这里钉住：落到这种兜底分支时，日志必须讲出真实原因和每个候选差多少。
     */
    @Test
    fun `落到无条件兜底节点时报出真实原因与各候选差距`() = runTest {
        // 注册表 load 时会校验 action / interrupt 目标是否已存在，故先注册原生动作
        ActionRegistry.register("act_report", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext) =
                ActionOutcome.Finish(success = false, message = "该跳过未解锁 | Can not Skip Battle")
        })
        val reg = PipelineRegistry.load(
            mapOf(
                "flow.json" to """
                    {
                      "empty":         { "action": "empty" },
                      "error_handler": { "action": "empty" },
                      "act_report":    { "action": "empty" },
                      "team":   { "action": "empty", "recognition": "template_match",
                                  "params": { "template": "details" } },
                      "skip":   { "action": "empty", "recognition": "template_match",
                                  "params": { "template": "skip_battle" } },
                      "cannot": { "action": "act_report" },
                      "stage":  { "action": "empty", "rate_limit": 0,
                                  "next": ["team", "skip", "cannot"] }
                    }
                """.trimIndent(),
            )
        )
        val logs = mutableListOf<String>()
        val runner = PipelineRunner(
            registry = reg,
            contextFactory = { name, node, matches -> fakeContext(node, name, matches) },
            recognizeGate = { node ->
                when (reg.names().first { reg[it] === node }) {
                    // 页面对了但素材匹配不上：峰值贴着阈值
                    "team" -> RecognizeOutcome(false, miss = TemplateMiss("details", 0.85, 0.831, 640, 410))
                    // 画面根本不是这一页：峰值很低
                    "skip" -> RecognizeOutcome(false, miss = TemplateMiss("skip_battle", 0.85, 0.402, 12, 34))
                    else -> RecognizeOutcome.DIRECT_HIT
                }
            },
            onLog = { logs += it },
        ).also { it.delayer = {} }

        val reason = runner.run("stage")

        // 业务结论照旧上报（不改上游语义），但日志里必须同时有可定位的真实原因
        assertEquals("该跳过未解锁 | Can not Skip Battle", reason)
        val fallback = logs.firstOrNull { it.contains("落到兜底节点") }
        assertTrue("应报出落到兜底分支: $logs", fallback != null)
        assertTrue(fallback!!, fallback.contains("cannot"))
        // 两个候选各差多少都要在，才能分开「改时序」和「重截素材」
        assertTrue(fallback, fallback.contains("details 峰值=0.831@640,410 阈值=0.85"))
        assertTrue(fallback, fallback.contains("skip_battle 峰值=0.402@12,34 阈值=0.85"))
    }

    @Test
    fun `真候选命中时不输出兜底诊断`() = runTest {
        val reg = basicRegistry()
        ActionRegistry.register("act_a", recordingAction("A"))
        ActionRegistry.register("act_b", recordingAction("B"))
        hits = setOf("a")
        val logs = mutableListOf<String>()
        val runner = PipelineRunner(
            registry = reg,
            contextFactory = { name, node, matches -> fakeContext(node, name, matches) },
            recognizeGate = { node ->
                RecognizeOutcome(hit = reg.names().first { reg[it] === node } in hits)
            },
            onLog = { logs += it },
        ).also { it.delayer = {} }

        runner.run("main")

        // 未命中是路由常态，不能因此刷日志
        assertTrue("命中时不应有兜底诊断: $logs", logs.none { it.contains("落到兜底节点") })
    }

    @Test
    fun nextTakesFirstDeclaredHitNotBestScore() = runTest {
        val reg = basicRegistry()
        ActionRegistry.register("act_a", recordingAction("A"))
        ActionRegistry.register("act_b", recordingAction("B"))
        // a 与 b 同时命中：必须走声明顺序里的第一个（a）
        hits = setOf("a", "b")

        assertNull(runner(reg).run("main"))
        assertEquals(listOf("A"), trace.filterNot { it == "EMPTY" })
    }

    @Test
    fun fallsBackToInterruptWhenNoNextHits() = runTest {
        val reg = basicRegistry()
        ActionRegistry.register("act_a", recordingAction("A"))
        ActionRegistry.register("act_err", recordingAction("ERR"))
        // next 都不命中，只有 interrupt 的 error_handler 命中
        hits = setOf("error_handler")

        val reason = runner(reg).run("main")

        assertTrue("应执行中断动作: $trace", trace.contains("ERR"))
        // 中断处理完必须回到原节点继续路由（上游语义）。本用例里 hits 恒定，
        // 于是 main 路由 → 中断命中 → ERR → 回 main → 又只有中断命中 → …… 形成自环，
        // 因此 ERR 会重复出现，并最终被步数保险终止。两件事同时被验证：
        // 回归确实发生了，且无限循环不会让用户干等。
        assertTrue("中断后应回到原节点，故 ERR 会重复: ${trace.size} 次", trace.count { it == "ERR" } > 1)
        assertTrue("自环应被步数保险终止: $reason", reason?.contains("死循环") == true)
    }

    @Test
    fun disabledNodeIsSkippedDuringRouting() = runTest {
        val reg = PipelineRegistry.load(
            mapOf(
                "main.json" to """
                    {
                      "empty":         { "action": "empty" },
                      "error_handler": { "action": "empty" },
                      "act_a":         { "action": "empty" },
                      "act_b":         { "action": "empty" },
                      "a":    { "action": "act_a", "enable": false, "rate_limit": 0 },
                      "b":    { "action": "act_b", "rate_limit": 0 },
                      "main": { "action": "empty", "rate_limit": 0, "next": ["a", "b"] }
                    }
                """.trimIndent(),
            )
        )
        ActionRegistry.register("act_a", recordingAction("A"))
        ActionRegistry.register("act_b", recordingAction("B"))
        hits = setOf("a", "b")

        assertNull(runner(reg).run("main"))
        // a 被禁用（上游 check 节点会这样"用完即弃"），应跳到 b
        assertEquals(listOf("B"), trace.filterNot { it == "EMPTY" })
    }

    @Test
    fun retrySelfRerunsSameAction() = runTest {
        val reg = basicRegistry()
        var count = 0
        ActionRegistry.register("act_a", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome {
                trace += "A${++count}"
                // 第一次要求重试，第二次正常结束
                return if (count == 1) ActionOutcome.RetrySelf else ActionOutcome.Continue
            }
        })
        hits = setOf("a")

        assertNull(runner(reg).run("main"))
        assertEquals(listOf("A1", "A2"), trace.filterNot { it == "EMPTY" })
    }

    @Test
    fun finishClearsStackAndStops() = runTest {
        val reg = basicRegistry()
        ActionRegistry.register("act_a", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome {
                trace += "A"
                return ActionOutcome.Finish(success = true)
            }
        })
        ActionRegistry.register("act_b", recordingAction("B"))
        hits = setOf("a", "b")

        val r = runner(reg)
        assertNull(r.run("main"))
        assertEquals(listOf("A"), trace.filterNot { it == "EMPTY" })
        assertTrue("Finish 后应停止", !r.isRunning)
    }

    @Test
    fun actionWithoutBackendExecutesItsNestedNodeAndReturns() = runTest {
        val reg = basicRegistry()
        // 不注册任何动作：上游 45 个动作名里 10 个无实现体，属正常情形而非错误
        hits = setOf("a", "act_a")
        assertNull(runner(reg).run("main"))
        assertEquals("main 和子节点 act_a 都必须执行 empty", listOf("EMPTY", "EMPTY"), trace)
    }

    @Test fun returnFromNestedChainSkipsItsNextAndDefaultInterruptButResumesCaller() = runTest {
        val reg = PipelineRegistry.load(mapOf(
            "flow.json" to """{
                "empty":{"action":"empty"},
                "return_action":{"action":"empty"},
                "act_a":{"action":"empty"},
                "main":{"action":"child","next":["after"],"interrupt":[]},
                "child":{"action":"empty","next":["leaf"],"interrupt":[]},
                "leaf":{"action":"return_action","next":["forbidden"]},
                "forbidden":{"action":"empty","interrupt":[]},
                "after":{"action":"act_a","interrupt":[]}
            }""",
            "error.json" to """{"error_handler":{"action":"empty"}}""",
        ))
        assertEquals(listOf("error_handler"), reg.interruptsOf("leaf"))
        var attempts = 0
        ActionRegistry.register("return_action", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome {
                trace += "RETURN${++attempts}"
                return if (attempts == 1) ActionOutcome.RetrySelf else ActionOutcome.Return
            }
        })
        ActionRegistry.register("act_a", recordingAction("PARENT"))
        hits = reg.names()

        assertNull(runner(reg).run("main"))
        assertEquals(listOf("EMPTY", "RETURN1", "RETURN2", "PARENT"), trace)
    }

    @Test fun returnFromInterruptPreservesInterruptedRoute() = runTest {
        val reg = basicRegistry()
        ActionRegistry.register("act_a", recordingAction("A"))
        ActionRegistry.register("act_err", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome {
                trace += "RECOVERED"
                hits = setOf("a")
                return ActionOutcome.Return
            }
        })
        hits = setOf("error_handler")

        assertNull(runner(reg).run("main"))
        assertEquals(listOf("EMPTY", "RECOVERED", "A"), trace)
    }

    @Test fun returnAtEntryEndsOnlyThatBranch() = runTest {
        val reg = basicRegistry()
        ActionRegistry.register("empty", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext) = ActionOutcome.Return
        })
        hits = reg.names()
        val r = runner(reg)
        assertNull(r.run("main"))
        assertTrue(!r.isRunning)
        assertTrue(trace.isEmpty())
    }

    @Test fun checkCountRepeatsOriginThenDisablesEntryAndResetsNextRun() = runTest {
        val reg = PipelineRegistry.load(mapOf("flow.json" to """{
            "empty":{"action":"empty","interrupt":[]},
            "inc":{"action":"empty","interrupt":[]},
            "record":{"action":"empty","interrupt":[]},
            "main":{"action":"empty","next":["work"],"interrupt":[],"rate_limit":0},
            "work":{"action":"record","next":["check"],"interrupt":[],"rate_limit":0},
            "check":{"type":"check","action":"inc","params":{"target_count":2,"origin":"work","disable_node":"work"},"next":["work"],"interrupt":[],"rate_limit":0}
        }"""))
        val counts = mutableMapOf<String, Int>()
        ActionRegistry.register("record", recordingAction("WORK"))
        ActionRegistry.register("inc", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext): ActionOutcome { ctx.incrementCounter(ctx.nodeName); return ActionOutcome.Continue }
        })
        val runner = PipelineRunner(reg, { name, node, matches ->
            object : ActionContext by fakeContext(node, name, matches) {
                override fun counterOf(nodeName: String) = counts[nodeName] ?: 0
                override fun incrementCounter(nodeName: String): Int = (counterOf(nodeName) + 1).also { counts[nodeName] = it }
            }
        }, { RecognizeOutcome.DIRECT_HIT }).also { it.delayer = {} }
        repeat(2) {
            counts.clear(); trace.clear()
            assertNull(runner.run("main"))
            assertEquals(2, trace.count { it == "WORK" })
            assertEquals(2, counts["check"])
            assertTrue(reg.require("work").enable)
        }
    }

    @Test fun failedFinishIsNotReportedAsSuccess() = runTest {
        val reg = basicRegistry()
        ActionRegistry.register("act_a", object : ActionBackend {
            override suspend fun execute(ctx: ActionContext) = ActionOutcome.Finish(false, "游戏无法操作")
        })
        hits = setOf("a")
        assertEquals("游戏无法操作", runner(reg).run("main"))
    }

    @Test
    fun unknownEntryIsReported() = runTest {
        assertEquals(
            "入口节点未注册: nope",
            runner(basicRegistry()).run("nope"),
        )
    }

    @Test fun actionDelaysSurroundInputBeforeTheNextRecognition() = runTest {
        val reg = PipelineRegistry.load(mapOf("flow.json" to """{
            "empty":{"action":"empty","interrupt":[]},
            "error_handler":{"action":"empty","interrupt":[]},
            "main":{"action":"empty","params":{"pre_delay":0.2,"post_delay":3},"next":["end"],"interrupt":[],"rate_limit":0},
            "end":{"action":"empty","params":{"pre_delay":0,"post_delay":0},"interrupt":[],"rate_limit":0}
        }"""))
        val r = PipelineRunner(reg, { name, node, matches -> fakeContext(node, name, matches) }, {
            trace += "recognize"
            RecognizeOutcome.DIRECT_HIT
        }).also { it.delayer = { seconds -> trace += "wait:$seconds" } }
        assertNull(r.run("main"))
        assertEquals(listOf("wait:0.2", "EMPTY", "wait:3.0", "recognize", "EMPTY"), trace)
    }

    @Test
    fun runawayLoopIsBoundedInsteadOfHangingForever() = runTest {
        val reg = PipelineRegistry.load(
            mapOf(
                "main.json" to """
                    {
                      "empty":         { "action": "empty" },
                      "error_handler": { "action": "empty" },
                      "main": { "action": "empty", "rate_limit": 0, "next": ["main"] }
                    }
                """.trimIndent(),
            )
        )
        hits = setOf("main")
        // 自环：上游没有这道保险会无限空转，用户只能干等
        val reason = runner(reg).run("main")
        assertTrue("应报死循环而非挂死: $reason", reason?.contains("死循环") == true)
    }

    /**
     * 调度用例只验证「弹什么、压什么」，不该碰识别与输入 —— 命中与否由传给
     * [PipelineRunner] 的 recognizeGate 直接决定。故这里塞会抛异常的替身：
     * 一旦调度逻辑意外去截图或注入，测试立刻炸而不是悄悄通过。
     */
    private fun fakeContext(
        node: PipelineNode,
        name: String,
        matches: List<Match>,
    ) = TestActionContext(
        node = node,
        nodeName = name,
        input = throwingInput,
        recognize = throwingRecognizer,
        recognizeResult = matches,
    )

    private companion object {
        val throwingInput = object : InputSink {
            override fun touchDown(x: Int, y: Int, contact: Int) = error("调度用例不应注入输入")
            override fun touchMove(x: Int, y: Int, contact: Int) = error("调度用例不应注入输入")
            override fun touchUp(x: Int, y: Int, contact: Int) = error("调度用例不应注入输入")
            override fun touchCancel() = error("调度用例不应注入输入")
            override fun keyDown(keyCode: Int) = error("调度用例不应注入输入")
            override fun keyUp(keyCode: Int) = error("调度用例不应注入输入")
        }

        val throwingRecognizer = object : Recognizer {
            override suspend fun templateMatch(
                template: String, threshold: Double, crop: Crop?,
                maskTemplate: Crop?, screenshotScale: Double,
                onMiss: ((Double, Int, Int) -> Unit)?,
            ) = error("调度用例不应做识别")

            override suspend fun detectText(crop: Crop?, threshold: Double) =
                error("调度用例不应做识别")

            override suspend fun findText(target: String, crop: Crop?, threshold: Double) =
                error("调度用例不应做识别")

            override suspend fun classify(model: String, regions: List<Crop>) =
                error("调度用例不应做识别")

            override suspend fun classifyMultiLabel(model: String, regions: List<Crop>) =
                error("调度用例不应做识别")

            override suspend fun colorTemplateMatch(template: String, threshold: Double, crop: Crop?) =
                error("调度用例不应做识别")

            override suspend fun featureMatch(template: String, threshold: Double, crop: Crop?) =
                error("调度用例不应做识别")

            override suspend fun pyramidTemplateMatch(template: String, threshold: Double, crop: Crop?) =
                error("调度用例不应做识别")

            override suspend fun preciseTemplateMatch(template: String, threshold: Double, crop: Crop?) =
                error("调度用例不应做识别")
        }
    }
}
