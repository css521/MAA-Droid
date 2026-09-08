package com.aliothmoon.maadroid.engine.limbus.recognize

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * 灰度模板匹配。上游 LALC 用量最大的识别方式（流水线 77 处 + 动作代码 51 处 = 128 处），
 * 也是唯一在流水线 JSON 里出现的识别方式，因此实现必须与上游逐步对齐。
 *
 * 预处理链条照抄上游 `recognize/template_match.py`，顺序与参数都不能改：
 * 1. 转灰度
 * 2. CLAHE（clipLimit=1.5, tile 8x8）—— 边狱 UI 明暗差异大，直方图均衡后模板才稳
 * 3. 高斯模糊 5x5 —— 抑制 UI 抗锯齿造成的高频噪声
 * 4. `TM_CCOEFF_NORMED` 匹配
 * 5. 阈值筛选 → 按分数降序 → **20px 去重**
 *
 * 第 5 步的去重是必需的：`TM_CCOEFF_NORMED` 在目标周围会产生一片高分点，
 * 不去重会把一个按钮报成几十个匹配，上游动作代码里大量 `if len(res) > 0` 的判断
 * 与「取第一个」的写法都依赖这个语义。
 */
object TemplateMatcher {

    /** 与上游一致：中心坐标相差都小于此值即视为同一目标 */
    private const val MERGE_DISTANCE = 20

    private const val CLAHE_CLIP_LIMIT = 1.5
    private val CLAHE_TILE = Size(8.0, 8.0)
    private val BLUR_KERNEL = Size(5.0, 5.0)

    /**
     * @param screen 屏幕灰度图（已裁剪）
     * @param template 模板灰度图
     * @param threshold 匹配阈值，流水线默认 0.85
     * @param offsetX 裁剪偏移，结果坐标会加回去（上游 mask 语义）
     * @param screenshotScale 对屏幕的缩放倍率；结果坐标按原始尺度返回
     */
    fun match(
        screen: Mat,
        template: Mat,
        threshold: Double,
        offsetX: Int = 0,
        offsetY: Int = 0,
        screenshotScale: Double = 1.0,
    ): List<Match> {
        val prepScreen = preprocess(screen, screenshotScale)
        val prepTemplate = preprocess(template, 1.0)
        try {
            // 模板比屏幕大时 matchTemplate 会抛异常，上游是直接返回空
            if (prepTemplate.rows() > prepScreen.rows() || prepTemplate.cols() > prepScreen.cols()) {
                return emptyList()
            }
            val result = Mat()
            try {
                Imgproc.matchTemplate(prepScreen, prepTemplate, result, Imgproc.TM_CCOEFF_NORMED)
                return collect(
                    result = result,
                    templateWidth = prepTemplate.cols(),
                    templateHeight = prepTemplate.rows(),
                    threshold = threshold,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    scale = screenshotScale,
                )
            } finally {
                result.release()
            }
        } finally {
            if (prepScreen !== screen) prepScreen.release()
            if (prepTemplate !== template) prepTemplate.release()
        }
    }

    /** CLAHE + 高斯模糊 + 可选缩放。顺序与参数照抄上游，改动会让阈值失去意义 */
    private fun preprocess(src: Mat, scale: Double): Mat {
        var work = ensureGray(src)
        val clahe = Imgproc.createCLAHE(CLAHE_CLIP_LIMIT, CLAHE_TILE)
        val equalized = Mat()
        clahe.apply(work, equalized)
        if (work !== src) work.release()
        work = equalized

        val blurred = Mat()
        Imgproc.GaussianBlur(work, blurred, BLUR_KERNEL, 0.0)
        work.release()
        work = blurred

        if (scale != 1.0) {
            val resized = Mat()
            Imgproc.resize(
                work, resized,
                Size(work.cols() * scale, work.rows() * scale),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            work.release()
            work = resized
        }
        return work
    }

    private fun ensureGray(src: Mat): Mat {
        if (src.channels() == 1) return src
        val gray = Mat()
        // 帧是 BGR（见 core-bridge 的帧缓冲），不是 RGB
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        return gray
    }

    /**
     * 阈值筛选 + 按分降序 + 20px 去重。
     *
     * 不用 `Core.minMaxLoc` 循环挖空的常见写法：那样得到的极值点集与上游的
     * 「全部超阈值点再合并」不同，会在密集 UI 上给出不一样的结果集。
     */
    private fun collect(
        result: Mat,
        templateWidth: Int,
        templateHeight: Int,
        threshold: Double,
        offsetX: Int,
        offsetY: Int,
        scale: Double,
    ): List<Match> {
        // 先快速判断有没有超过阈值的点，避免逐点扫描整张相关图
        val mm = Core.minMaxLoc(result)
        if (mm.maxVal < threshold) return emptyList()

        val rows = result.rows()
        val cols = result.cols()
        val data = FloatArray(rows * cols)
        // 相关图是 CV_32F；一次性取出比逐点 get() 快一个数量级
        if (result.type() != CvType.CV_32F) return emptyList()
        result.get(0, 0, data)

        val candidates = ArrayList<Match>()
        val halfW = templateWidth / 2
        val halfH = templateHeight / 2
        for (y in 0 until rows) {
            val rowBase = y * cols
            for (x in 0 until cols) {
                val score = data[rowBase + x].toDouble()
                if (score < threshold) continue
                val (cx, cy) = toCenter(x, y, templateWidth, templateHeight, offsetX, offsetY, scale)
                candidates += Match(cx, cy, score)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        return mergeNearby(candidates)
    }

    /**
     * 按分降序后做 20px 去重。
     *
     * 单独抽出为纯函数：OpenCV 的 native 库在纯 JVM 单测里加载不了，若把去重埋在
     * [match] 内部，这段最容易出错、也最被上游依赖的逻辑就只能靠真机验证。
     */
    internal fun mergeNearby(candidates: List<Match>): List<Match> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedByDescending { it.score }
        val merged = ArrayList<Match>()
        for (c in sorted) {
            val near = merged.any {
                abs(c.x - it.x) < MERGE_DISTANCE && abs(c.y - it.y) < MERGE_DISTANCE
            }
            // 已按分数降序，先入选的必定分更高，故相近的直接丢弃
            if (!near) merged += c
        }
        return merged
    }

    /** 相关图坐标 → 原始屏幕中心坐标。同样抽出以便纯 JVM 验证 */
    internal fun toCenter(
        matchX: Int, matchY: Int,
        templateWidth: Int, templateHeight: Int,
        offsetX: Int, offsetY: Int, scale: Double,
    ): Pair<Int, Int> = Pair(
        ((matchX + templateWidth / 2) / scale).toInt() + offsetX,
        ((matchY + templateHeight / 2) / scale).toInt() + offsetY,
    )
}
