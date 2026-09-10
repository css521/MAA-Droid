package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

import kotlin.math.ceil

/**
 * OCR 两个模型的输入尺寸与检测框几何，全部是纯函数。
 *
 * 抽成纯函数的理由同 [CtcDecoder]：onnxruntime 与 OpenCV 的 native 库在纯 JVM 单测里
 * 加载不了，而这些计算**算错不会报错**，只会让检测框偏移或文字被截断。
 * 参数取自上游 `recognize/rapidocr.yaml`，不是默认值 —— 上游显式改过其中几项。
 */
object OcrGeometry {

    // ---- 检测（det）----

    /** 上游 rapidocr.yaml：limit_side_len=736、limit_type=min */
    const val DET_LIMIT_SIDE_LEN = 736
    const val DET_THRESH = 0.3f
    const val DET_BOX_THRESH = 0.5f
    const val DET_UNCLIP_RATIO = 1.6
    const val DET_MAX_CANDIDATES = 1000
    const val DET_MIN_SIZE = 3

    /** det 的归一化是 mean=std=0.5（**不是** ImageNet 那组，与分类器不同） */
    const val DET_MEAN = 0.5f
    const val DET_STD = 0.5f

    /** 网络要求边长为 32 的整数倍 */
    private const val STRIDE = 32

    /**
     * 计算检测模型的输入尺寸。
     *
     * `limit_type=min` 的语义是「**短边不小于** limit_side_len」：短边不足时放大，
     * 够了就不缩放。与 `max`（长边不超过）相反 —— 用错会让小图不放大，
     * 小字直接检测不出来。
     *
     * 缩放后两边各自四舍五入到 32 的整数倍，且至少为 32。
     */
    fun detInputSize(width: Int, height: Int, limitSideLen: Int = DET_LIMIT_SIDE_LEN): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return STRIDE to STRIDE
        val minSide = minOf(width, height)
        val ratio = if (minSide < limitSideLen) {
            limitSideLen.toDouble() / minSide
        } else 1.0

        val rw = alignToStride((width * ratio).toInt())
        val rh = alignToStride((height * ratio).toInt())
        return rw to rh
    }

    /**
     * 对齐到 32 的整数倍。
     *
     * 必须用 **banker's rounding**（四舍六入五取偶）而不是常见的四舍五入：
     * 上游是 Python 的 `round()`，它对 .5 取偶数侧。以 2000 为例，
     * `2000/32 = 62.5` → Python 得 62（→1984），四舍五入会得 63（→2016），
     * 两者相差 32px，会让检测模型读到与上游不同尺寸的图。
     *
     * Java 的 `Math.rint` 正是 half-to-even，Kotlin 的 `roundToInt` 则是 half-up。
     */
    private fun alignToStride(v: Int): Int =
        (Math.rint(v.toDouble() / STRIDE).toInt() * STRIDE).coerceAtLeast(STRIDE)

    /**
     * DB 的 unclip 距离：`面积 * ratio / 周长`。
     *
     * 上游用 pyclipper 以圆角外扩多边形，随后立刻重新取最小外接矩形。对矩形而言，
     * 圆角外扩再取最小外接矩形**等于**把矩形四边各外扩 [unclipDistance]
     * （圆角部分被重新取矩形吸收）。已用 pyclipper 实测核对，误差在 0.7px 以内，
     * 而结果随后要取整并缩放回原图，该误差无实际影响 —— 因此不必引入 pyclipper。
     */
    fun unclipDistance(width: Double, height: Double, ratio: Double = DET_UNCLIP_RATIO): Double {
        val perimeter = 2 * (width + height)
        if (perimeter <= 0.0) return 0.0
        return width * height * ratio / perimeter
    }

    // ---- 识别（rec）----

    /** 上游 rec_img_shape = [3, 48, 320] */
    const val REC_HEIGHT = 48
    const val REC_BASE_WIDTH = 320
    const val REC_BATCH_SIZE = 6

    /** rec 的归一化同样是 mean=std=0.5 */
    const val REC_MEAN = 0.5f
    const val REC_STD = 0.5f

    /**
     * 计算一个文字块送入识别模型时的缩放宽度。
     *
     * 上游把高度固定为 48，宽度按原始宽高比等比缩放并**向上取整**，
     * 超过批次允许的最大宽度则截断到该宽度，其余部分右侧补零。
     *
     * 向上取整不能改成向下：窄一个像素就可能把最后一个字切掉半边，
     * 识别结果少一个字。
     *
     * @param batchMaxWidth 该批次的目标宽度，由 [recBatchWidth] 算出
     */
    fun recResizedWidth(srcWidth: Int, srcHeight: Int, batchMaxWidth: Int): Int {
        if (srcHeight <= 0) return 1
        val ratio = srcWidth.toDouble() / srcHeight
        val wanted = ceil(REC_HEIGHT * ratio).toInt()
        return wanted.coerceIn(1, batchMaxWidth)
    }

    /**
     * 一批文字块的统一输入宽度 = `48 * max(320/48, 批内最大宽高比)`。
     *
     * RapidOCR 3.8.4 的 320 是基准下限，不是上限；把长文字挤进 320 会损失字形。
     * 上游按宽高比排序后每六框一批，同批所有图共用最大宽度。
     */
    fun recBatchWidth(sizes: List<Pair<Int, Int>>): Int {
        val maxRatio = sizes.maxOfOrNull { (w, h) ->
            if (h <= 0) 1.0 else w.toDouble() / h
        } ?: 1.0
        return (REC_HEIGHT * maxOf(REC_BASE_WIDTH.toDouble() / REC_HEIGHT, maxRatio)).toInt()
    }

    /**
     * 把四点框按顺时针整理成「左上、右上、右下、左下」。
     *
     * 上游 `order_points_clockwise`：先按 x 排序分成左右两列，各列再按 y 定上下。
     * 顺序错了会让后续按框裁图时取到镜像或旋转的区域，识别结果变成乱码。
     */
    fun orderClockwise(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.size != 4) return points
        val byX = points.sortedBy { it.first }
        val left = byX.take(2).sortedBy { it.second }
        val right = byX.drop(2).sortedBy { it.second }
        // 左上、右上、右下、左下
        return listOf(left[0], right[0], right[1], left[1])
    }
}
