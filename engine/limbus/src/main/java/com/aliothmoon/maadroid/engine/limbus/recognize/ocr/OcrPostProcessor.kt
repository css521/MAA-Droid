package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

/** 上游 rapid_ocr.py：先合并检测框，再纠正含数字的短文本。 */
internal object OcrPostProcessor {
    fun process(
        boxes: List<TextBox>,
        mergeX: Boolean = true,
        mergeY: Boolean = true,
    ): List<TextBox> = TextMerge.merge(boxes, mergeX, mergeY).map { box ->
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
