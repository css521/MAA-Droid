package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextQueryTest {
    @Test fun `09保留前导零并拒绝其他关卡与猜测`() {
        val query = OcrTextQuery("09")
        assertTrue(query.isNumber)
        for (text in listOf("09", "STAGE 09", "Lv.09")) assertTrue(text, query.matches(text))
        for (text in listOf("9", "109", "090", "O9", "08", "", "0 9")) assertFalse(text, query.matches(text))
    }

    @Test fun `数字规则不限现有关卡编号`() {
        assertTrue(OcrTextQuery("111").matches("Lv111"))
        assertFalse(OcrTextQuery("60").matches("160"))
    }

    @Test fun `普通名称仍按字面包含匹配且不当作正则表达式`() {
        val query = OcrTextQuery("E.G.O")
        assertFalse(query.isNumber)
        assertTrue(query.matches("E.G.O Gift"))
        assertFalse(query.matches("ExGyO Gift"))
        assertFalse(OcrTextQuery("").matches("09"))
    }
}
