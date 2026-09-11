package com.maadroid.app.engine.limbus.recognize.ocr

/** 名称仍按上游包含匹配；纯数字必须完整保留，09 不能命中 109，也不能退化成 9。 */
internal class OcrTextQuery(val target: String) {
    val isNumber = target.isNotEmpty() && target.all { it in '0'..'9' }
    private val number = if (isNumber) Regex("(?<![0-9])${Regex.escape(target)}(?![0-9])") else null

    fun matches(text: String): Boolean = target.isNotEmpty() &&
        (number?.containsMatchIn(text) ?: (target in text))
}
