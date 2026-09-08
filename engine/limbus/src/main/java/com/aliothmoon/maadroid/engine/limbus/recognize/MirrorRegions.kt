package com.aliothmoon.maadroid.engine.limbus.recognize

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.random.Random

/**
 * 镜牢九宫格的区域常量与两个分类器的输入准备。
 *
 * 坐标全部照抄上游 `utils/get_save_mirror_legend.py` 与 `get_save_mirror_path.py`，
 * 是按 1280x720 客户区量出来的。改动会让模型读到错位的图。
 */
object MirrorRegions {

    /**
     * 六个节点区域（两列 × 三行），供 `mirror_legend` 逐个分类。
     *
     * 注意第二列中间那格是 300 而非 290 —— 上游就是这么写的（`second_mid` 用 300），
     * 看着像笔误但两处独立文件都如此，故照抄。
     */
    val LEGEND_NODES: List<Crop> = listOf(
        Crop(660, 80, 130, 110),   // first_up
        Crop(660, 290, 130, 110),  // first_mid
        Crop(660, 500, 130, 110),  // first_low
        Crop(920, 80, 130, 110),   // second_up
        Crop(920, 300, 130, 110),  // second_mid
        Crop(920, 500, 130, 110),  // second_low
    )

    /** `mirror_path` 的取图范围：包含三条路径连线的整块 */
    val PATH_AREA = Crop(670, 70, 360, 540)

    /**
     * 画连线图前要遮掉的六个节点区域。
     *
     * 与 [LEGEND_NODES] **不是同一组坐标**（上游两处各写了一份，此处偏移 10px、
     * 尺寸小 20x20），照抄以免模型读到与训练时不同的构图。
     */
    val PATH_OCCLUDE: List<Crop> = listOf(
        Crop(670, 90, 110, 90),
        Crop(670, 300, 110, 90),
        Crop(670, 510, 110, 90),
        Crop(930, 90, 110, 90),
        Crop(930, 310, 110, 90),
        Crop(930, 510, 110, 90),
    )

    /**
     * 准备 `mirror_path` 的输入。
     *
     * 上游在裁剪前先往六个节点区域画**随机涂鸦**（亮色线条、斑点、圆形），
     * 目的是遮掉节点图标、让模型只看连线 —— 这是训练时的增广，推理时也照做，
     * 所以不能省：不遮挡等于给模型一张分布外的图。
     *
     * 与上游的唯一差别是随机源可指定种子（[seed]），好让单测能复现同一张图。
     * 遮挡本身仍是随机形状，落在模型训练时见过的分布内；换成纯色块填充则不在。
     *
     * @param screenBgr 整屏 BGR
     * @return 缩放到模型输入尺寸的 RGB 字节，长度 `outWidth * outHeight * 3`
     */
    fun preparePathInput(
        screenBgr: Mat,
        outWidth: Int,
        outHeight: Int,
        seed: Long = DEFAULT_SEED,
    ): ByteArray? {
        val work = screenBgr.clone()
        try {
            val rng = Random(seed)
            for (region in PATH_OCCLUDE) {
                drawGraffiti(work, region, rng)
            }
            val area = clamp(PATH_AREA, work.cols(), work.rows()) ?: return null
            val cropped = Mat(work, Rect(area.x, area.y, area.width, area.height))
            try {
                return toRgbBytes(cropped, outWidth, outHeight)
            } finally {
                cropped.release()
            }
        } finally {
            work.release()
        }
    }

    /** 准备 `mirror_legend` 的六张输入图；某格越界则整体返回空表 */
    fun prepareLegendInputs(screenBgr: Mat, outWidth: Int, outHeight: Int): List<ByteArray> {
        val result = ArrayList<ByteArray>(LEGEND_NODES.size)
        for (node in LEGEND_NODES) {
            val area = clamp(node, screenBgr.cols(), screenBgr.rows()) ?: return emptyList()
            val cropped = Mat(screenBgr, Rect(area.x, area.y, area.width, area.height))
            try {
                result += toRgbBytes(cropped, outWidth, outHeight) ?: return emptyList()
            } finally {
                cropped.release()
            }
        }
        return result
    }

