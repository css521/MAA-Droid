package com.aliothmoon.maadroid.engine.limbus.pipeline

import com.aliothmoon.maadroid.engine.limbus.action.ActionRegistry
import com.aliothmoon.maadroid.engine.limbus.action.FakeConfig
import com.aliothmoon.maadroid.engine.limbus.action.FakeInput
import com.aliothmoon.maadroid.engine.limbus.action.FakeRecognizer
import com.aliothmoon.maadroid.engine.limbus.action.FakeTemplateIndex
import com.aliothmoon.maadroid.engine.limbus.action.LimbusActions
import com.aliothmoon.maadroid.engine.limbus.action.TestActionContext
import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 拿**上游真实流水线**跑执行器。
 *
 * 前面的用例都用手写的小流水线验证调度语义；这条则证明：133 个真实节点装配完之后，
 * 执行器能在其中走动、动作能被真正调用、识别不中时会按 next/interrupt 收敛而不是卡死。
 * 这是「引擎能跑」与「引擎能编译」之间的差别。
 *
 * 识别用可编程替身而非真 OpenCV：目的是验证**调度与动作**，不是验证匹配算法
 * （那由 TemplateMatcherTest 与真机负责）。上游 clone 不在时跳过。
 */
class UpstreamPipelineRunTest {

    private val upstreamTaskDir =
        File("/Users/css521/project/java/LixAssistantLimbusCompany/lalc_backend/config/task")

    private lateinit var registry: PipelineRegistry

    @Before
    fun setUp() {
        assumeTrue("未找到上游 clone，跳过", upstreamTaskDir.isDirectory)
        ActionRegistry.clearForTest()
        LimbusActions.resetForTest()
        LimbusActions.install()

        val files = upstreamTaskDir.listFiles { f -> f.extension == "json" }!!
            .associate { it.name to it.readText() }
        registry = PipelineRegistry.load(files)
    }

    private fun runnerFor(
        recognizer: FakeRecognizer,
        input: FakeInput,
        config: FakeConfig = FakeConfig(),
        onLog: (String) -> Unit = {},
    ): PipelineRunner {
        val counters = ConcurrentHashMap<String, Int>()
        val nodeRecognizer = NodeRecognizer(recognizer)
        return PipelineRunner(
            registry = registry,
            contextFactory = { name, node, matches ->
                TestActionContext(
                    node = node,
                    nodeName = name,
                    input = input,
                    recognize = recognizer,
                    config = config,
                    templates = FakeTemplateIndex(),
                    recognizeResult = matches,
                ).also { ctx ->
                    // 让计数在节点之间共享，复刻引擎里的行为
                    counters[name]?.let { c -> repeat(c) { ctx.incrementCounter(name) } }
                }
            },
            recognizeGate = { nodeRecognizer.recognize(it) },
            onLog = onLog,
        )
    }

    @Test
    fun `真实流水线装配出 133 个节点`() {
        assertEquals(133, registry.size)
    }

    @Test
    fun `所有入口节点都能起跑且不抛异常`() = runTest {
        // 注意「什么都不命中」**不是**静止态：上游有 7 个 inverse 节点
        // （back_to_init_page 等），识别不中时它们反而命中，于是
        // main_circle_center 与 back_to_init_page 会互相路由。真实运行中
        // back_to_init_page 的动作会改变画面从而跳出，喂静态假识别则会一直转。
        //
        // 所以这里断言的是「不抛异常、且要么正常结束要么被步数保险兜住」，
        // 而不是「必然收敛」—— 后者对真实流水线是个错误的期望。
        val entries = listOf("main", "mirror", "exp", "thread", "mail", "reward")
            .filter { registry[it] != null }
        assertTrue("应能找到上游的入口节点", entries.isNotEmpty())

        for (entry in entries) {
            val reason = runnerFor(FakeRecognizer(), FakeInput()).run(entry)
            if (reason != null) {
                assertTrue(
                    "入口 $entry 只应因步数保险而中止，实际: $reason",
                    reason.contains("死循环"),
                )
            }
        }
    }

    @Test
    fun `静态假识别下的自环由步数保险兜住而非挂死`() = runTest {
        // 上游没有这道保险，遇到识别持续不中会无限空转、用户只能干等。
        // 这条用例把它钉住：保险是有意为之的行为差异，不是实现瑕疵。
        val reason = runnerFor(FakeRecognizer(), FakeInput()).run("main")
        assertNotNull("应当被步数保险中止", reason)
        assertTrue(reason!!.contains("死循环"))
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
