package com.maadroid.app.engine.limbus.recognize

import com.maadroid.app.engine.limbus.recognize.ocr.PpOcrEngine
import java.util.Locale
import org.opencv.core.Mat
import org.opencv.core.Rect

/** Android 英文队伍页的正向证据；不把桌面 Details 模板的缺失当作游戏限制。 */
internal object TeamSelectionDetector {
    private val counter = Regex("^(\\d{1,2})/(\\d{1,2})$")
    private val grid = Rect(200, 170, 850, 400)

    fun detect(
        screen: Mat,
        reader: PpOcrEngine?,
        checkActive: () -> Unit,
        onCandidates: (String) -> Unit,
    ): Match? {
        if (reader == null || screen.cols() != 1280 || screen.rows() != 720) return null
        checkActive()
        val counts = reader.detect(screen, mergeX = false, mergeY = false).map {
            TextMatch(it.text, it.centerX, it.centerY, it.confidence.toDouble())
        }.filter { it.x in 1100..1260 && it.y in 495..550 }
        if (validCounters(counts).size != 1) return null
        checkActive()
        val crop = Mat(screen, grid)
        val labels = try {
            reader.detect(crop, mergeX = false, mergeY = false).map {
                TextMatch(it.text, it.centerX + grid.x, it.centerY + grid.y, it.confidence.toDouble())
            }
        } finally { crop.release() }
        onCandidates("counter=$counts labels=${labels.filter { isCardLabel(it) }}")
        return fromText(counts, labels)
    }

    /** 人数区和上下两排卡片必须在同一帧同时存在；单个数字、SELECTED 或宿主蒙版均不够。 */
    fun fromText(counts: List<TextMatch>, labels: List<TextMatch>): Match? {
        val count = validCounters(counts).singleOrNull() ?: return null
        val cards = labels.filter(::isCardLabel)
        val upper = cards.filter { it.y in 220..285 }.maxByOrNull { it.score } ?: return null
        val lower = cards.filter { it.y in 415..480 }.maxByOrNull { it.score } ?: return null
        return Match(count.x, count.y, minOf(count.score, upper.score, lower.score))
    }

    private fun validCounters(text: List<TextMatch>) = text.filter {
        if (it.score < .9 || !it.score.isFinite() || it.x !in 1100..1260 || it.y !in 495..550) return@filter false
        val parts = counter.matchEntire(it.text.filterNot(Char::isWhitespace)) ?: return@filter false
        val selected = parts.groupValues[1].toInt()
        val total = parts.groupValues[2].toInt()
        total in 1..12 && selected in 0..total
    }

    private fun isCardLabel(text: TextMatch): Boolean =
        text.score.isFinite() && text.score >= .8 && text.x in 240..1040 &&
            text.text.trim().lowercase(Locale.ROOT) in setOf("selected", "backup")
}
