package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.limbus.recognize.Crop
import com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer
import com.aliothmoon.maadroid.engine.limbus.recognize.TextMatch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LuxcavationActionsTest {
    @Before fun register() {
        ActionRegistry.clearForTest()
        LuxcavationActions.registerAll()
    }

    private class StageRecognizer(vararg rounds: List<TextMatch>) : Recognizer by FakeRecognizer() {
        private val remaining = rounds.toMutableList()
        val requests = mutableListOf<Pair<String, Crop?>>()
        override suspend fun findExpStage(stage: String): List<TextMatch> =
            findText(stage, Crop(250, 180, 1000, 50), .5)
        override suspend fun findText(target: String, crop: Crop?, threshold: Double): List<TextMatch> {
            requests += target to crop
            return if (remaining.isEmpty()) emptyList() else remaining.removeAt(0)
        }
    }

    private fun context(section: String, stage: String, mode: String, reader: StageRecognizer) =
        TestActionContext(
            config = FakeConfig().put(section, "${section}_stage", stage)
                .put(section, "luxcavation_mode", mode),
            recognize = reader,
        )

    @Test fun `经验前导零原样传入 OCR 并按模式点击对应入口`() = runTest {
        for ((mode, expectedY) in listOf("enter" to 480, "skip battle" to 515)) {
            val reader = StageRecognizer(listOf(TextMatch("07", 810, 205, .9)))
            val ctx = context("exp", "07", mode, reader)

            assertEquals(ActionOutcome.Continue, ActionRegistry["exp_select_stage"]!!.execute(ctx))
            assertEquals(listOf("07" to Crop(250, 180, 1000, 50)), reader.requests)
            assertEquals(listOf(820 to expectedY), ctx.fakeInput.clicks())
        }
    }

    /**
     * 纺锤难度标签的搜索区与点击目标都**故意偏离上游**，因为上游那两个值是 PC 布局的：
     *
     * - 上游 `mask=[610, 170, 90, 400]`（luxcavation.py:54）在手机上从标签右边缘外 2px
     *   起裁，只能读到被切碎的 boss 名（实测 `Brazen Bull - Tearful` → `I - Tearfi`），
     *   目标层级永远匹配不上。手机上标签在 x 522~608，故左扩到 500。
     * - 上游点 OCR 命中的标签中心。标签不是可点区域，点它落在行内空白，
     *   表现为"点了分割线却进不了战斗"。改为点该行的 Enter 按钮（x=785），
     *   纵向仍取 OCR 命中的 y，这样翻页后仍然点对行。
     */
    @Test fun `纺锤在手机布局下搜索难度标签并点击该行 Enter`() = runTest {
        for ((mode, expectedY) in listOf("enter" to 480, "skip battle" to 515)) {
            // OCR 命中的是难度标签中心（帧内实测约 565,477），不是 boss 名
            val reader = StageRecognizer(listOf(TextMatch("60", 565, 477, .9)))
            val ctx = context("thread", "60", mode, reader)

            assertEquals(ActionOutcome.Continue, ActionRegistry["thread_select_stage"]!!.execute(ctx))
            assertEquals(listOf("60" to Crop(500, 170, 200, 400)), reader.requests)
            // 末次点击横向必须是 Enter 的 785，而不是命中的 565
            assertEquals(listOf(140 to 330, 370 to expectedY, 785 to 477), ctx.fakeInput.clicks())
        }
    }

    @Test fun `后续上游新增的纯数字关卡仍可选择`() = runTest {
        val reader = StageRecognizer(listOf(TextMatch("111", 650, 390, .9)))
        val ctx = context("thread", "111", "enter", reader)
        assertEquals(ActionOutcome.Continue, ActionRegistry["thread_select_stage"]!!.execute(ctx))
        assertEquals("111", reader.requests.single().first)
    }

    @Test fun `经验初次未找到会滑动并使用新截图的命中位置`() = runTest {
        val reader = StageRecognizer(emptyList(), listOf(TextMatch("09", 530, 205, .9)))
        val ctx = context("exp", "09", "enter", reader)

        assertEquals(ActionOutcome.Continue, ActionRegistry["exp_select_stage"]!!.execute(ctx))
        assertEquals(2, reader.requests.size)
        assertTrue(ctx.fakeInput.events.contains("down(590,310)"))
        assertTrue(ctx.fakeInput.events.contains("up(940,310)"))
        assertEquals(listOf(540 to 480), ctx.fakeInput.clicks())
        assertEquals(listOf(.6), ctx.slept)
    }

    @Test fun `纺锤列表未找到时向下滑动后再选中关卡`() = runTest {
        val reader = StageRecognizer(emptyList(), listOf(TextMatch("60", 645, 380, .9)))
        val ctx = context("thread", "60", "enter", reader)

        assertEquals(ActionOutcome.Continue, ActionRegistry["thread_select_stage"]!!.execute(ctx))
        assertEquals(2, reader.requests.size)
        assertTrue(ctx.fakeInput.events.contains("down(650,325)"))
        assertTrue(ctx.fakeInput.events.contains("up(650,430)"))
        // 翻页后仍点该行的 Enter：横向固定 785，纵向跟随新命中的 y
        assertEquals(listOf(140 to 330, 370 to 480, 785 to 380), ctx.fakeInput.clicks())
        assertEquals(listOf(1.0, 1.0, .6), ctx.slept)
    }

    @Test fun `始终找不到时有限重试后报告失败而不是继续到战斗`() = runTest {
        for (section in listOf("exp", "thread")) {
            val reader = StageRecognizer()
            val ctx = context(section, "60", "enter", reader)
            val result = ActionRegistry["${section}_select_stage"]!!.execute(ctx)

            assertTrue(result is ActionOutcome.Finish)
            assertFalse((result as ActionOutcome.Finish).success)
            assertTrue(result.message.orEmpty().contains("60"))
            assertEquals(7, reader.requests.size)
            assertEquals(6, ctx.slept.count { it == .6 })
            assertEquals(if (section == "exp") emptyList() else listOf(140 to 330, 370 to 480),
                ctx.fakeInput.clicks())
        }
    }

    @Test fun `无效关卡在触控和 OCR 前终止包括跳过战斗模式`() = runTest {
        for (section in listOf("exp", "thread")) for (mode in listOf("enter", "skip battle")) {
            for (stage in listOf("", " ", "-1", "1.5", "Lv60", "6O")) {
                val reader = StageRecognizer()
                val ctx = context(section, stage, mode, reader)
                val result = ActionRegistry["${section}_select_stage"]!!.execute(ctx)

                assertTrue("$section/$mode/$stage", result is ActionOutcome.Finish && !result.success)
                assertTrue(ctx.fakeInput.events.isEmpty())
                assertTrue(reader.requests.isEmpty())
                assertTrue(ctx.slept.isEmpty())
            }
        }
    }

    @Test fun `未知模式不能静默成功且不操作游戏`() = runTest {
        for (section in listOf("exp", "thread")) {
            val reader = StageRecognizer()
            val ctx = context(section, "60", "unsupported", reader)
            val result = ActionRegistry["${section}_select_stage"]!!.execute(ctx)

            assertTrue(result is ActionOutcome.Finish && !result.success)
            assertTrue((result as ActionOutcome.Finish).message.orEmpty().contains("unsupported"))
            assertTrue(ctx.fakeInput.events.isEmpty())
            assertTrue(reader.requests.isEmpty())
        }
    }
}
