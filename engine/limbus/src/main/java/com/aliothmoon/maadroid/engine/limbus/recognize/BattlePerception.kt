package com.aliothmoon.maadroid.engine.limbus.recognize

import kotlin.math.abs

/** scale 是截图缩放比，与上游 pyramid_template_match 的第四个返回值同义。 */
data class BattleSkillAnchor(val x: Int, val y: Int, val score: Double, val scale: Double)
data class BattleSkillIcon(val type: String, val x: Int, val y: Int)
data class BattleSinnerAvatar(val x: Int, val y: Int, val scores: List<Int>) {
    val egoAvailable: Boolean get() = (scores.maxOrNull() ?: 0) >= 110
}

data class BattleEgoPanel(
    val details: List<Match>,
    val zeroCorrosion: List<Match>,
    val battleVisible: Boolean,
) {
    // 不能把丢帧/模板缺失/未知页面误判为选择成功。
    val closed: Boolean get() = details.isEmpty() && battleVisible
}

/** 无 OpenCV/Android 依赖的战斗几何与图像数学，可直接在 JVM 验证。 */
object BattlePerception {
    val SKILL_AREA = Crop(0, 470, 1280, 155)
    val EGO_AREA = Crop(0, 95, 1280, 110)
    private val dangerous = setOf("hopeless", "struggling", "neutral")

    fun mergeAnchors(matches: List<BattleSkillAnchor>): List<BattleSkillAnchor> {
        val kept = mutableListOf<BattleSkillAnchor>()
        for (m in matches.filter { it.score.isFinite() && it.scale.isFinite() && it.scale > 0 }
            .sortedByDescending { it.score }) {
            if (kept.none { abs(it.x - m.x) < 20 && abs(it.y - m.y) < 20 }) kept += m
        }
        return kept
    }

    /** 保留上游两条技能行；大缩放对应 (20,-15)，其余 (15,-20)。 */
    fun skillRegions(matches: List<BattleSkillAnchor>, winRateX: Int = 1280): List<Crop> =
        mergeAnchors(matches).sortedBy { it.x }.mapNotNull { m ->
            if (m.x > winRateX || m.y < 560 || m.y in 571..589 || m.y > 600) return@mapNotNull null
            val x = m.x + if (m.scale >= 1.2) 20 else 15
            val y = m.y + if (m.scale >= 1.2) -15 else -20
            Crop(x - 40, y - 40, 80, 80)
        }

    fun bindSkills(regions: List<Crop>, labels: List<String>): List<BattleSkillIcon> {
        // 推理部分失败时不能 zip 截断后继续，把别人的标签配到这个坐标上。
        if (regions.size != labels.size) return emptyList()
        return regions.zip(labels) { r, label -> BattleSkillIcon(label, r.x + 40, r.y + 40) }
    }

    fun allUnselected(skills: List<BattleSkillIcon>): Boolean =
        skills.isNotEmpty() && skills.all { it.type == "unselected" }

    fun hasDanger(skills: List<BattleSkillIcon>): Boolean = skills.any { it.type in dangerous }

    fun threatenedAvatars(skills: List<BattleSkillIcon>, avatars: List<BattleSinnerAvatar>): List<BattleSinnerAvatar> {
        val ordered = avatars.sortedBy { it.x }.distinctBy { it.x }
        // 先按所有罪人的区间归属，再筛可用性，否则不可用罪人的技能会错配给前一人。
        return skills.filter { it.type in dangerous }.sortedBy { it.x }.mapNotNull { skill ->
            ordered.lastOrNull { it.x <= skill.x }?.takeIf { it.egoAvailable }
        }.distinctBy { it.x }
    }

    /** 每张卡的 0% 必须在其 detail 左侧 200px 内，且不得越过前一张卡。 */
    fun safeEgoDetails(panel: BattleEgoPanel): List<Match> {
        val cards = panel.details.filter { it.x in 20..1279 && it.y in 95..204 }.sortedBy { it.x }
        return cards.filterIndexed { i, card ->
            val left = maxOf(0, card.x - 200, cards.getOrNull(i - 1)?.x ?: 0)
            panel.zeroCorrosion.any { it.x >= left && it.x < card.x && it.y in 95..204 }
        }.reversed()
    }

    fun avatarCrop(x: Int, y: Int) = Crop(x - 21, y - 65, 80, 55)

    /** BGR 血条颜色。上游 RGB=(225,80,40)，每通道容差 20，包含边界。 */
    fun isHpPixel(b: Int, g: Int, r: Int): Boolean = b in 20..60 && g in 60..100 && r in 205..245

    /**
     * 上游 enhance_img 把 RGB 数组交给 cv2_to_pil，再 RGB2GRAY，实际交换了红蓝。
     * 为保持其 110 阈值含义，对原始 BGR 数组按 RGB 权重计算；技能模型仍接收正常 RGB。
     */
    fun avatarGray(bgr: ByteArray): IntArray {
        require(bgr.size % 3 == 0)
        return IntArray(bgr.size / 3) { p ->
            val b = bgr[p * 3].toInt() and 255
            val g = bgr[p * 3 + 1].toInt() and 255
            val r = bgr[p * 3 + 2].toInt() and 255
            (b * 4899 + g * 9617 + r * 1868 + 8192) shr 14
        }
    }

    /** 3x3 灰度膨胀，OpenCV 默认边界等效为忽略图外像素。 */
    fun dilateGray(gray: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && gray.size == width * height)
        return IntArray(gray.size) { i ->
            val x = i % width
            val y = i / width
            var value = 0
            for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) value = maxOf(value, gray[ny * width + nx])
            }
            value
        }
    }

    /** >5 前景，8 连通域内平均灰度后截断，空图返回空分组。 */
    fun clusterBrightness(gray: IntArray, width: Int, height: Int): List<Int> {
        require(width > 0 && height > 0 && gray.size == width * height)
        val visited = BooleanArray(gray.size)
        val queue = IntArray(gray.size)
        val scores = mutableListOf<Int>()
        for (start in gray.indices) {
            if (visited[start] || gray[start] <= 5) continue
            var head = 0
            var tail = 1
            queue[0] = start
            visited[start] = true
            var sum = 0L
            while (head < tail) {
                val i = queue[head++]
                sum += gray[i]
                val x = i % width
                val y = i / width
                for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                    for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                        val j = ny * width + nx
                        if (!visited[j] && gray[j] > 5) {
                            visited[j] = true
                            queue[tail++] = j
                        }
                    }
                }
            }
            scores += (sum / tail).toInt()
        }
        return scores
    }

    fun avatarScores(enhancedBgr: ByteArray): List<Int> =
        clusterBrightness(dilateGray(avatarGray(enhancedBgr), 80, 55), 80, 55)
}
