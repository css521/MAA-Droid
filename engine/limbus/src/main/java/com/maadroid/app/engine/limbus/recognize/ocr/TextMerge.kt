package com.maadroid.app.engine.limbus.recognize.ocr

/**
 * 一个带包围盒的文本块。坐标是 1280x720 逻辑坐标。
 */
data class TextBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * 检测框的合并，移植上游 `recognize/rapid_ocr.py` 的 `detect_text_in_image`。
 *
 * 为什么必须做：PP-OCR 的检测头会把一行字拆成多个框（尤其中英混排的饰品名），
 * 而上游动作代码是拿**整段文字**去模糊匹配饰品名的。不合并的话
 * 「Burning Branch」可能被拆成两个框，两边都匹配不上任何已知饰品。
 *
 * 两步顺序不可交换（先横后纵）：先把同一行的碎片拼成整行，再把换行的两行拼成一段。
 * 反过来会先把上下两行的碎片纵向拼起来，得到跨行的乱序文本。
 *
 * 阈值全部照抄上游，改动会直接影响拼出来的文本：
 * - 横向：`dx <= 100 && dy <= 10`（同一行）
 * - 纵向：`dy <= 30 && dx <= 80`（相邻行）
 *
 * 合并后的置信度取组内**最小值**（上游 `min`）—— 一段文字里只要有一处认得不准，
 * 整段就不该被当作高可信。
 */
object TextMerge {

    private const val X_MERGE_DX = 100
    private const val X_MERGE_DY = 10
    private const val Y_MERGE_DY = 30
    private const val Y_MERGE_DX = 80

    /** 按上游的两步合并。[mergeX] / [mergeY] 与上游同名参数一致，默认都开 */
    fun merge(boxes: List<TextBox>, mergeX: Boolean = true, mergeY: Boolean = true): List<TextBox> {
        var items = boxes
        if (mergeX) {
            items = mergeGroups(
                items.sortedBy { it.centerX },
                sortWithinGroupBy = { it.centerX },
            ) { base, other ->
                kotlin.math.abs(other.centerX - base.centerX) <= X_MERGE_DX &&
                        kotlin.math.abs(other.centerY - base.centerY) <= X_MERGE_DY
            }
        }
        if (mergeY) {
            items = mergeGroups(
                items.sortedBy { it.centerY },
                sortWithinGroupBy = { it.centerY },
            ) { base, other ->
                kotlin.math.abs(other.centerY - base.centerY) <= Y_MERGE_DY &&
                        kotlin.math.abs(other.centerX - base.centerX) <= Y_MERGE_DX
            }
        }
        return items
    }

    /**
     * 贪心分组：按已排序的顺序取第一个未访问项作基准，向后收所有满足 [canMerge] 的项。
     *
     * 与上游一致地**只跟基准比**，不做传递闭包 —— 传递合并会把一长串间距均匀的
     * 独立文本全部并成一段。
     */
    private inline fun mergeGroups(
        sorted: List<TextBox>,
        crossinline sortWithinGroupBy: (TextBox) -> Int,
        canMerge: (TextBox, TextBox) -> Boolean,
    ): List<TextBox> {
        val visited = BooleanArray(sorted.size)
        val result = ArrayList<TextBox>(sorted.size)

        for (i in sorted.indices) {
            if (visited[i]) continue
            visited[i] = true
            val base = sorted[i]
            val group = ArrayList<TextBox>()
            group += base

            for (j in i + 1 until sorted.size) {
                if (visited[j]) continue
                if (canMerge(base, sorted[j])) {
                    visited[j] = true
                    group += sorted[j]
                }
            }
            result += combine(group.sortedBy(sortWithinGroupBy))
        }
        return result
    }

    /** 组内以空格连接文本，包围盒取并集，置信度取最小 */
    private fun combine(group: List<TextBox>): TextBox {
        if (group.size == 1) return group[0]
        return TextBox(
            text = group.joinToString(" ") { it.text },
            left = group.minOf { it.left },
            top = group.minOf { it.top },
            right = group.maxOf { it.right },
            bottom = group.maxOf { it.bottom },
            confidence = group.minOf { it.confidence },
        )
    }
}
