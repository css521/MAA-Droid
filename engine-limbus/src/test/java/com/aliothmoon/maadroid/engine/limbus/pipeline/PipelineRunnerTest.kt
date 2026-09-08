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
        contextFactory = { node -> FakeContext(node) },
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

    private class FakeContext(override val node: PipelineNode) : ActionContext {
        override val input = throwingInput
        override val recognize = throwingRecognizer
        override val config = emptyConfig
        override fun log(message: String) = Unit
        override fun ensureActive() = Unit
        override suspend fun delay(seconds: Double) = Unit
    }

    private companion object {
        // 这些用例只验证调度，不触碰识别与输入；被调用即说明测试写错了
        val throwingInput = object : com.aliothmoon.maadroid.engine.InputSink {
            override fun touchDown(x: Int, y: Int, contact: Int) = error("不应调用")
            override fun touchMove(x: Int, y: Int, contact: Int) = error("不应调用")
            override fun touchUp(x: Int, y: Int, contact: Int) = error("不应调用")
            override fun touchCancel() = error("不应调用")
            override fun keyDown(keyCode: Int) = error("不应调用")
            override fun keyUp(keyCode: Int) = error("不应调用")
        }
        val throwingRecognizer = object : com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer {
            override suspend fun templateMatch(
                template: String, threshold: Double,
                crop: com.aliothmoon.maadroid.engine.limbus.recognize.Crop?,
                maskTemplate: com.aliothmoon.maadroid.engine.limbus.recognize.Crop?,
                screenshotScale: Double,
            ) = error("不应调用")
            override suspend fun detectText(crop: com.aliothmoon.maadroid.engine.limbus.recognize.Crop?, threshold: Double) = error("不应调用")
            override suspend fun findText(target: String, crop: com.aliothmoon.maadroid.engine.limbus.recognize.Crop?, threshold: Double) = error("不应调用")
            override suspend fun classify(model: String, regions: List<com.aliothmoon.maadroid.engine.limbus.recognize.Crop>) = error("不应调用")
            override suspend fun colorTemplateMatch(template: String, threshold: Double, crop: com.aliothmoon.maadroid.engine.limbus.recognize.Crop?) = error("不应调用")
            override suspend fun featureMatch(template: String, threshold: Double, crop: com.aliothmoon.maadroid.engine.limbus.recognize.Crop?) = error("不应调用")
            override suspend fun pyramidTemplateMatch(template: String, threshold: Double, crop: com.aliothmoon.maadroid.engine.limbus.recognize.Crop?) = error("不应调用")
            override suspend fun preciseTemplateMatch(template: String, threshold: Double, crop: com.aliothmoon.maadroid.engine.limbus.recognize.Crop?) = error("不应调用")
        }
        val emptyConfig = object : com.aliothmoon.maadroid.engine.limbus.action.LimbusConfig {
            override fun int(section: String, key: String, default: Int) = default
            override fun bool(section: String, key: String, default: Boolean) = default
            override fun str(section: String, key: String, default: String) = default
            override fun list(section: String, key: String) = emptyList<String>()
        }
    }
}
