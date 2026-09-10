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

    @Test fun `纺锤先切副本再选模式最后点击 OCR 返回的全屏坐标`() = runTest {
        for ((mode, expectedY) in listOf("enter" to 480, "skip battle" to 515)) {
            val reader = StageRecognizer(listOf(TextMatch("60", 656, 399, .9)))
            val ctx = context("thread", "60", mode, reader)

            assertEquals(ActionOutcome.Continue, ActionRegistry["thread_select_stage"]!!.execute(ctx))
            assertEquals(listOf("60" to Crop(610, 170, 90, 400)), reader.requests)
            assertEquals(listOf(140 to 330, 370 to expectedY, 656 to 399), ctx.fakeInput.clicks())
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
        assertEquals(listOf(140 to 330, 370 to 480, 645 to 380), ctx.fakeInput.clicks())
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
