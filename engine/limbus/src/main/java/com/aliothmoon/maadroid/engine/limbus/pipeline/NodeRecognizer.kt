package com.aliothmoon.maadroid.engine.limbus.pipeline

import com.aliothmoon.maadroid.engine.limbus.recognize.Crop
import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer

/**
 * 一次节点识别的结果。
 *
 * [matches] 要留着：上游 `do_recognize` 把命中结果写回 `params["recognize_result"]`，
 * 随后该节点的 `click` / `swipe` 在 target 为字符串时直接取它的第一个坐标，
 * **而不是重新截图匹配**。当前上游流水线里 13 个 click 全用坐标数组、无一用字符串，
 * 所以这条路径今天走不到；但保留它才不会在上游改成字符串 target 时静默漂移
 * （重新匹配会多一次截图，且期间画面可能已变）。
 */
data class RecognizeOutcome(val hit: Boolean, val matches: List<Match> = emptyList()) {
    companion object {
        val MISS = RecognizeOutcome(hit = false)
        val DIRECT_HIT = RecognizeOutcome(hit = true)
    }
}

/**
 * 节点识别，移植上游 `TaskNode.do_recognize` 与 `get_recognition_params`。
 *
 * 实测上游 v5.0.0 的 133 个节点只用两种识别：`direct`(56) 与 `template_match`(77)，
 * 且 template 一律是单个字符串、只出现过一个非默认阈值 0.9、4 个节点带 mask。
 * 另两种（color / feature）在 JSON 里没出现但注册表支持，一并实现以免上游启用时报错。
 *
 * 返回值语义照抄：`enable && (hit xor inverse)`。
 * - `enable=false` 恒不命中 —— 上游 check 节点会把目标节点置 false 实现「用完即弃」
 * - `inverse` 是「识别不中才算命中」，用于「不在主界面就先回主界面」这类判断
 */
class NodeRecognizer(
    private val recognizer: Recognizer,
    private val onUnknownRecognition: (String) -> Unit = {},
) {

    suspend fun recognize(node: PipelineNode): RecognizeOutcome {
        val raw = when (node.recognition) {
            PipelineNode.RECOGNITION_DIRECT -> RecognizeOutcome.DIRECT_HIT

            PipelineNode.RECOGNITION_TEMPLATE_MATCH ->
                byTemplate(node, DEFAULT_TEMPLATE_THRESHOLD) { t, th, crop ->
                    recognizer.templateMatch(t, th, crop)
                }

            PipelineNode.RECOGNITION_COLOR_TEMPLATE_MATCH ->
                byTemplate(node, DEFAULT_COLOR_FEATURE_THRESHOLD) { t, th, crop ->
                    recognizer.colorTemplateMatch(t, th, crop)
                }

            PipelineNode.RECOGNITION_FEATURE_MATCH ->
                byTemplate(node, DEFAULT_COLOR_FEATURE_THRESHOLD) { t, th, crop ->
                    recognizer.featureMatch(t, th, crop)
                }

            else -> {
                // 上游此处 raise ValueError 直接炸掉流水线。这里降为「不命中」+ 告警：
                // 未知识别方式意味着资源包比 App 新，而那本该由兼容门闸在装载前拦住；
                // 真漏到运行期时，让这一个分支走不通远好过整条任务链崩掉。
                onUnknownRecognition("未知识别方式 ${node.recognition}，该节点按不命中处理")
                RecognizeOutcome.MISS
            }
        }

        val hit = if (node.inverse) !raw.hit else raw.hit
        return RecognizeOutcome(hit = node.enable && hit, matches = raw.matches)
    }

    private suspend fun byTemplate(
        node: PipelineNode,
        defaultThreshold: Double,
        match: suspend (String, Double, Crop?) -> List<Match>,
    ): RecognizeOutcome {
        // 上游 template 一律单串；这里仍按多个处理，任一命中即命中，
        // 以便上游将来改成数组时无需改动
        val templates = node.templates()
        if (templates.isEmpty()) return RecognizeOutcome.MISS

        val threshold = node.num("threshold") ?: defaultThreshold
        val crop = node.ints("mask")?.takeIf { it.size >= 4 }
            ?.let { Crop(it[0], it[1], it[2], it[3]) }

        for (t in templates) {
            val found = match(t, threshold, crop)
            if (found.isNotEmpty()) return RecognizeOutcome(hit = true, matches = found)
        }
        return RecognizeOutcome.MISS
    }

    private companion object {
        /** 上游 get_recognition_params 里 template_match 的默认阈值 */
        const val DEFAULT_TEMPLATE_THRESHOLD = 0.85

        /** color_template_match 与 feature_match 的默认阈值 */
        const val DEFAULT_COLOR_FEATURE_THRESHOLD = 0.7
    }
}
