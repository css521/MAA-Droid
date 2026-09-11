package com.maadroid.app.engine.limbus.recognize.ocr

/**
 * PP-OCR 识别头的 CTC 解码。
 *
 * 字符表**不需要随资源包另发**：`ch_PP-OCRv5_rec_mobile.onnx` 把它嵌在模型的
 * `metadata_props["character"]` 里（换行分隔，18383 个字符）。这一点很关键 ——
 * 否则就要从 PaddleOCR 单独取一份字典进仓库，还得自行担保它与模型版本对得上。
 *
 * 字符表的组装顺序照抄 rapidocr 的 `CTCLabelDecode.get_character`，一步都不能换：
 * 1. 取模型里的 18383 个字符
 * 2. 在**末尾**追加空格 → 18384
 * 3. 在**开头**插入 blank → 18385
 *
 * 18385 正是该模型输出层的类别数（已用 onnx 读出 output shape 的最后一维核对）。
 * 顺序错了不会报错，只会让每个字都偏移一位、识别结果整体变成乱码。
 */
class CtcDecoder(characters: List<String>) {

    /** 下标 → 字符，下标 0 是 blank */
    private val table: List<String> = buildList(characters.size + 2) {
        addAll(characters)
        add(SPACE)
        add(0, BLANK)
    }

    val classCount: Int get() = table.size

    /**
     * 解码一条时间序列。
     *
     * @param logits 形状 `[time, classCount]` 的展平数组（按行优先）
     * @return 文本与平均置信度
     *
     * 两步都来自上游：先折叠**连续重复**的下标（CTC 的标准去重），再剔除 blank。
     * 顺序不可交换 —— 先剔 blank 会把被 blank 分隔的两个相同字符错误地折成一个。
     */
    fun decode(logits: FloatArray, timeSteps: Int): OcrText {
        if (timeSteps <= 0 || table.isEmpty()) return OcrText("", 0f)
        val classes = table.size
        require(logits.size >= timeSteps * classes) {
            "logits 长度 ${logits.size} 不足 ${timeSteps * classes}"
        }

        val sb = StringBuilder()
        var confSum = 0f
        var confCount = 0
        var prevIndex = -1

        for (t in 0 until timeSteps) {
            val base = t * classes
            var bestIdx = 0
            var bestVal = logits[base]
            for (c in 1 until classes) {
                val v = logits[base + c]
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = c
                }
            }

            val isRepeat = bestIdx == prevIndex
            prevIndex = bestIdx
            if (isRepeat) continue
            if (bestIdx == BLANK_INDEX) continue

            sb.append(table[bestIdx])
            confSum += bestVal
            confCount++
        }

        // 上游在没有任何字符时把置信度记为 0 而不是 NaN
        val conf = if (confCount == 0) 0f else confSum / confCount
        return OcrText(sb.toString(), conf)
    }

    companion object {
        const val BLANK = "blank"
        const val BLANK_INDEX = 0
        const val SPACE = " "

        /** 模型元数据里的字符表是换行分隔的；空行要保留位置外的过滤 */
        fun parseCharacters(metadata: String): List<String> =
            metadata.split('\n').filter { it.isNotEmpty() }

        /** 该模型输出层的类别数，已用 onnx 核对 */
        const val PPOCR_V5_CLASS_COUNT = 18385
    }
}

/** 一段识别出的文本与其平均置信度 */
data class OcrText(val text: String, val confidence: Float)
