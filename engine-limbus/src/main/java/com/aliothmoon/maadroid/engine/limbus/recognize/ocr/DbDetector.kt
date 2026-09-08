package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * DB（Differentiable Binarization）检测的后处理，移植上游
 * `rapidocr/ch_ppocr_det/utils.py` 的 `DBPostProcess`。
 *
 * 输入是模型给出的概率图，输出是文字块的四点框与各自得分。步骤与上游一致：
 * 二值化 → 膨胀 → 找轮廓 → 最小外接矩形 → 按框内平均概率打分筛选 →
 * unclip 外扩 → 缩放回原图坐标。
 *
 * 其中 unclip 不用 pyclipper：上游外扩后立刻重取最小外接矩形，而对矩形而言
 * 圆角外扩再重取矩形等于四边各外扩 [OcrGeometry.unclipDistance]（已实测核对，
 * 误差 ≤0.7px，随后要取整缩放，无实际影响）。
 */
object DbDetector {

    /**
     * @param probMap 概率图，CV_32F，尺寸为检测模型的输入尺寸
     * @param srcWidth 原图宽（结果坐标缩放回这个尺度）
     * @param srcHeight 原图高
     */
    fun boxesFrom(
        probMap: Mat,
        srcWidth: Int,
        srcHeight: Int,
        thresh: Float = OcrGeometry.DET_THRESH,
        boxThresh: Float = OcrGeometry.DET_BOX_THRESH,
        unclipRatio: Double = OcrGeometry.DET_UNCLIP_RATIO,
        maxCandidates: Int = OcrGeometry.DET_MAX_CANDIDATES,
    ): List<DetBox> {
        if (probMap.empty()) return emptyList()
        val mapW = probMap.cols()
        val mapH = probMap.rows()

        val binary = Mat()
        val dilated = Mat()
        try {
            Imgproc.threshold(probMap, binary, thresh.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            binary.convertTo(binary, CvType.CV_8UC1)

            // 上游 use_dilation=true，核是 2x2 全 1
            val kernel = Mat.ones(2, 2, CvType.CV_8UC1)
            try {
                Imgproc.dilate(binary, dilated, kernel)
            } finally {
                kernel.release()
            }

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            try {
                Imgproc.findContours(
                    dilated, contours, hierarchy,
                    Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE,
                )
            } finally {
                hierarchy.release()
            }

            val result = ArrayList<DetBox>()
            for (contour in contours.take(maxCandidates)) {
                try {
                    val box = processContour(
                        contour, probMap, mapW, mapH,
                        srcWidth, srcHeight, boxThresh, unclipRatio,
                    )
                    if (box != null) result += box
                } finally {
                    contour.release()
                }
            }
            return result
        } finally {
            binary.release()
            dilated.release()
        }
    }

    private fun processContour(
        contour: MatOfPoint,
        probMap: Mat,
        mapW: Int,
        mapH: Int,
        srcWidth: Int,
        srcHeight: Int,
        boxThresh: Float,
        unclipRatio: Double,
    ): DetBox? {
        val contour2f = MatOfPoint2f(*contour.toArray())
        val rect: RotatedRect
        try {
            rect = Imgproc.minAreaRect(contour2f)
        } finally {
            contour2f.release()
        }

        // 上游：短边小于 min_size 直接丢弃
        if (min(rect.size.width, rect.size.height) < OcrGeometry.DET_MIN_SIZE) return null

        val score = scoreOf(probMap, rect) ?: return null
        // 上游是 `if box_thresh > score: continue`，即严格小于才保留
        if (score < boxThresh) return null

        // unclip：四边各外扩 distance
        val distance = OcrGeometry.unclipDistance(rect.size.width, rect.size.height, unclipRatio)
        val expanded = RotatedRect(
            rect.center,
            Size(rect.size.width + 2 * distance, rect.size.height + 2 * distance),
            rect.angle,
        )
        // 上游外扩后再查一次短边，阈值是 min_size + 2
        if (min(expanded.size.width, expanded.size.height) < OcrGeometry.DET_MIN_SIZE + 2) return null

        val corners = Mat()
        val points: List<Pair<Float, Float>>
        try {
            Imgproc.boxPoints(expanded, corners)
            points = (0 until corners.rows()).map { r ->
                corners.get(r, 0)[0].toFloat() to corners.get(r, 1)[0].toFloat()
            }
        } finally {
            corners.release()
        }

        // 缩放回原图并夹到边界内
        val scaleX = srcWidth.toDouble() / mapW
        val scaleY = srcHeight.toDouble() / mapH
        val scaled = OcrGeometry.orderClockwise(points).map { (x, y) ->
            (x * scaleX).toFloat().coerceIn(0f, srcWidth.toFloat()) to
                    (y * scaleY).toFloat().coerceIn(0f, srcHeight.toFloat())
        }

        val left = scaled.minOf { it.first }.toInt()
        val top = scaled.minOf { it.second }.toInt()
        val right = scaled.maxOf { it.first }.toInt()
        val bottom = scaled.maxOf { it.second }.toInt()
        if (right <= left || bottom <= top) return null

        return DetBox(left, top, right, bottom, score)
    }

    /**
     * 框内平均概率，对应上游 `box_score_fast`：在框的外接矩形内以框为掩码求均值。
     *
     * 用均值而非最大值：单个高响应像素不足以说明这里有文字，
     * 取最大会让噪点也被当成文字块。
     */
    private fun scoreOf(probMap: Mat, rect: RotatedRect): Float? {
        val corners = Mat()
        try {
            Imgproc.boxPoints(rect, corners)
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            val pts = ArrayList<Point>(corners.rows())
            for (r in 0 until corners.rows()) {
                val x = corners.get(r, 0)[0]
                val y = corners.get(r, 1)[0]
                pts += Point(x, y)
                minX = min(minX, kotlin.math.floor(x).toInt())
                maxX = max(maxX, kotlin.math.ceil(x).toInt())
                minY = min(minY, kotlin.math.floor(y).toInt())
                maxY = max(maxY, kotlin.math.ceil(y).toInt())
            }
            minX = minX.coerceIn(0, probMap.cols() - 1)
            maxX = maxX.coerceIn(0, probMap.cols() - 1)
            minY = minY.coerceIn(0, probMap.rows() - 1)
            maxY = maxY.coerceIn(0, probMap.rows() - 1)
            if (maxX < minX || maxY < minY) return null

            val roi = Mat(probMap, Rect(minX, minY, maxX - minX + 1, maxY - minY + 1))
            val mask = Mat.zeros(roi.rows(), roi.cols(), CvType.CV_8UC1)
            try {
                val shifted = MatOfPoint(*pts.map { Point(it.x - minX, it.y - minY) }.toTypedArray())
                try {
                    Imgproc.fillPoly(mask, listOf(shifted), Scalar(1.0))
                } finally {
                    shifted.release()
                }
                return Core.mean(roi, mask).`val`[0].toFloat()
            } finally {
                roi.release()
                mask.release()
            }
        } finally {
            corners.release()
        }
    }
}

/** 一个检测到的文字块位置与其得分（尚未识别文字） */
data class DetBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val score: Float,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
