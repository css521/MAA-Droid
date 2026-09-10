package com.aliothmoon.maadroid.engine.limbus.recognize

/**
 * Android 经验卡片标题查询，仍使用 LALC 的标题掩码和按钮坐标。
 *
 * 手机日志中 09 被两次读成完整的 `STAGE 9`。只在明确的 STAGE 标签中比较编号，
 * 不放宽通用数字 OCR，也不把卡片标题中的 `#9`、其他位置的单独 `9` 当作 09。
 */
internal class ExpStageQuery(private val target: String) {
    private val number = target.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    fun matches(text: String): Boolean {
        if (number == null) return false
        val value = text.trim()
        if (value == target) return true
        val label = STAGE.matchEntire(value) ?: return false
        return label.groupValues[1].toIntOrNull() == number
    }

    companion object {
        val REGION = Crop(250, 180, 1000, 50)
        private val STAGE = Regex("STAGE\\s*([0-9]+)", RegexOption.IGNORE_CASE)
    }
}
