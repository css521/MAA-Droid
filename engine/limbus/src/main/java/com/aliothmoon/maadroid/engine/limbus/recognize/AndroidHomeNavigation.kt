package com.aliothmoon.maadroid.engine.limbus.recognize

import com.aliothmoon.maadroid.engine.limbus.recognize.ocr.PpOcrEngine
import java.util.Locale
import kotlin.math.abs
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Android 的底部导航图标约为桌面模板的 1.4 倍，标签与图标的间距也不同。
 * 仅适配三个主页锚点，仍读取安装资源中的 LALC 模板；不改变其他模板的阈值或坐标。
 * 2026-09-09 手机截图中 CLAHE 会放大已选中 Window 图标的金色填充差异，
 * 此处用灰度 + blur 保留轮廓，并在右下导航区域做有限尺度搜索。
 */
internal object AndroidHomeNavigation {
    private val names = setOf("main_drive_no_text", "main_window_no_text", "main_drive_with_text")
    fun supports(name: String) = name in names

    // 同一帧内缓存 drive icon 的多尺度匹配结果。
    // 每次 templateMatch 调用之间帧不变（bridge 按内容递增 seq），
    // 同一轮 probe 里多个主页导航判据不需要各自做一遍 17 档多尺度搜索。
    // 这是 39 秒卡顿的根因：error_handler 打转 ~10 轮 × 每轮多个主页判据 × 17 档。
    // 时间窗口缓存：如果上次搜索是 <2 秒前，直接复用。
    // 为什么不用 frameSeq：bridge 每次 grab 都给新 seq，缓存永远不命中。
    // 为什么 2 秒安全：这段时间里画面没变（因为没有命中就没有点击），
    // 17 档搜索 ≈ 300-800ms，而路由里多个候选的间隔 < 100ms。
    private var cachedDriveTime: Long = 0
    private var cachedDrive: Match? = null
    private var cachedLabelsTime: Long = 0
    private var cachedLabels: List<TextMatch>? = null
    private const val CACHE_TTL_MS = 2000L

    fun match(
        screen: Mat,
        name: String,
        template: Mat,
        driveTemplate: Mat,
        threshold: Double,
        language: String,
        ocr: PpOcrEngine?,
        checkActive: () -> Unit,
        frameSeq: Long = -1,
    ): Match? {
        if (!supports(name) || screen.cols() != 1280 || screen.rows() != 720) return null
        val now = System.currentTimeMillis()
        val drive = if (now - cachedDriveTime < CACHE_TTL_MS) {
            cachedDrive ?: return null
        } else {
            icon(screen, driveTemplate, threshold, checkActive).also {
                cachedDriveTime = now; cachedDrive = it
            } ?: return null
        }
        return when (name) {
            "main_drive_no_text" -> drive
            // window icon 不缓存——它和 drive 的 template 不同，缓存 drive 的结果不能用。
            // 但 17 档搜索只对 drive 做（因为 drive 是所有三个路径共同的第一步），
            // window 的搜索在 drive 命中之后才走，且 window icon 和 drive icon 相邻，
            // 尺度可以复用 drive 的最佳尺度——但当前 icon() 不支持指定尺度范围，暂搁。
            "main_window_no_text" -> icon(screen, template, threshold, checkActive)
                ?.takeIf { isWindowBesideDrive(it, drive) }
            else -> {
                val reader = ocr ?: return null
                val lbl = if (now - cachedLabelsTime < CACHE_TTL_MS) {
                    cachedLabels ?: emptyList()
                } else {
                    labels(screen, reader, checkActive).also {
                        cachedLabelsTime = now; cachedLabels = it
                    }
                }
                confirmedDriveLabel(drive, lbl, language, threshold)
            }
        }
    }

    fun observeLanguage(
        screen: Mat, driveTemplate: Mat, language: String, ocr: PpOcrEngine?,
        checkActive: () -> Unit,
    ): GameLanguageObservation {
        if (ocr == null || screen.cols() != 1280 || screen.rows() != 720) return GameLanguageObservation.Uncertain
        val drive = icon(screen, driveTemplate, .85, checkActive) ?: return GameLanguageObservation.Uncertain
        val labels = labels(screen, ocr, checkActive)
        return languageObservation(drive, labels, language)
    }

    private fun labels(screen: Mat, reader: PpOcrEngine, checkActive: () -> Unit): List<TextMatch> {
        checkActive()
        // Avoid magnifying a thin strip to thousands of pixels; keep button labels separate.
        val region = Rect(640, 400, 640, 320)
        val work = Mat(screen, region)
        return try {
            // 手机导航标签过小，CLAHE 会损坏抗锯齿边缘；保留已验证的原色适配。
            // 业务 OCR 仍按上游走整帧掩码和灰度增强。
            reader.detect(work, mergeX = false, mergeY = false, enhanceContrast = false).map {
                TextMatch(it.text, it.centerX + region.x, it.centerY + region.y, it.confidence.toDouble())
            }
        } finally { work.release() }
    }

