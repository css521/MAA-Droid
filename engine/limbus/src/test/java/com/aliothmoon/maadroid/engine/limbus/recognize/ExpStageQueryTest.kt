package com.aliothmoon.maadroid.engine.limbus.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpStageQueryTest {
    @Test fun `手机日志中的 STAGE 9 对应配置09且不选择相邻卡片`() {
        // 754, 2026-09-10T04:53:40Z, frame3836. Both enhanced and original-color OCR read STAGE 9.
        val candidates = listOf(
            TextMatch("STAGE 08", 680, 202, .97),
            TextMatch("STAGE 07", 340, 202, .98),
            TextMatch("STAGE 9", 1022, 202, .84),
            TextMatch(".uxcavation #7 (Pierce)", 449, 199, .92),
            TextMatch(".uxcavation #8 (Pierce)", 790, 199, .91),
            TextMatch("uxcavation #9 (Pierce)", 1132, 199, .93),
        )
        assertEquals(listOf(candidates[2]), candidates.filter { ExpStageQuery("09").matches(it.text) })
    }

    @Test fun `只对完整 STAGE 标签按编号比较并保留原始精确数字`() {
        val query = ExpStageQuery("09")
        for (text in listOf("09", "STAGE 09", "STAGE 9", "stage9", " STAGE  9 ")) {
            assertTrue(text, query.matches(text))
        }
        for (text in listOf("9", "109", "090", "STAGE 109", "STAGE 90", "STAGE 08", "STAGE O9",
            "Luxcavation #9", "Stage 9 Rewards 09", "Backstage 9", "Lv09", "", "Stage 9.5")) {
            assertFalse(text, query.matches(text))
        }
    }

    @Test fun `不猜测混淆字符且支持未来编号`() {
        assertTrue(ExpStageQuery("111").matches("STAGE 111"))
        for (target in listOf("", "O9", "-9", "9.0", "99999999999999")) {
            assertFalse(target, ExpStageQuery(target).matches("STAGE 9"))
        }
    }
}
