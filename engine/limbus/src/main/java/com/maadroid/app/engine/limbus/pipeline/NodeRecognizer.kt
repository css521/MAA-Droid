package com.maadroid.app.engine.limbus.pipeline

import com.maadroid.app.engine.limbus.recognize.Crop
import com.maadroid.app.engine.limbus.recognize.Match
import com.maadroid.app.engine.limbus.recognize.Recognizer

/**
 * 一次节点识别的结果。
 *
 * [matches] 要留着：上游 `do_recognize` 把命中结果写回 `params["recognize_result"]`，
 * 随后该节点的 `click` / `swipe` 在 target 为字符串时直接取它的第一个坐标，
 * **而不是重新截图匹配**。当前上游流水线里 13 个 click 全用坐标数组、无一用字符串，
 * 所以这条路径今天走不到；但保留它才不会在上游改成字符串 target 时静默漂移
 * （重新匹配会多一次截图，且期间画面可能已变）。
 */
data class RecognizeOutcome(
    val hit: Boolean,
    val matches: List<Match> = emptyList(),
    /** 未命中时的定量原因，仅供诊断；命中时为 null。见 [TemplateMiss] */
    val miss: TemplateMiss? = null,
) {
    companion object {
        val MISS = RecognizeOutcome(hit = false)
        val DIRECT_HIT = RecognizeOutcome(hit = true)
    }
}

/**
 * 一次模板识别未命中的定量原因。
 *
 * 存在的理由：模板匹配的相关图峰值在阈值判定时本来就算出来了，过去被直接丢弃，
 * 于是「界面不对」和「界面对了但素材匹配不上」在日志里长得一模一样——
 * 而这两者的修法完全不同（改时序 vs 重截图）。带上峰值就能一眼分开：
 * 峰值 0.2 说明画面根本不是那一页，峰值 0.83 卡在 0.85 说明素材需要重截或降阈值。
 *
 * [peak] 为 null 表示没有可比的相关图（素材缺失，或模板尺寸超出画面）。
 */
data class TemplateMiss(
    val template: String,
    val threshold: Double,
    val peak: Double?,
    val x: Int = 0,
    val y: Int = 0,
) {
    override fun toString(): String = if (peak == null) {
        "$template 无相关图(素材缺失或模板大于画面) 阈值=$threshold"
    } else {
        "$template 峰值=${"%.3f".format(peak)}@$x,$y 阈值=$threshold"
    }
}

/**
 * 节点识别，移植上游 `TaskNode.do_recognize` 与 `get_recognition_params`。
 *
 * 实测上游 v5.0.0 的 133 个节点只用两种识别：`direct`(56) 与 `template_match`(77)，
 * 且 template 一律是单个字符串、只出现过一个非默认阈值 0.9、4 个节点带 mask。
 * 另两种（color / feature）在 JSON 里没出现但注册表支持，一并实现以免上游启用时报错。
 *
 * 有效节点的返回值语义照抄：`enable && (hit xor inverse)`。
 * 不支持的识别类型或无效识别参数抛出异常，不作为 MISS 参与 inverse。
 * - `enable=false` 恒不命中 —— 上游 check 节点会把目标节点置 false 实现「用完即弃」
 * - `inverse` 是「识别不中才算命中」，用于「不在主界面就先回主界面」这类判断
 */
