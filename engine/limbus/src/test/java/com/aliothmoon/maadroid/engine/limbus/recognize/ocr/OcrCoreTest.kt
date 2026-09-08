package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 可验证部分的测试：CTC 解码与检测框合并。
 *
 * 两者都是「算错不报错、只让文本变成乱码或拼错」的逻辑，且都不需要真机 ——
 * 期望值由 Python 复现 rapidocr 的同一算法算出后交叉验证。
 */
class OcrCoreTest {

    /** 三个字符的小字符表，组装后为 [blank, A, B, C, space]，共 5 类 */
    private val decoder = CtcDecoder(listOf("A", "B", "C"))

    /** 把「每步选哪个下标」翻成 one-hot logits，便于直接表达 argmax 序列 */
    private fun logitsOf(indices: List<Int>, confs: List<Float>? = null): FloatArray {
        val classes = decoder.classCount
        val out = FloatArray(indices.size * classes) { -10f }
        for ((t, idx) in indices.withIndex()) {
            out[t * classes + idx] = confs?.get(t) ?: 1f
        }
        return out
    }

    // ---- 字符表组装 ----

    @Test
    fun `字符表为 blank 在前空格在后`() {
        // 顺序错了不会报错，只会让每个字整体偏移一位、结果变成乱码
        assertEquals(5, decoder.classCount)
        val decoded = decoder.decode(logitsOf(listOf(0, 1, 4)), timeSteps = 3)
        // 下标 0 是 blank 被剔除，1 是 A，4 是空格
        assertEquals("A ", decoded.text)
    }

    @Test
    fun `真实模型的类别数与字符表对得上`() {
        // 18383 个字符 + 空格 + blank = 18385，正是该模型输出层的类别数
        val fake = List(18383) { "x" }
        assertEquals(CtcDecoder.PPOCR_V5_CLASS_COUNT, CtcDecoder(fake).classCount)
    }

    @Test
    fun `解析模型元数据里的字符表`() {
        // 元数据是换行分隔的，末尾可能带空行
        val chars = CtcDecoder.parseCharacters("一\n乙\n二\n")
        assertEquals(listOf("一", "乙", "二"), chars)
    }

    // ---- CTC 解码（期望值由 Python 复现 rapidocr 算法取得）----

    @Test
    fun `折叠连续重复但保留被 blank 分隔的相同字符`() {
        // A,A,blank,A -> "AA"：前两个折成一个，blank 之后的 A 是新字符。
        // 若先剔 blank 再去重，会错误地折成 "A"
        assertEquals("AA", decoder.decode(logitsOf(listOf(1, 1, 0, 1)), 4).text)
    }

    @Test
    fun `连续重复折叠为一个`() {
        assertEquals("ABC", decoder.decode(logitsOf(listOf(1, 2, 2, 3)), 4).text)
    }

    @Test
    fun `全是 blank 时得到空串`() {
        val r = decoder.decode(logitsOf(listOf(0, 0, 0)), 3)
        assertEquals("", r.text)
        // 上游在没有任何字符时把置信度记为 0 而不是 NaN
        assertEquals(0f, r.confidence, 1e-6f)
    }

    @Test
    fun `置信度是留下来的字符的均值`() {
        // 序列 A(0.9), blank(0.1), B(0.7)：blank 被剔除，均值 = (0.9+0.7)/2 = 0.8
        val r = decoder.decode(
            logitsOf(listOf(1, 0, 2), confs = listOf(0.9f, 0.1f, 0.7f)),
            3,
        )
        assertEquals("AB", r.text)
        assertEquals(0.8f, r.confidence, 1e-5f)
    }

    @Test
    fun `空序列与数据不足都安全处理`() {
        assertEquals("", decoder.decode(FloatArray(0), 0).text)
        val e = runCatching { decoder.decode(FloatArray(3), 5) }.exceptionOrNull()
        assertTrue("长度不足应明确失败", e is IllegalArgumentException)
    }

    // ---- 检测框合并 ----

    private fun box(text: String, l: Int, t: Int, r: Int, b: Int, conf: Float = 0.9f) =
        TextBox(text, l, t, r, b, conf)

    @Test
    fun `同一行的碎片被横向拼成整段`() {
        // PP-OCR 会把一行字拆成多个框；不拼起来，「Burning Branch」两半
        // 都匹配不上任何已知饰品名
        val merged = TextMerge.merge(
            listOf(
                box("Burning", 100, 190, 180, 210, 0.95f),
                box("Branch", 200, 190, 270, 210, 0.88f),
            )
        )
        assertEquals(1, merged.size)
        assertEquals("Burning Branch", merged[0].text)
        // 包围盒取并集
        assertEquals(100, merged[0].left)
        assertEquals(270, merged[0].right)
        // 置信度取组内最小 —— 一处认不准，整段就不该算高可信
        assertEquals(0.88f, merged[0].confidence, 1e-6f)
    }

