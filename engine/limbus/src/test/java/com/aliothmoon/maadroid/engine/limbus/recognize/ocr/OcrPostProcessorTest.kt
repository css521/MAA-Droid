package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrPostProcessorTest {
    private fun box(text: String, x: Int = 100, y: Int = 200) =
        TextBox(text, x, y, x + 20, y + 20, .83f)

    @Test fun `纺锤 G0 纠正为 60 且坐标和置信度不变`() {
        val source = box("G0")
        assertEquals(source.copy(text = "60"), OcrPostProcessor.process(listOf(source)).single())
        assertEquals("G0", source.text)
    }

    @Test fun `短数字使用上游全部混淆映射`() {
        val examples = mapOf("G0" to "60", "O9" to "09", "Z0" to "20", "S0" to "50",
            "B0" to "80", "I0" to "10", "l0" to "10", "1OG" to "106")
        for ((input, expected) in examples) {
            assertEquals(input, expected, OcrPostProcessor.process(listOf(box(input))).single().text)
        }
    }

    @Test fun `英文单词长数字和未声明的小写混淆不改写`() {
        for (text in listOf("GO", "BIG", "EGO", "G000", "G0 Gift", "Lv. G0", "g0", "o9", "07", "12/12")) {
            assertEquals(text, text, OcrPostProcessor.process(listOf(box(text))).single().text)
        }
    }

    @Test fun `必须在横向合并后判断文本长度避免破坏整行名称`() {
        val processed = OcrPostProcessor.process(listOf(box("G0"), box("Gift", 150)))
        assertEquals("G0 Gift", processed.single().text)
    }

    @Test fun `必须在纵向合并后判断文本长度避免破坏换行名称`() {
        val processed = OcrPostProcessor.process(listOf(box("G0"), box("Gift", y = 225)))
        assertEquals("G0 Gift", processed.single().text)
    }

    @Test fun `关闭合并时仍纠正独立数字框`() {
        val processed = OcrPostProcessor.process(listOf(box("G0"), box("Gift", 150)), false, false)
        assertEquals(listOf("60", "Gift"), processed.map { it.text })
    }

    @Test fun `空检测没有输出`() {
        assertEquals(emptyList<TextBox>(), OcrPostProcessor.process(emptyList()))
    }

    @Test fun `上游先过滤空白与低分单框避免合并后抹掉正确关卡`() {
        val stage = box("09").copy(confidence = .97f)
        val noise = box("P", 50).copy(confidence = .28f)
        assertEquals(listOf(stage), OcrPostProcessor.process(listOf(noise, stage)))
        assertEquals(emptyList<TextBox>(), OcrPostProcessor.process(listOf(box(""), box("  "), noise)))
    }

    @Test fun `上游全局阈值含等号且纵向干扰也先过滤`() {
        val stage = box("09").copy(confidence = .5f)
        val label = box("STAGE", y = 180).copy(confidence = .499f)
        assertEquals(listOf(stage), OcrPostProcessor.process(listOf(label, stage)))
        assertEquals(listOf(stage), OcrPostProcessor.process(listOf(label, stage), false, false))
    }
}
