package com.aliothmoon.maadroid.engine.limbus.action

import android.view.KeyEvent
import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 基础动作的行为验证。
 *
 * 重点不在「代码跑通」，而在几个**改了会静默出错**的语义：坐标目标与模板目标的分流、
 * repeat_interval 的 -0.5 修正、wait_* 的退出条件、report_error 的失败传播。
 */
class BaseActionsTest {

    @Before
    fun setUp() {
        ActionRegistry.clearForTest()
        LimbusActions.resetForTest()
        LimbusActions.install()
    }

    @Test
    fun `click 用坐标目标时按 target 加 offset 点击`() = runTest {
        val ctx = TestActionContext(
            node = nodeWith("""{"target":[100,200],"target_offset":[5,-5]}""")
        )
        ActionRegistry["click"]!!.execute(ctx)
        assertEquals(listOf(105 to 195), ctx.fakeInput.clicks())
    }

    @Test
    fun `click 用模板目标时点路由识别留下的坐标`() = runTest {
        // 上游语义：字符串 target 取 params["recognize_result"]，即把本节点带到
        // 这里的那次路由识别的结果，而不是重新截图匹配
        val ctx = TestActionContext(
            node = nodeWith("""{"target":"some_button"}"""),
            recognizeResult = listOf(Match(640, 360, 0.95)),
        )
        ActionRegistry["click"]!!.execute(ctx)
        assertEquals(listOf(640 to 360), ctx.fakeInput.clicks())
        assertTrue("不该重新截图匹配", ctx.fakeRecognizer.templateCalls.isEmpty())
    }

    @Test
    fun `click 用模板目标时叠加 offset`() = runTest {
        val ctx = TestActionContext(
            node = nodeWith("""{"target":"some_button","target_offset":[10,-20]}"""),
            recognizeResult = listOf(Match(640, 360, 0.95)),
        )
        ActionRegistry["click"]!!.execute(ctx)
        assertEquals(listOf(650 to 340), ctx.fakeInput.clicks())
    }

    @Test
    fun `click 没配 target 时点识别命中的位置`() = runTest {
        // 回归用例：上游 target 的缺省值是空字符串，所以「没配 target」等价于
        // 「点识别结果」。实测上游 23 个 click 节点里有 10 个就是这么写的
        // （只配 template 与 target_offset），当成「无目标」会让它们静默失效。
        val ctx = TestActionContext(
            node = nodeWith("""{"template":"red_exclaimation","target_offset":[-10,10]}"""),
            recognizeResult = listOf(Match(1100, 100, 0.93)),
        )
        ActionRegistry["click"]!!.execute(ctx)
        assertEquals(listOf(1090 to 110), ctx.fakeInput.clicks())
    }

    @Test
    fun `click 没有识别结果时不点任何位置`() = runTest {
        // 入口节点没经过路由识别，recognizeResult 为空 —— 此时不该乱点
        val ctx = TestActionContext(node = nodeWith("""{"target":"missing"}"""))
        ActionRegistry["click"]!!.execute(ctx)
        assertTrue("没有坐标就不该乱点", ctx.fakeInput.clicks().isEmpty())
    }

    @Test
    fun `click 的 repeat_interval 要减掉 0_5 秒`() = runTest {
        // 上游 max(interval - 0.5, 0)：点击自身已耗时约 0.5 秒，不减会让节奏偏慢
        val ctx = TestActionContext(
            node = nodeWith("""{"target":[10,10],"repeat":3,"repeat_interval":0.8}""")
        )
        ActionRegistry["click"]!!.execute(ctx)
        assertEquals(3, ctx.fakeInput.clicks().size)
        assertEquals(listOf(0.3, 0.3), ctx.slept.map { (it * 10).toInt() / 10.0 })
    }

    @Test
    fun `key 按名字映射到 Android keycode 并可重复`() = runTest {
        val ctx = TestActionContext(node = nodeWith("""{"key":"enter","repeat":2}"""))
        ActionRegistry["key"]!!.execute(ctx)
        assertEquals(listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ENTER), ctx.fakeInput.keyPresses())
    }

    @Test
    fun `swipe 起点为模板时同样取路由识别的坐标`() = runTest {
        val ctx = TestActionContext(
            node = nodeWith("""{"begin":"handle","end":[800,300]}"""),
            recognizeResult = listOf(Match(200, 300, 0.9)),
        )
        ActionRegistry["swipe"]!!.execute(ctx)
        assertEquals("down(200,300)", ctx.fakeInput.events.first())
        assertEquals("up(800,300)", ctx.fakeInput.events.last())
    }

    @Test
    fun `wait_disappear 直到模板消失才返回`() = runTest {
        val ctx = TestActionContext(node = nodeWith("""{"template":"loading"}"""))
        ctx.fakeRecognizer.onTemplateSequence(
            "loading",
            listOf(Match(1, 1, 0.9)),
            listOf(Match(1, 1, 0.9)),
            emptyList(),
        )
        ActionRegistry["wait_disappear"]!!.execute(ctx)
        assertEquals("应轮询到第三次才发现消失", 2, ctx.slept.size)
    }

    @Test
    fun `wait_appear 命中后立即返回`() = runTest {
        val ctx = TestActionContext(node = nodeWith("""{"template":"dialog"}"""))
        ctx.fakeRecognizer.onTemplate("dialog", Match(1, 1, 0.9))
        ActionRegistry["wait_appear"]!!.execute(ctx)
        assertTrue("已出现就不该再等", ctx.slept.isEmpty())
    }

    @Test
    fun `wait_appear 超过 max_wait_time 后放行`() = runTest {
        val ctx = TestActionContext(
            node = nodeWith("""{"template":"never","max_wait_time":3,"check_interval":1}""")
        )
        ActionRegistry["wait_appear"]!!.execute(ctx)
        // 等满 3 秒就放行，让后续节点的识别去决定走向，而不是无限挂住
        assertEquals(3, ctx.slept.size)
    }

    @Test
    fun `report_error 以失败结束流水线并带上原因`() = runTest {
        val ctx = TestActionContext(node = nodeWith("""{"error_msg":"账号被封"}"""))
        val outcome = ActionRegistry["report_error"]!!.execute(ctx)
        assertTrue(outcome is ActionOutcome.Finish)
        outcome as ActionOutcome.Finish
        assertEquals(false, outcome.success)
        assertEquals("账号被封", outcome.message)
    }

    @Test
    fun `check_out_update 自增计数供队伍轮换取用`() = runTest {
        val ctx = TestActionContext(nodeName = "mirror_check")
        ActionRegistry["check_out_update"]!!.execute(ctx)
        ActionRegistry["check_out_update"]!!.execute(ctx)
        assertEquals(2, ctx.counterOf("mirror_check"))
    }
}