    @Test
    fun `横向拼接按 x 排序而非按输入顺序`() {
        val merged = TextMerge.merge(
            listOf(
                box("Branch", 200, 190, 270, 210),
                box("Burning", 100, 190, 180, 210),
            )
        )
        assertEquals("Burning Branch", merged[0].text)
    }

    @Test
    fun `距离过远的文本不合并`() {
        val merged = TextMerge.merge(
            listOf(box("A", 100, 190, 150, 210), box("B", 400, 190, 450, 210))
        )
        assertEquals("相距 300px 远超阈值 100，不该合并", 2, merged.size)
    }

    @Test
    fun `纵向相邻的两行被拼成一段`() {
        // 中心 y 相距 25，在阈值 30 之内
        val merged = TextMerge.merge(
            listOf(box("first", 100, 100, 200, 120), box("second", 100, 125, 200, 145))
        )
        assertEquals(1, merged.size)
        assertEquals("first second", merged[0].text)
        assertEquals(100, merged[0].top)
        assertEquals(145, merged[0].bottom)
    }

    @Test
    fun `纵向拼接按 y 排序`() {
        val merged = TextMerge.merge(
            listOf(box("second", 100, 125, 200, 145), box("first", 100, 100, 200, 120))
        )
        assertEquals("first second", merged[0].text)
    }

    @Test
    fun `纵向间距超过阈值则不合并`() {
        // 中心 y 相距 40，超出阈值 30 —— 不是同一段文字的换行
        val merged = TextMerge.merge(
            listOf(box("first", 100, 100, 200, 120), box("second", 100, 140, 200, 160))
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `先横后纵不能反过来`() {
        // 横纵两步的阈值不对称（横向容许 dx<=100，纵向只容许 dx<=80），
        // 所以顺序会改变结果。构造一例把差别放出来：
        //   A(100,100) B(195,100) C(100,125)
        //   A-B 横向相距 95：横向可合（<=100），纵向不可合（>80）
        //
        // 先横后纵：A+B 拼成一行，再与 C 纵向拼 → 一段 "A B C"
        // 先纵后横：A 与 C 先纵向拼成 "A C"，B 因 dy 差 12 超过横向阈值 10 落单 → 两段
        val merged = TextMerge.merge(
            listOf(
                box("A", 90, 90, 110, 110),
                box("B", 185, 90, 205, 110),
                box("C", 90, 115, 110, 135),
            )
        )
        assertEquals("顺序反了会把 B 落下", 1, merged.size)
        assertEquals("A B C", merged[0].text)
    }

    @Test
    fun `二维碎片按行拼再按段拼`() {
        // 上下两行各两个碎片，拼成一段
        val merged = TextMerge.merge(
            listOf(
                box("A", 100, 100, 140, 120),
                box("B", 150, 100, 190, 120),
                box("C", 100, 125, 140, 145),
                box("D", 150, 125, 190, 145),
            )
        )
        assertEquals(1, merged.size)
        assertEquals("A B C D", merged[0].text)
    }

    @Test
    fun `可分别关闭横纵合并`() {
        val two = listOf(box("A", 100, 190, 150, 210), box("B", 160, 190, 210, 210))
        assertEquals(1, TextMerge.merge(two, mergeX = true, mergeY = false).size)
        assertEquals(2, TextMerge.merge(two, mergeX = false, mergeY = false).size)
    }

    @Test
    fun `只跟基准比而不做传递合并`() {
        // 三个间距均匀的独立文本：A-B 相距 90（可合），B-C 也 90，但 A-C 相距 180。
        // 只跟基准比时 A 收 B（90<=100），C 距 A 180 不收，故 C 独立。
        // 若做传递闭包，会把一长串独立文本全并成一段
        val merged = TextMerge.merge(
            listOf(
                box("A", 100, 190, 120, 210),
                box("B", 190, 190, 210, 210),
                box("C", 280, 190, 300, 210),
            ),
            mergeY = false,
        )
        assertEquals(2, merged.size)
        assertEquals("A B", merged[0].text)
        assertEquals("C", merged[1].text)
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(TextMerge.merge(emptyList()).isEmpty())
    }
}
