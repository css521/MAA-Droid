package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OCR 输入尺寸与检测框几何。
 *
 * 期望值由 Python 复现 rapidocr 的同一算式取得。这些计算算错都不会报错，
 * 只会让检测框偏移、文字被截断或裁到镜像区域，最终表现为识别结果乱码。
 */
class OcrGeometryTest {

    // ---- 检测输入尺寸 ----

    @Test
    fun `短边不足时放大到 736 并对齐 32`() {
        // limit_type=min 的语义是「短边不小于 736」
        assertEquals(992 to 736, OcrGeometry.detInputSize(640, 480))
        assertEquals(1472 to 736, OcrGeometry.detInputSize(100, 50))
    }

    @Test
    fun `边狱的 1280x720 会被放大一点以满足短边要求`() {
        // 720 < 736，故整体放大后对齐到 32 的倍数
        assertEquals(1312 to 736, OcrGeometry.detInputSize(1280, 720))
    }

    @Test
    fun `短边已达标时不缩放只对齐`() {
        assertEquals(1280 to 736, OcrGeometry.detInputSize(1280, 736))
        assertEquals(1984 to 1504, OcrGeometry.detInputSize(2000, 1500))
    }

    @Test
    fun `对齐 32 用四舍六入五取偶而非四舍五入`() {
        // 2000/32 = 62.5：上游 Python 的 round() 取偶得 62 → 1984。
        // 若按常见的四舍五入会得 63 → 2016，比上游宽 32px，
        // 检测模型读到的图尺寸就与上游不同
        assertEquals(1984, OcrGeometry.detInputSize(2000, 1500).first)
        // 2016/32 = 63.0 不涉及取半，正常对齐
        assertEquals(2016, OcrGeometry.detInputSize(2016, 1500).first)
        // 2032/32 = 63.5 → 取偶得 64 → 2048
        assertEquals(2048, OcrGeometry.detInputSize(2032, 1500).first)
    }

    @Test
    fun `退化输入不产生非法尺寸`() {
        // 网络要求边长是 32 的倍数且为正
        assertEquals(32 to 32, OcrGeometry.detInputSize(0, 0))
        assertEquals(32 to 32, OcrGeometry.detInputSize(-5, 10))
    }

    // ---- unclip 距离 ----

    @Test
    fun `unclip 距离为面积乘比例除周长`() {
        assertEquals(13.3333, OcrGeometry.unclipDistance(100.0, 20.0), 1e-4)
        assertEquals(20.0, OcrGeometry.unclipDistance(50.0, 50.0), 1e-4)
        assertEquals(11.1628, OcrGeometry.unclipDistance(200.0, 15.0), 1e-4)
        assertEquals(6.7833, OcrGeometry.unclipDistance(37.0, 11.0), 1e-4)
    }

    @Test
    fun `退化尺寸的 unclip 距离为零而不是除零`() {
        assertEquals(0.0, OcrGeometry.unclipDistance(0.0, 0.0), 1e-9)
    }

    // ---- 识别输入宽度 ----

    @Test
    fun `识别批宽至少 320 并随长文本放大而不压缩字形`() {
        // 来自上游 uv.lock 锁定的 RapidOCR 3.8.4 TextRecognizer 的计算式。
        assertEquals(320, OcrGeometry.recBatchWidth(listOf(10 to 20)))
        assertEquals(320, OcrGeometry.recBatchWidth(listOf(100 to 20)))
        assertEquals(576, OcrGeometry.recBatchWidth(listOf(300 to 25)))
        assertEquals(576, OcrGeometry.recBatchWidth(listOf(100 to 20, 300 to 25)))
        assertEquals(4800, OcrGeometry.recBatchWidth(listOf(2000 to 20)))
        assertEquals(320, OcrGeometry.recBatchWidth(emptyList()))
    }

    @Test
    fun `缩放宽度按宽高比向上取整`() {
        // 48 * (100/20) = 240
        assertEquals(240, OcrGeometry.recResizedWidth(100, 20, 320))
        // 48 * (300/25) = 576 > 320，截断到批宽
        assertEquals(320, OcrGeometry.recResizedWidth(300, 25, 320))
    }

    @Test
    fun `向上取整避免把最后一个字切掉半边`() {
        // 48 * (7/20) = 16.8 → 17 而不是 16
        assertEquals(17, OcrGeometry.recResizedWidth(7, 20, 320))
    }

    @Test
    fun `退化输入的缩放宽度至少为 1`() {
        assertEquals(1, OcrGeometry.recResizedWidth(0, 20, 320))
        assertEquals(1, OcrGeometry.recResizedWidth(10, 0, 320))
    }

    // ---- 四点排序 ----

    @Test
    fun `四点被整理成左上右上右下左下`() {
        // 乱序输入
        val ordered = OcrGeometry.orderClockwise(
            listOf(
                20f to 30f,   // 右下
                0f to 0f,     // 左上
                20f to 0f,    // 右上
                0f to 30f,    // 左下
            )
        )
        assertEquals(listOf(0f to 0f, 20f to 0f, 20f to 30f, 0f to 30f), ordered)
    }

    @Test
    fun `点数不为四时原样返回`() {
        val three = listOf(0f to 0f, 1f to 1f, 2f to 2f)
        assertEquals(three, OcrGeometry.orderClockwise(three))
    }
}
