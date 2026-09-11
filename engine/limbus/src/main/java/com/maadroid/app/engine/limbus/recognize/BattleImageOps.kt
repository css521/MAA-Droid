package com.maadroid.app.engine.limbus.recognize

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** 战斗图像管线；技能定位复用通用多尺度匹配，保留倍率供分类区域修正。 */
internal object BattleImageOps {
    /** v5.0.0: CLAHE + Gaussian + 截图 1.6..0.525 步长 .025，保留最佳命中的缩放比。 */
    suspend fun skillAnchors(screen: Mat, template: Mat): List<BattleSkillAnchor> {
        val context = currentCoroutineContext()
        return AdvancedTemplateMatcher.pyramidMatch(screen, template, threshold = 0.8,
            offsetY = BattlePerception.SKILL_AREA.y, checkActive = { context.ensureActive() })
            .map { BattleSkillAnchor(it.x, it.y, it.score, it.scale) }
    }

    /** PIL crop 的图外部分为黑色，不能 clamp 后把较小图块拉伸到分类器输入。 */
    fun paddedCrop(screen: Mat, crop: Crop): Mat {
        val out = Mat.zeros(crop.height, crop.width, screen.type())
        val left = maxOf(0, crop.x)
        val top = maxOf(0, crop.y)
        val right = minOf(screen.cols(), crop.x + crop.width)
        val bottom = minOf(screen.rows(), crop.y + crop.height)
        if (right <= left || bottom <= top) return out
        val src = Mat(screen, Rect(left, top, right - left, bottom - top))
        val dst = Mat(out, Rect(left - crop.x, top - crop.y, right - left, bottom - top))
        try {
            src.copyTo(dst)
        } finally {
            src.release()
            dst.release()
        }
        return out
    }

    suspend fun sinnerAvatars(screen: Mat): List<BattleSinnerAvatar> {
        val strip = Mat(screen, Rect(0, 685, 1280, 10))
        val mask = Mat()
        val colored = Mat()
        val closed = Mat()
        val hsv = Mat()
        val kernel = Mat.ones(5, 5, CvType.CV_8U)
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Core.inRange(strip, Scalar(20.0, 60.0, 205.0), Scalar(60.0, 100.0, 245.0), mask)
            Core.bitwise_and(strip, strip, colored, mask)
            Imgproc.morphologyEx(colored, closed, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            Imgproc.cvtColor(closed, hsv, Imgproc.COLOR_BGR2HSV)
            Core.inRange(hsv, Scalar(0.0, 0.0, 0.0), Scalar(180.0, 255.0, 50.0), mask)
            Core.bitwise_not(mask, mask)
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val centers = contours.mapNotNull { contour ->
                val m = Imgproc.moments(contour)
                if (m.m00 == 0.0) null else (m.m10 / m.m00).toInt() to (685 + (m.m01 / m.m00).toInt())
            }.sortedBy { it.first }
            return centers.map { (x, y) ->
                currentCoroutineContext().ensureActive()
                val avatar = paddedCrop(screen, BattlePerception.avatarCrop(x, y))
                try {
                    maskAvatar(avatar)
                    val bgr = ByteArray(80 * 55 * 3)
                    avatar.get(0, 0, bgr)
                    BattleSinnerAvatar(x, y, BattlePerception.avatarScores(bgr))
                } finally {
                    avatar.release()
                }
            }
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
            kernel.release()
            hsv.release()
            closed.release()
            colored.release()
            mask.release()
            strip.release()
        }
    }

    /** get_save_sinner_avatar.py 的六个多边形与三条 3px 线，按原始 80x55 坐标画。 */
    private fun maskAvatar(avatar: Mat) {
        val polygons = listOf(
            listOf(0 to 0, 0 to 25, 20 to 0),
            listOf(80 to 0, 80 to 28, 60 to 0),
            listOf(0 to 45, 0 to 55, 4 to 55),
            listOf(80 to 40, 80 to 55, 75 to 55),
            listOf(11 to 54, 4 to 30, 24 to 5, 56 to 5, 75 to 30, 70 to 55),
            listOf(33 to 0, 33 to 3, 46 to 3, 46 to 0),
        )
        val black = Scalar.all(0.0)
        for (points in polygons) {
            val polygon = MatOfPoint(*points.map { Point(it.first.toDouble(), it.second.toDouble()) }.toTypedArray())
            try {
                Imgproc.fillPoly(avatar, listOf(polygon), black)
            } finally {
                polygon.release()
            }
        }
        Imgproc.line(avatar, Point(0.0, 30.0), Point(80.0, 30.0), black, 3)
        Imgproc.line(avatar, Point(22.0, 0.0), Point(22.0, 55.0), black, 3)
        Imgproc.line(avatar, Point(58.0, 0.0), Point(58.0, 55.0), black, 3)
    }
}
