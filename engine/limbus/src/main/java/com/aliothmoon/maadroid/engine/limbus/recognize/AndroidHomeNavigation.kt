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

    fun match(
        screen: Mat,
        name: String,
        template: Mat,
        driveTemplate: Mat,
        threshold: Double,
        language: String,
        ocr: PpOcrEngine?,
        checkActive: () -> Unit,
    ): Match? {
        if (!supports(name) || screen.cols() != 1280 || screen.rows() != 720) return null
        val drive = icon(screen, driveTemplate, threshold, checkActive) ?: return null
        return when (name) {
            "main_drive_no_text" -> drive
            "main_window_no_text" -> icon(screen, template, threshold, checkActive)
                ?.takeIf { isWindowBesideDrive(it, drive) }
            else -> {
                // 必须看见所选语言的 Drive 标签；不能因手机排布不同就跳过语言校验。
                val reader = ocr ?: return null
                checkActive()
                // 较高的区域避免 OCR 的 min-side 放大把细长导航条放大到数千像素。
                val region = Rect(640, 400, 640, 320)
                val work = Mat(screen, region)
                val labels = try {
                    // 相邻导航标签是独立按钮，不能合并成 "Window Sinners" 等整行。
                    reader.detect(work, mergeX = false, mergeY = false).map {
                        TextMatch(it.text, it.centerX + region.x, it.centerY + region.y, it.confidence.toDouble())
                    }
                } finally {
                    work.release()
                }
                confirmedDriveLabel(drive, labels, language, threshold)
            }
        }
    }

    internal fun isWindowBesideDrive(window: Match, drive: Match): Boolean =
        drive.x - window.x in 70..260 && abs(window.y - drive.y) <= 25

    internal fun confirmedDriveLabel(
        drive: Match, labels: List<TextMatch>, language: String, threshold: Double,
    ): Match? {
        val expected = when (language) {
            "en" -> setOf("drive")
            "zh" -> setOf("驾驶舱", "驾驶席", "駕駛艙", "駕駛席")
            else -> return null
        }
        val label = labels.firstOrNull {
            it.score >= threshold && abs(it.x - drive.x) <= 40 && it.y - drive.y in 10..75 &&
                (normalize(it.text) in expected ||
                    language == "en" && isConfirmedEnglishOcrAlias(it, drive, labels))
        } ?: return null
        return drive.copy(score = minOf(drive.score, label.score))
    }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun isConfirmedEnglishOcrAlias(label: TextMatch, drive: Match, labels: List<TextMatch>): Boolean {
        // In the downsampled phone report, OCR reads the round D as O ("Orive", 0.91).
        // Accept only with the strong Drive icon AND the adjacent English Sinners label.
        return normalize(label.text) == "orive" && drive.score >= .9 && labels.any {
            normalize(it.text) == "sinners" && it.score >= .8 &&
                label.x - it.x in 50..130 && abs(label.y - it.y) <= 15
        }
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
