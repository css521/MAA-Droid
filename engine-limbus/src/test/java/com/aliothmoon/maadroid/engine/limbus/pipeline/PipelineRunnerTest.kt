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
    }

    @After
    fun tearDown() = ActionRegistry.clearForTest()

    private fun runner(reg: PipelineRegistry) = PipelineRunner(
        registry = reg,
        contextFactory = { node -> fakeContext(node) },
        recognizeGate = { node -> reg.names().first { reg[it] === node }.let { it in hits } },
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

    @Test
    fun nextTakesFirstDeclaredHitNotBestScore() = runTest {
        val reg = basicRegistry()
        ActionRegistry.register("act_a", recordingAction("A"))
        ActionRegistry.register("act_b", recordingAction("B"))
        // a 与 b 同时命中：必须走声明顺序里的第一个（a）
        hits = setOf("a", "b")

        assertNull(runner(reg).run("main"))
        assertEquals(listOf("A"), trace)
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
        assertEquals(listOf("B"), trace)
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
        assertEquals(listOf("A1", "A2"), trace)
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
        assertEquals(listOf("A"), trace)
        assertTrue("Finish 后应停止", !r.isRunning)
    }

    @Test
    fun actionWithoutBackendIsTreatedAsPureRouting() = runTest {
        val reg = basicRegistry()
        // 不注册任何动作：上游 45 个动作名里 10 个无实现体，属正常情形而非错误
        hits = setOf("a")
        assertNull(runner(reg).run("main"))
        assertTrue("无实现体不应报错", trace.isEmpty())
    }

    @Test
    fun unknownEntryIsReported() = runTest {
        assertEquals(
            "入口节点未注册: nope",
            runner(basicRegistry()).run("nope"),
        )
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
    private fun fakeContext(node: PipelineNode) = TestActionContext(
        node = node,
        input = throwingInput,
        recognize = throwingRecognizer,
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
            ) = error("调度用例不应做识别")

            override suspend fun detectText(crop: Crop?, threshold: Double) =
                error("调度用例不应做识别")

            override suspend fun findText(target: String, crop: Crop?, threshold: Double) =
                error("调度用例不应做识别")

            override suspend fun classify(model: String, regions: List<Crop>) =
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