    /**
     * BGR Mat → 指定尺寸的 RGB 字节。
     *
     * 通道要转成 RGB：模型是按 PIL 的 RGB 训练的，喂 BGR 会让红蓝互换，
     * 模型照样给出一个标签，只是错的。
     */
    internal fun toRgbBytes(bgr: Mat, outWidth: Int, outHeight: Int): ByteArray? {
        if (bgr.empty()) return null
        val resized = Mat()
        try {
            // 上游用 LANCZOS；OpenCV 的 INTER_AREA 在缩小时质量相当且更快，
            // 放大时退回 INTER_CUBIC
            val shrinking = bgr.cols() > outWidth || bgr.rows() > outHeight
            Imgproc.resize(
                bgr, resized, Size(outWidth.toDouble(), outHeight.toDouble()),
                0.0, 0.0,
                if (shrinking) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC,
            )
            val rgb = Mat()
            try {
                Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
                val bytes = ByteArray(outWidth * outHeight * 3)
                rgb.get(0, 0, bytes)
                return bytes
            } finally {
                rgb.release()
            }
        } finally {
            resized.release()
        }
    }

    /** 复刻上游 generate_random_graffiti：7-12 个亮色线条/斑点/圆形 */
    private fun drawGraffiti(target: Mat, region: Crop, rng: Random) {
        val area = clamp(region, target.cols(), target.rows()) ?: return
        val count = rng.nextInt(GRAFFITI_MIN, GRAFFITI_MAX + 1)
        repeat(count) {
            when (rng.nextInt(3)) {
                0 -> {
                    val p1 = Point(
                        (area.x + rng.nextInt(area.width + 1)).toDouble(),
                        (area.y + rng.nextInt(area.height + 1)).toDouble(),
                    )
                    val p2 = Point(
                        (area.x + rng.nextInt(area.width + 1)).toDouble(),
                        (area.y + rng.nextInt(area.height + 1)).toDouble(),
                    )
                    Imgproc.line(target, p1, p2, brightBgr(rng, 200), rng.nextInt(3, 6))
                }
                1 -> {
                    val c = Point(
                        (area.x + rng.nextInt(area.width + 1)).toDouble(),
                        (area.y + rng.nextInt(area.height + 1)).toDouble(),
                    )
                    Imgproc.circle(target, c, rng.nextInt(3, 7), brightBgr(rng, 150), -1)
                }
                else -> {
                    val margin = 10
                    if (area.width <= margin * 2 || area.height <= margin * 2) return@repeat
                    val c = Point(
                        (area.x + margin + rng.nextInt(area.width - margin * 2)).toDouble(),
                        (area.y + margin + rng.nextInt(area.height - margin * 2)).toDouble(),
                    )
                    Imgproc.circle(target, c, rng.nextInt(7, 13), brightBgr(rng, 180), -1)
                }
            }
        }
    }

    /** 偏亮的随机颜色，与游戏界面的连线颜色接近（上游同此意图） */
    private fun brightBgr(rng: Random, low: Int): Scalar {
        fun ch() = rng.nextInt(low, 256).toDouble()
        return Scalar(ch(), ch(), ch())
    }

    internal fun clamp(crop: Crop, width: Int, height: Int): Crop? {
        val x = crop.x.coerceIn(0, width)
        val y = crop.y.coerceIn(0, height)
        val w = crop.width.coerceAtMost(width - x)
        val h = crop.height.coerceAtMost(height - y)
        return if (w <= 0 || h <= 0) null else Crop(x, y, w, h)
    }

    private const val GRAFFITI_MIN = 7
    private const val GRAFFITI_MAX = 12

    /** 固定种子让同一帧得到同一张遮挡图，便于复现问题；上游是纯随机 */
    private const val DEFAULT_SEED = 0x5A1CL
}
