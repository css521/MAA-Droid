package com.maadroid.app.engine.limbus.recognize

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.features2d.FlannBasedMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Ports of LALC recognize/{color,precise,pyramid}_template_match.py and feature_match.py.
 *
 * Inputs are borrowed, nonempty CV_8UC3 BGR or CV_8UC4 BGRA Mats, never Bitmap RGBA.
 * Alpha is discarded, as in upstream pil_to_cv2; transparent pixels are NOT a matching mask.
 * Upstream mask/mask_template mean crops: pass an already cropped screen/template, and the
 * ACTUAL clamped screen crop origin as offsetX/Y. Template crop origins are not added.
 * Outputs use input-screen pixels plus that offset, not an implicit 1280x720 conversion.
 * The caller loads OpenCV and owns/releases inputs (including any submat headers).
 *
 * Explicit upstream bug fixes: color's default sends BGR into single-channel CLAHE;
 * here only candidate detection uses gray CLAHE, while color scoring uses untouched BGR.
 * Feature returns keypoints (not object centers); its upstream 2x coordinates are restored
 * to screen pixels here. Its unusual score and lack of merging are otherwise preserved.
 * Color ROIs use integer half-sizes to avoid upstream's one-pixel shift for odd templates.
 * No mode falls back to another algorithm. Missing native/LSH support is an error.
 */
object AdvancedTemplateMatcher {
    enum class Mode(val upstreamName: String) {
        COLOR("color_template_match"), FEATURE("feature_match"),
        PYRAMID("pyramid_template_match"), PRECISE("precise_template_match");

        companion object {
            fun fromUpstreamName(name: String): Mode = entries.firstOrNull { it.upstreamName == name }
                ?: throw IllegalArgumentException("Unsupported advanced matching mode: $name")
        }
    }

    /** scale multiplies the SCREEN, so the object's size is templateSize / scale. */
    data class ScaledMatch(val x: Int, val y: Int, val score: Double, val scale: Double) {
        fun asMatch() = Match(x, y, score)
    }

    data class ColorMatch(
        val x: Int, val y: Int, val score: Double,
        val templateScore: Double, val colorSimilarity: Double,
    ) {
        fun asMatch() = Match(x, y, score)
    }

    fun match(
        mode: String, screenBgr: Mat, templateBgr: Mat, threshold: Double,
        offsetX: Int = 0, offsetY: Int = 0,
    ): List<Match> = match(Mode.fromUpstreamName(mode), screenBgr, templateBgr, threshold, offsetX, offsetY)

    fun match(
        mode: Mode, screenBgr: Mat, templateBgr: Mat, threshold: Double,
        offsetX: Int = 0, offsetY: Int = 0,
    ): List<Match> = when (mode) {
        Mode.COLOR -> colorMatch(screenBgr, templateBgr, threshold, offsetX, offsetY).map { it.asMatch() }
        Mode.FEATURE -> featureMatch(screenBgr, templateBgr, threshold, offsetX, offsetY)
        Mode.PYRAMID -> pyramidMatch(screenBgr, templateBgr, threshold, offsetX, offsetY).map { it.asMatch() }
        Mode.PRECISE -> preciseMatch(screenBgr, templateBgr, threshold, offsetX, offsetY)
    }

