package com.aliothmoon.maadroid.engine.limbus.recognize

import java.util.Locale
import kotlin.math.abs

/** Android 的标题页可能保留英文按钮，即使游戏内容选择了中文。 */
internal object TitleScreenDetector {
    val textRegion = Crop(0, 360, 1280, 360)

    // 缓存按钮只用来确认页面，绝不能点击它。标题页中央下方是 Touch to Start 区域。
    fun fromAnchor(matches: List<Match>): Match? = matches
        .firstOrNull { it.score >= 0.85 && it.x in 40..500 && it.y in 580..700 }
        ?.let { Match(640, 540, it.score) }

    fun fromText(matches: List<TextMatch>): Match? {
        val text = matches.filter { it.score >= 0.7 }
        text.firstOrNull {
            it.x in 400..880 && it.y in 440..630 && normalize(it.text) in startLabels
        }?.let { return Match(it.x, it.y, it.score) }

        // 闪烁的开始文字不可见时，必须同时看到左右相邻的两个标题按钮。
        val change = text.firstOrNull {
            it.x in 40..230 && it.y in 580..700 && normalize(it.text) in bannerLabels
        } ?: return null
        val caches = text.firstOrNull {
            it.x in (change.x + 40)..500 && abs(it.y - change.y) <= 25 &&
                normalize(it.text) in cacheLabels
        } ?: return null
        return Match(640, 540, minOf(change.score, caches.score))
    }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private val startLabels = setOf("touchtostart", "taptostart", "点击开始", "触摸开始", "點擊開始")
    private val bannerLabels = setOf("changebanner", "更换横幅", "更換橫幅")
    private val cacheLabels = setOf("clearallcaches", "清除所有缓存", "清除所有緩存")
}
