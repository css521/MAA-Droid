package com.aliothmoon.maadroid.engine.limbus.recognize

import com.aliothmoon.maadroid.engine.limbus.recognize.ocr.PpOcrEngine
import java.util.Locale
import org.opencv.core.Mat
import org.opencv.core.Rect

/** A positively identified mailbox. [empty] false means unconfirmed, not "has mail". */
data class MailboxObservation(val close: Match?, val empty: Boolean)

/** Mail-only OCR fallback; it neither changes the shared matcher nor treats a miss as success. */
object MailboxDetector {
    val region = Crop(150, 80, 960, 520)
    private val titleRegion = Crop(480, 80, 320, 90)
    private val buttonRegion = Crop(200, 515, 900, 70)
    private const val CONFIDENCE = 0.7

    fun fromText(text: List<TextMatch>): MailboxObservation? {
        val readable = text.filter { it.score.isFinite() && it.score >= CONFIDENCE }
        val title = readable.any {
            normalized(it.text) in setOf("mailbox", "邮箱", "郵箱") &&
                it.x in 480..800 && it.y in 80..170
        }
        val empty = readable.any {
            normalized(it.text) in setOf("nomailinstorage", "暂无邮件", "暫無郵件") &&
                it.x in 300..980 && it.y in 210..470
        }
        // A missed Close label must not turn a still-visible mailbox into absence.
        // The empty message also proves presence, but only a confirmed title authorizes a tap.
        if (!title && !empty) return null
        val close = if (title) readable.filter {
            normalized(it.text) in setOf("close", "关闭", "關閉") &&
                it.x in 650..900 && it.y in 515..585
        }.maxByOrNull { it.score } else null
        return MailboxObservation(close?.let { Match(it.x, it.y, it.score) }, empty)
    }

    /** All passes observe this same captured frame. Cropped OCR coordinates are restored here. */
    fun detect(
        screen: Mat,
        ocr: PpOcrEngine?,
        onCandidates: (String) -> Unit = {},
        checkActive: () -> Unit = {},
    ): MailboxObservation? {
        if (ocr == null || screen.cols() != 1280 || screen.rows() != 720) return null
        val words = mutableListOf<TextMatch>()
        fun read(area: Crop, enhanceContrast: Boolean) {
            checkActive()
            val crop = Mat(screen, Rect(area.x, area.y, area.width, area.height))
            try {
                val found = ocr.detect(crop, mergeX = false, mergeY = false, enhanceContrast = enhanceContrast)
                    .map { TextMatch(it.text, it.centerX + area.x, it.centerY + area.y, it.confidence.toDouble()) }
                words += found
                onCandidates("crop=$area enhanced=$enhanceContrast words=[" + found.take(12).joinToString("; ") {
                    "${it.text.take(36).replace('\n', ' ')}@${it.x},${it.y}:${(it.score * 100).toInt()}%"
                } + "]")
            } finally { crop.release() }
        }
        read(region, enhanceContrast = true)
        fromText(words)?.takeIf { it.empty && it.close != null }?.let { return it }
        // The phone preview reads Close as Clese in a whole-frame pass. Re-reading the
        // actual button row recovers Close without fuzzy spelling or a lower threshold.
        read(titleRegion, enhanceContrast = true)
        read(buttonRegion, enhanceContrast = true)
        fromText(words)?.takeIf { it.empty && it.close != null }?.let { return it }
        read(region, enhanceContrast = false)
        return fromText(words)
    }

    private fun normalized(text: String): String = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}