    /** Raw gray CCOEFF_NORMED: deliberately no CLAHE, histogram equalization or blur. */
    fun preciseMatch(
        screenBgr: Mat, templateBgr: Mat, threshold: Double = 0.7,
        offsetX: Int = 0, offsetY: Int = 0, screenshotScale: Double = 1.0,
        grayscale: Boolean = true,
    ): List<Match> = nativeCall("precise_template_match") {
        validate(screenBgr, templateBgr, threshold)
        require(screenshotScale.isFinite() && screenshotScale > 0) { "screenshotScale must be positive" }
        Mats().use { mats ->
            val screen = if (grayscale) mats.gray(screenBgr) else mats.bgr(screenBgr)
            val template = if (grayscale) mats.gray(templateBgr) else mats.bgr(templateBgr)
            val resized = if (screenshotScale == 1.0) screen else {
                val width = scaledDimension(screen.cols(), screenshotScale)
                val height = scaledDimension(screen.rows(), screenshotScale)
                if (width == 0 || height == 0) return@use emptyList()
                mats.own(Mat()).also {
                    Imgproc.resize(screen, it, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                }
            }
            mergeNearby(correlate(resized, template, threshold, offsetX, offsetY, screenshotScale))
                .map { it.asMatch() }
        }
    }

    /** CLAHE(1.5,8x8), blur(5x5), then resize SCREEN at [maxScale,minScale) by -0.025. */
    fun pyramidMatch(
        screenBgr: Mat, templateBgr: Mat, threshold: Double = 0.7,
        offsetX: Int = 0, offsetY: Int = 0, maxScale: Double = 1.6, minScale: Double = 0.5,
        checkActive: () -> Unit = {},
    ): List<ScaledMatch> = nativeCall("pyramid_template_match") {
        validate(screenBgr, templateBgr, threshold)
        val scales = pyramidScales(maxScale, minScale)
        Mats().use { mats ->
            val screen = mats.enhancedGray(screenBgr)
            val template = mats.enhancedGray(templateBgr)
            val all = ArrayList<ScaledMatch>()
            val resized = mats.own(Mat())
            for (scale in scales) {
                checkActive()
                val width = scaledDimension(screen.cols(), scale)
                val height = scaledDimension(screen.rows(), scale)
                if (width < template.cols() || height < template.rows()) continue
                Imgproc.resize(screen, resized, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                all += correlate(resized, template, threshold, offsetX, offsetY, scale)
            }
            // Merge in original screenshot coordinates, across ALL scales, keeping the winner's scale.
            mergeNearby(all)
        }
    }

    /** Gray candidate threshold=max(0,threshold-.1), then upstream BGR histogram reranking. */
    fun colorMatch(
        screenBgr: Mat, templateBgr: Mat, threshold: Double = 0.7,
        offsetX: Int = 0, offsetY: Int = 0, colorWeight: Double = 0.5,
        colorThreshold: Double = 0.5,
    ): List<ColorMatch> = nativeCall("color_template_match") {
        validate(screenBgr, templateBgr, threshold)
        require(colorWeight.isFinite() && colorWeight in 0.0..1.0)
        require(colorThreshold.isFinite() && colorThreshold > 0 && colorThreshold <= 1)
        Mats().use { mats ->
            val screen = mats.bgr(screenBgr)
            val template = mats.bgr(templateBgr)
            if (template.cols() > screen.cols() || template.rows() > screen.rows()) return@use emptyList()
            val candidates = mergeNearby(correlate(
                mats.enhancedGray(screen), mats.enhancedGray(template), max(0.0, threshold - 0.1), 0, 0, 1.0,
            ))
            val templateHist = mats.histogram(template)
            candidates.mapNotNull { candidate ->
                Mats().use { regionMats ->
                    // Integer half-width matches the candidate center, including odd-sized templates.
                    // Upstream int(center-w/2) shifts odd widths left by one; do not reproduce that bug.
                    val rect = Rect(candidate.x - template.cols() / 2, candidate.y - template.rows() / 2,
                        template.cols(), template.rows())
                    val region = regionMats.own(screen.submat(rect))
                    val rawColor = 1.0 - Imgproc.compareHist(templateHist, regionMats.histogram(region), Imgproc.HISTCMP_BHATTACHARYYA)
                    val color = normalizeColorSimilarity(rawColor, colorThreshold)
                    val score = candidate.score * (1.0 - colorWeight) + color * colorWeight
                    if (score.isFinite() && score >= threshold) ColorMatch(
                        candidate.x + offsetX, candidate.y + offsetY, score, candidate.score, color,
                    ) else null
                }
            }.sortedByDescending { it.score }
        }
    }

    /**
     * ORB(1000,1.2,edgeThreshold=20), 2x linear resize, FLANN LSH(6,12,1), checks=50, k=2.
     * threshold is Lowe's ratio, not a final score cutoff. Upstream returns each accepted
     * screen keypoint without clustering/homography; do not use these as object centers.
     * Java OpenCV exposes LSH parameters via read(filename), not its constructor. A temporary
     * parameter file is deleted after read; pass context.cacheDir if java.io.tmpdir is unusable.
     */
    fun featureMatch(
        screenBgr: Mat, templateBgr: Mat, threshold: Double = 0.75,
        offsetX: Int = 0, offsetY: Int = 0, minMatches: Int = 8, temporaryDirectory: File? = null,
    ): List<Match> = nativeCall("feature_match (ORB + FLANN-LSH)") {
        validate(screenBgr, templateBgr, threshold)
        require(minMatches > 0) { "minMatches must be positive" }
        Mats().use { mats ->
            val screen = mats.own(Mat())
            val template = mats.own(Mat())
            Imgproc.resize(mats.gray(screenBgr), screen, Size(), 2.0, 2.0, Imgproc.INTER_LINEAR)
            Imgproc.resize(mats.gray(templateBgr), template, Size(), 2.0, 2.0, Imgproc.INTER_LINEAR)
            val templatePoints = mats.own(MatOfKeyPoint())
            val screenPoints = mats.own(MatOfKeyPoint())
            val templateDescriptors = mats.own(Mat())
            val screenDescriptors = mats.own(Mat())
            val noMask = mats.own(Mat())
            val orb = ORB.create(1000, 1.2f, 8, 20)
            try {
                orb.detectAndCompute(template, noMask, templatePoints, templateDescriptors)
                orb.detectAndCompute(screen, noMask, screenPoints, screenDescriptors)
            } finally {
                orb.clear()
            }
            // A textureless image is a genuine no-match, not a missing-dependency fallback.
            if (templateDescriptors.empty() || screenDescriptors.rows() < 2) return@use emptyList()
            val matcher = createLshMatcher(temporaryDirectory)
            val neighbors = ArrayList<MatOfDMatch>()
            try {
                matcher.knnMatch(templateDescriptors, screenDescriptors, neighbors, 2)
                val pairs = neighbors.map { it.toArray() }
                val good = pairs.filter { it.size >= 2 && passesRatio(it[0].distance.toDouble(), it[1].distance.toDouble(), threshold) }
                    .map { it[0] }
                if (good.size < minMatches) return@use emptyList()
                val firstDistance = pairs.firstOrNull()?.firstOrNull()?.distance
                    ?: error("FLANN-LSH returned no first neighbor required by upstream feature score")
                val score = 1.0 - good.first().distance / max(1.0, firstDistance.toDouble())
                val points = screenPoints.toArray()
                good.map {
                    val point = points[it.trainIdx].pt
                    Match((point.x / 2.0).toInt() + offsetX, (point.y / 2.0).toInt() + offsetY, score)
                }
            } finally {
                neighbors.forEach { it.release() }
                matcher.clear()
            }
        }
    }

    private fun createLshMatcher(directory: File?): FlannBasedMatcher {
        val matcher = FlannBasedMatcher.create()
        try {
            val parameters = File.createTempFile("limbus-flann-lsh-", ".yml", directory)
            try {
                parameters.writeText(LSH_PARAMETERS)
                matcher.read(parameters.absolutePath)
            } finally {
                check(parameters.delete()) { "Cannot delete temporary FLANN-LSH parameters: $parameters" }
            }
            return matcher
        } catch (e: Exception) {
            matcher.clear()
            throw IllegalStateException("feature_match requires OpenCV FLANN-LSH and a writable temporary directory", e)
        }
    }

    private fun validate(screen: Mat, template: Mat, threshold: Double) {
        require(threshold.isFinite() && threshold in 0.0..1.0) { "threshold must be in [0,1]" }
        for ((name, mat) in listOf("screenBgr" to screen, "templateBgr" to template)) {
            require(!mat.empty() && mat.dims() == 2 && mat.depth() == CvType.CV_8U && mat.channels() in 3..4) {
                "$name must be a nonempty CV_8UC3 BGR or CV_8UC4 BGRA Mat; convert Bitmap RGBA before calling"
            }
        }
    }

    private fun correlate(
        screen: Mat, template: Mat, threshold: Double, offsetX: Int, offsetY: Int, scale: Double,
    ): List<ScaledMatch> {
        if (template.cols() > screen.cols() || template.rows() > screen.rows()) return emptyList()
        return Mats().use { mats ->
            val result = mats.own(Mat())
            Imgproc.matchTemplate(screen, template, result, Imgproc.TM_CCOEFF_NORMED)
            val row = FloatArray(result.cols())
            val matches = ArrayList<ScaledMatch>()
            for (y in 0 until result.rows()) {
                result.get(y, 0, row)
                for (x in row.indices) {
                    val score = row[x].toDouble()
                    if (score.isFinite() && score >= threshold) matches += center(
                        x, y, template.cols(), template.rows(), score, scale, offsetX, offsetY,
                    )
                }
            }
            matches
        }
    }

    internal fun center(x: Int, y: Int, width: Int, height: Int, score: Double, scale: Double,
                        offsetX: Int, offsetY: Int) = ScaledMatch(
        ((x + width / 2) / scale).toInt() + offsetX,
        ((y + height / 2) / scale).toInt() + offsetY, score, scale,
    )

    internal fun mergeNearby(candidates: List<ScaledMatch>): List<ScaledMatch> {
        val result = ArrayList<ScaledMatch>()
        for (candidate in candidates.filter { it.score.isFinite() }.sortedByDescending { it.score }) {
            if (result.none { abs(candidate.x.toLong() - it.x) < 20 && abs(candidate.y.toLong() - it.y) < 20 }) {
                result += candidate
            }
        }
        return result
    }

    internal fun pyramidScales(maxScale: Double, minScale: Double): List<Double> {
        require(maxScale.isFinite() && minScale.isFinite() && minScale > 0 && maxScale > minScale)
        val count = ceil((maxScale - minScale) / 0.025)
        require(count <= 10000) { "Too many pyramid scales" }
        // numpy.arange uses the representable step (start+step)-start, including its rounding.
        val step = (maxScale - 0.025) - maxScale
        require(step < 0) { "Pyramid step is not representable at this scale" }
        return List(count.toInt()) { maxScale + it * step }
    }

    internal fun normalizeColorSimilarity(raw: Double, threshold: Double): Double =
        if (raw > threshold) 1.0 else raw / threshold

    internal fun passesRatio(nearest: Double, second: Double, threshold: Double): Boolean =
        nearest.isFinite() && second.isFinite() && nearest < threshold * second

    private fun scaledDimension(size: Int, scale: Double): Int {
        val value = size * scale
        require(value.isFinite() && value <= Int.MAX_VALUE) { "Scaled image dimension overflows Int" }
        return value.toInt()
    }

    private inline fun <T> nativeCall(mode: String, block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        throw IllegalStateException("$mode requires loaded OpenCV imgproc/features2d/flann native support; no fallback is available", e)
    }

    /** Tracks only owned Mats. All allocations/submat headers are released, including on failure. */
    private class Mats : Closeable {
        private val owned = ArrayList<Mat>()
        fun <T : Mat> own(mat: T): T = mat.also { owned += it }
        fun bgr(src: Mat): Mat = if (src.channels() == 3) src else own(Mat()).also {
            Imgproc.cvtColor(src, it, Imgproc.COLOR_BGRA2BGR)
        }
        fun gray(src: Mat): Mat = own(Mat()).also {
            Imgproc.cvtColor(src, it, if (src.channels() == 4) Imgproc.COLOR_BGRA2GRAY else Imgproc.COLOR_BGR2GRAY)
        }
        fun enhancedGray(src: Mat): Mat {
            val gray = gray(src)
            val equalized = own(Mat())
            val blurred = own(Mat())
            val clahe = Imgproc.createCLAHE(1.5, Size(8.0, 8.0))
            try {
                clahe.apply(gray, equalized)
            } finally {
                clahe.collectGarbage()
                clahe.clear()
            }
            Imgproc.GaussianBlur(equalized, blurred, Size(5.0, 5.0), 0.0)
            return blurred
        }
        fun histogram(src: Mat): Mat {
            val hist = own(Mat())
            // Joint 80x80x80 histogram, not three independent channel histograms.
            Imgproc.calcHist(listOf(src), own(MatOfInt(0, 1, 2)), own(Mat()), hist,
                own(MatOfInt(80, 80, 80)), own(MatOfFloat(0f, 256f, 0f, 256f, 0f, 256f)))
            Core.normalize(hist, hist, 0.0, 1.0, Core.NORM_MINMAX)
            return hist
        }
        override fun close() {
            owned.asReversed().forEach { it.release() }
        }
    }

    // OpenCV's DescriptorMatcher serialization format, also emitted by upstream flann.write().
    private val LSH_PARAMETERS = """
        %YAML:1.0
        ---
        format: 3
        indexParams:
           - { name: algorithm, type: 9, value: 6 }
           - { name: key_size, type: 4, value: 12 }
           - { name: multi_probe_level, type: 4, value: 1 }
           - { name: table_number, type: 4, value: 6 }
        searchParams:
           - { name: checks, type: 4, value: 50 }
           - { name: eps, type: 5, value: 0. }
           - { name: explore_all_trees, type: 8, value: 0 }
           - { name: sorted, type: 8, value: 1 }
    """.trimIndent() + "\n"
}
