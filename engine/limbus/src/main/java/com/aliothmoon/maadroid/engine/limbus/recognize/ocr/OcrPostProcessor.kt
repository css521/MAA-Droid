package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

/** RapidOCR 先按 Global.text_score 过滤单框，再由 rapid_ocr.py 合并、纠正短数字。 */
internal object OcrPostProcessor {
    private const val TEXT_SCORE = .5f

    fun process(
        boxes: List<TextBox>,
        mergeX: Boolean = true,
        mergeY: Boolean = true,
    ): List<TextBox> = TextMerge.merge(
        // 必须在合并前过滤：旁边一个低分误识别框会把正确的关卡号一起拉到阈值以下。
        boxes.filter { it.text.isNotBlank() && it.confidence >= TEXT_SCORE }, mergeX, mergeY,
    ).map { box ->
        box.copy(text = correctShortNumber(box.text))
    }

    private fun correctShortNumber(text: String): String {
        // 纺锤关卡的 60 可能识别成 G0；长文本和纯字母单词必须保留原样。
        if (text.length > 3 || text.none { it.isDigit() }) return text
        return buildString(text.length) {
            for (char in text) append(when (char) {
                'G' -> '6'
                'O' -> '0'
                'Z' -> '2'
                'S' -> '5'
                'B' -> '8'
                'I', 'l' -> '1'
                else -> char
            })
        }
    }
}