class NodeRecognizer(
    private val recognizer: Recognizer,
    private val onUnknownRecognition: (String) -> Unit = {},
    /**
     * 是否输出识别耗时。由「设置 → 调试模式」驱动：这些行只对排查慢在哪里有用，
     * 平时会把执行日志刷满（一轮任务数百次识别）。
     */
    private val perfEnabled: Boolean = false,
) {

    suspend fun recognize(node: PipelineNode): RecognizeOutcome {
        val t0 = System.nanoTime()
        // Defend callers that construct/copy a node without going through registry.load.
        node.compatibilityError()?.let { reason ->
            val message = "流水线节点识别失败：$reason"
            onUnknownRecognition(message)
            error(message)
        }
        val raw = when (node.recognition) {
            PipelineNode.RECOGNITION_DIRECT -> RecognizeOutcome.DIRECT_HIT

            PipelineNode.RECOGNITION_TEMPLATE_MATCH ->
                byTemplate(node, DEFAULT_TEMPLATE_THRESHOLD) { t, th, crop, onMiss ->
                    val matches = recognizer.templateMatch(t, th, crop, onMiss = onMiss)
                    // 只放行不使用命中坐标的经验/纺锤选队动作。其他 Details 点击、镜牢、
                    // inverse、裁剪或自定义阈值仍完全使用上游模板，不伪造按钮坐标。
                    if (matches.isEmpty() && node.enable && t == "details" && node.action == "choose_team" &&
                        node.str("cfg_type") in setOf("exp", "thread") && !node.inverse &&
                        crop == null && th == DEFAULT_TEMPLATE_THRESHOLD) {
                        recognizer.observeTeamSelection()?.let { listOf(it) } ?: matches
                    } else matches
                }

            PipelineNode.RECOGNITION_COLOR_TEMPLATE_MATCH ->
                byTemplate(node, DEFAULT_COLOR_FEATURE_THRESHOLD) { t, th, crop, _ ->
                    recognizer.colorTemplateMatch(t, th, crop)
                }

            PipelineNode.RECOGNITION_FEATURE_MATCH ->
                byTemplate(node, DEFAULT_COLOR_FEATURE_THRESHOLD) { t, th, crop, _ ->
                    recognizer.featureMatch(t, th, crop)
                }

            // 文字判据：读区域内文字与期望串比对，不需要素材。
            // mask 在这里是 OCR 的取字区域（对应上游 fill_mask_screenshot 语义），
            // 强烈建议每个 ocr 节点都给 mask —— 全屏 OCR 既慢又容易撞上别处的同名文字。
            PipelineNode.RECOGNITION_OCR -> {
                val target = node.str("text").orEmpty()
                val threshold = node.num("threshold") ?: DEFAULT_OCR_THRESHOLD
                val crop = node.ints("mask")?.let { Crop(it[0], it[1], it[2], it[3]) }
                val found = recognizer.findText(target, crop, threshold)
                RecognizeOutcome(
                    hit = found.isNotEmpty(),
                    matches = found.map { Match(it.x, it.y, it.score) },
                )
            }

            else -> error("不支持的 recognition '${node.recognition}'，请升级 App 后重新加载资源包")
        }

        val hit = if (node.inverse) !raw.hit else raw.hit
        val finalHit = node.enable && hit
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (perfEnabled && ms > 200) {
            onUnknownRecognition("perf: ${node.action}/${node.recognition} ${ms}ms hit=$finalHit tpl=${node.templates().firstOrNull()}")
        }
        // inverse 节点「识别不中」就是命中，此时那份 miss 不是失败原因，别往上报
        return RecognizeOutcome(
            hit = finalHit,
            matches = raw.matches,
            miss = if (finalHit) null else raw.miss,
        )
    }

    private suspend fun byTemplate(
        node: PipelineNode,
        defaultThreshold: Double,
        match: suspend (String, Double, Crop?, ((Double, Int, Int) -> Unit)?) -> List<Match>,
    ): RecognizeOutcome {
        // 上游 template 一律单串；这里仍按多个处理，任一命中即命中，
        // 以便上游将来改成数组时无需改动
        val templates = node.templates()
        val threshold = node.num("threshold") ?: defaultThreshold
        val crop = node.ints("mask")
            ?.let { Crop(it[0], it[1], it[2], it[3]) }

        // 多模板时留下峰值最高的那次未命中：它最接近命中，最能说明差在哪
        var best: TemplateMiss? = null
        for (t in templates) {
            var peak: Double? = null
            var peakX = 0
            var peakY = 0
            val found = match(t, threshold, crop) { p, x, y ->
                peak = p
                peakX = x
                peakY = y
            }
            if (found.isNotEmpty()) return RecognizeOutcome(hit = true, matches = found)
            val current = TemplateMiss(t, threshold, peak, peakX, peakY)
            val prev = best
            if (prev == null || (current.peak ?: -1.0) > (prev.peak ?: -1.0)) best = current
        }
        return RecognizeOutcome(hit = false, miss = best)
    }

    private companion object {
        /** 上游 get_recognition_params 里 template_match 的默认阈值 */
        const val DEFAULT_TEMPLATE_THRESHOLD = 0.85

        /** ocr 判据的默认置信度门槛，与上游 find_text_in_image 一致 */
        const val DEFAULT_OCR_THRESHOLD = 0.5

        /** color_template_match 与 feature_match 的默认阈值 */
        const val DEFAULT_COLOR_FEATURE_THRESHOLD = 0.7
    }
}
