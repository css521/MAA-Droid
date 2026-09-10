package com.aliothmoon.maadroid.engine.limbus.recognize

/**
 * 纺锤关卡难度查询。只在明确的 `Lv<数字>` 标签里比较编号。
 *
 * 存在的理由：手机上 OCR 把 `Difficulty Lv60` 读成 **`Difficulty LvG0`**——`6` 认成
 * 字母 `G`（真机实测置信度 0.92，同帧的 `Lv40`、`Lv50` 都读对）。于是 `findText("60")`
 * 永远匹配不上，重试耗尽后报「找不到 Thread 副本 60」。用户看到的"点在 Lv40 与 Lv50
 * 之间"其实不是点击，是重试之间那次 `swipe(650,325→650,430)` 手势。
 *
 * 这类混淆在本项目里已经出现三次：方舟侧 `理智/210` → `理智/2IO`（0→O、1→I）、
 * 经验副本 `09` → `STAGE 9`（丢前导零）、这里 `60` → `G0`（6→G）。
 *
 * 修法沿用 [ExpStageQuery] 的克制原则：**只在识别出 `Lv` 标签之后、且只对捕获到的
 * 编号做字形还原**，不放宽通用数字 OCR。否则 `Lunacy 124974`、脑啡肽 `13/155`、
 * `Consecutive Battle ×1` 这些同屏数字都可能被误当成层级。
 *
 * 标签本身也会被读错（实测出现过 `Dificulty`，少一个 f），所以正则不匹配
 * "Difficulty" 这个词，只锚定 `Lv` + 编号。
 */
internal class ThreadStageQuery(private val target: String) {
    private val number = target.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    fun matches(text: String): Boolean {
        if (number == null) return false
        val value = text.trim()
        if (value == target) return true
        val label = LEVEL.find(value) ?: return false
        return restoreDigits(label.groupValues[1]) == number
    }

    companion object {
        /**
         * 难度标签所在区域（1280x720 帧坐标）。
         *
         * 上游是 `mask=[610, 170, 90, 400]`（LALC luxcavation.py:54，PC 布局）。手机上
         * 标签在 x 522~608，上游 crop 从 610 起正好压在其右边缘外，只能读到被切碎的
         * boss 名。左扩到 500 后实测三行标签全部读出：`Lv40@567,259`、`Lv50@567,362`、
         * `LvG0@567,464`。纵向 170~570 覆盖三行（行高约 103），底部资源数字在 y>650 之外。
         */
        val REGION = Crop(500, 170, 200, 400)

        /** 关卡行 Enter 按钮的横向中心（帧内实测 x 763~806） */
        const val ENTER_X = 785

        /** 只锚定 `Lv` + 编号：标签词本身会被读错（实测 `Dificulty`），不能进正则 */
        private val LEVEL = Regex("Lv\\s*([0-9GOIloSBZ]+)", RegexOption.IGNORE_CASE)

        /**
         * 把编号里被认成字母的字形还原成数字。
         *
         * `G→6` 是实测确认的；其余是 PaddleOCR 在窄体金色数字上的常见混淆，
         * 一并覆盖以免下次换成 `Lv80` 又栽在 `B` 上。只作用于已经确认是编号的片段。
         */
        private fun restoreDigits(raw: String): Int? = raw.map {
            when (it) {
                'G', 'g' -> '6'
                'O', 'o', 'D' -> '0'
                'I', 'i', 'l' -> '1'
                'S', 's' -> '5'
                'B' -> '8'
                'Z', 'z' -> '2'
                else -> it
            }
        }.joinToString("").toIntOrNull()
    }
}