    internal fun isWindowBesideDrive(window: Match, drive: Match): Boolean =
        drive.x - window.x in 70..260 && abs(window.y - drive.y) <= 25

    internal fun confirmedDriveLabel(
        drive: Match, labels: List<TextMatch>, language: String, threshold: Double,
    ): Match? {
        if (drive.score < threshold) return null
        if (!matchesLanguage(drive, labels, language, threshold)) return null
        // Conflicting language evidence cannot authorize loading either language's templates.
        val other = if (language == "en") "zh" else "en"
        return drive.takeUnless { matchesLanguage(drive, labels, other, threshold) }
    }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    internal fun languageObservation(drive: Match, labels: List<TextMatch>, language: String): GameLanguageObservation {
        val english = matchesLanguage(drive, labels, "en", .85)
        val chinese = matchesLanguage(drive, labels, "zh", .85)
        val detected = when {
            english && !chinese -> "en"
            chinese && !english -> "zh"
            else -> return GameLanguageObservation.Uncertain
        }
        return if (detected == language) GameLanguageObservation.Confirmed
        else GameLanguageObservation.Mismatch(language, detected)
    }

    private fun matchesLanguage(drive: Match, labels: List<TextMatch>, language: String, threshold: Double): Boolean {
        if (drive.score < threshold || language !in setOf("en", "zh")) return false
        val row = labels.filter { it.y - drive.y in 10..75 }
        val exactDrive = if (language == "en") setOf("drive")
            else setOf("驾驶舱", "驾驶席", "駕駛艙", "駕駛席")
        if (row.any { it.score >= threshold && abs(it.x - drive.x) <= 40 && normalize(it.text) in exactDrive }) return true

        // Tiny mobile labels vary between frames (Drive -> Orive / Drlre). Do not keep
        // adding misspellings of one word. Require two independent English navigation
        // buttons, in their actual relative positions, with at least one exact word.
        if (language != "en" || drive.score < .9) return false
        val vocabulary = listOf(
            "window" to -230..-105, "sinners" to -135..-40, "drive" to -40..40,
            "theater" to 35..125, "extract" to 115..210, "dispense" to 195..295,
        )
        val evidence = vocabulary.mapNotNull { (word, offset) ->
            row.filter { it.score >= .8 && it.x - drive.x in offset }
                .map { normalize(it.text) }
                .firstOrNull { it == word || oneEditApart(it, word) }
                ?.let { word to (it == word) }
        }
        return evidence.size >= 2 && evidence.any { it.second }
    }

    private fun oneEditApart(actual: String, expected: String): Boolean {
        if (abs(actual.length - expected.length) > 1) return false
        var a = 0
        var b = 0
        var edits = 0
        while (a < actual.length && b < expected.length) {
            if (actual[a] == expected[b]) { a++; b++; continue }
            if (++edits > 1) return false
            if (actual.length >= expected.length) a++
            if (actual.length <= expected.length) b++
        }
        return edits + (actual.length - a) + (expected.length - b) == 1
    }

    private fun icon(screen: Mat, template: Mat, threshold: Double, checkActive: () -> Unit): Match? {
        val region = Rect(640, 540, 640, 180)
        val crop = Mat(screen, region)
        val grayScreen = Mat()
        val grayTemplate = Mat()
        val resized = Mat()
        val scores = Mat()
        try {
            if (crop.channels() == 1) crop.copyTo(grayScreen)
            else Imgproc.cvtColor(crop, grayScreen, Imgproc.COLOR_BGR2GRAY)
            if (template.channels() == 1) template.copyTo(grayTemplate)
            else Imgproc.cvtColor(template, grayTemplate, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(grayScreen, grayScreen, Size(5.0, 5.0), 0.0)
            Imgproc.GaussianBlur(grayTemplate, grayTemplate, Size(5.0, 5.0), 0.0)
            var best: Match? = null
            // 屏幕缩放 0.625..1.025，对应模板大小约 1..1.6 倍；点击坐标还原到原帧。
            for (step in 25..41) {
                checkActive()
                val scale = step / 40.0
                Imgproc.resize(grayScreen, resized, Size(), scale, scale, Imgproc.INTER_LINEAR)
                if (resized.cols() < grayTemplate.cols() || resized.rows() < grayTemplate.rows()) continue
                Imgproc.matchTemplate(resized, grayTemplate, scores, Imgproc.TM_CCOEFF_NORMED)
                val peak = Core.minMaxLoc(scores)
                if (!peak.maxVal.isFinite() || peak.maxVal < threshold || peak.maxVal <= (best?.score ?: -1.0)) continue
                val center = TemplateMatcher.toCenter(
                    peak.maxLoc.x.toInt(), peak.maxLoc.y.toInt(), grayTemplate.cols(), grayTemplate.rows(),
                    region.x, region.y, scale,
                )
                best = Match(center.first, center.second, peak.maxVal)
            }
            return best
        } finally {
            scores.release()
            resized.release()
            grayTemplate.release()
            grayScreen.release()
            crop.release()
        }
    }
}
