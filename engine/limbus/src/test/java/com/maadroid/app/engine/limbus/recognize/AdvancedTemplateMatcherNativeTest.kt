package com.maadroid.app.engine.limbus.recognize

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Random
import kotlin.math.abs

/**
 * Standalone native regression entry point; no Gradle, Android instrumentation or skipped tests.
 * Compile this + AdvancedTemplateMatcher.kt + the existing Match declaration with kotlinc and
 * OpenCV's Java jar, then run this class with an absolute native library path as args[0].
 * Without a path it calls System.loadLibrary(Core.NATIVE_LIBRARY_NAME).
 * Missing native libraries, missing FLANN-LSH and assertion failures all exit unsuccessfully.
 */
object AdvancedTemplateMatcherNativeTest {
    @JvmStatic fun main(args: Array<String>) {
        if (args.isNotEmpty()) System.load(File(args[0]).absolutePath) else System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val cases = listOf(
            "precise: two targets, noncontiguous crop, offsets, input ownership" to ::preciseCroppedTargets,
            "precise: raw pixels differ from blurred matching" to ::precisePreservesHighFrequency,
            "color: reject same-luminance different-color image" to ::colorDisambiguates,
            "alpha: discard channel without treating transparent pixels as mask" to ::alphaIsDiscarded,
            "pyramid: resize screen, restore coordinates, preserve scale" to ::pyramidFindsScale,
            "feature: real ORB + FLANN-LSH, screen coordinates, temporary cleanup" to ::featureFindsPoints,
            "feature: textureless image is a real no-match" to ::featureTextureless,
            "invalid input: grayscale is rejected, borrowed inputs survive" to ::invalidInput,
        )
        for ((name, test) in cases) {
            test()
            println("PASS $name")
        }
        println("PASS ${cases.size} native cases; OpenCV ${Core.getVersionString()}; 0 skipped")
    }

    private fun preciseCroppedTargets() = withMats { own ->
        val template = own(pattern(19, 17))
        val screen = own(Mat(130, 180, CvType.CV_8UC3, Scalar.all(0.0)))
        val crop = own(screen.submat(Rect(31, 23, 120, 80)))
        check(!crop.isContinuous)
        paste(template, crop, 5, 12)
        paste(template, crop, 75, 12)
        val snapshot = own(screen.clone())
        val hits = AdvancedTemplateMatcher.match("precise_template_match", crop, template, 0.999, 31, 23)
        check(hits.map { it.x to it.y }.toSet() == setOf(45 to 43, 115 to 43)) { hits }
        check(Core.norm(screen, snapshot, Core.NORM_INF) == 0.0)
        check(!screen.empty() && !crop.empty() && !template.empty())
    }

    private fun precisePreservesHighFrequency() = withMats { own ->
        val template = own(pattern(41, 39))
        val blurred = own(Mat())
        Imgproc.GaussianBlur(template, blurred, Size(5.0, 5.0), 0.0)
        check(AdvancedTemplateMatcher.preciseMatch(template, template, 0.999).size == 1)
        check(AdvancedTemplateMatcher.preciseMatch(blurred, template, 0.999).isEmpty())
        val enlarged = own(Mat())
        Imgproc.resize(template, enlarged, Size(), 2.0, 2.0, Imgproc.INTER_NEAREST)
        val hit = AdvancedTemplateMatcher.preciseMatch(enlarged, template, 0.999, 30, 50, 0.5).single()
        check(hit.x == 70 && hit.y == 88) { hit }
    }

    private fun colorDisambiguates() = withMats { own ->
        val template = own(pattern(48, 48))
        val blue = ByteArray(48 * 48 * 3)
        val red = ByteArray(blue.size)
        val rng = Random(717)
        for (i in 0 until 48 * 48) {
            val green = (rng.nextInt(160) + 30).toByte()
            blue[i * 3] = 150.toByte(); blue[i * 3 + 1] = green
            red[i * 3 + 1] = green; red[i * 3 + 2] = 57
        }
        template.put(0, 0, blue)
        val wrongColor = own(Mat(48, 48, CvType.CV_8UC3))
        wrongColor.put(0, 0, red)
        check(AdvancedTemplateMatcher.preciseMatch(wrongColor, template, 0.99).isNotEmpty())
        check(AdvancedTemplateMatcher.colorMatch(wrongColor, template, 0.85).isEmpty())
        val hit = AdvancedTemplateMatcher.colorMatch(template, template, 0.99, 11, 13).single()
        check(hit.x == 35 && hit.y == 37 && hit.colorSimilarity == 1.0)
        check(hit.templateScore > 0.999 && hit.score > 0.999)
    }

    private fun alphaIsDiscarded() = withMats { own ->
        val bgr = own(pattern(43, 37))
        val bgra = own(Mat())
        Imgproc.cvtColor(bgr, bgra, Imgproc.COLOR_BGR2BGRA)
        val transparent = own(Mat(37, 43, CvType.CV_8UC1, Scalar.all(0.0)))
        Core.insertChannel(transparent, bgra, 3)
        val before = own(bgra.clone())
        val hits = AdvancedTemplateMatcher.preciseMatch(bgr, bgra, 0.999)
        check(hits.single().x == 21)
        check(AdvancedTemplateMatcher.colorMatch(bgr, bgra, 0.999).size == 1)
        check(Core.norm(before, bgra, Core.NORM_INF) == 0.0)
        val other = own(pattern(43, 37, seed = 881))
        check(AdvancedTemplateMatcher.preciseMatch(other, bgra, 0.99).isEmpty())
    }

    private fun pyramidFindsScale() = withMats { own ->
        val template = own(pattern(64, 64))
        val enlarged = own(Mat())
        Imgproc.resize(template, enlarged, Size(80.0, 80.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val hits = AdvancedTemplateMatcher.pyramidMatch(enlarged, template, 0.80, 100, 200)
        check(hits.isNotEmpty()) { "No pyramid hit" }
        val best = hits.first()
        check(abs(best.scale - 0.8) <= 0.026) { best }
        check(abs(best.x - 140) <= 2 && abs(best.y - 240) <= 2) { best }
        check(hits.size == 1) { "Scales were not merged: $hits" }
        check(AdvancedTemplateMatcher.preciseMatch(enlarged, template, 0.80).isEmpty())
    }

    private fun featureFindsPoints() = withMats { own ->
        val texture = own(pattern(128, 112))
        val directory = File(System.getProperty("java.io.tmpdir"), "limbus-lsh-test-${System.nanoTime()}")
        check(directory.mkdir())
        try {
            Core.setRNGSeed(724)
            val matches = AdvancedTemplateMatcher.featureMatch(texture, texture, 0.75, 700, 300, temporaryDirectory = directory)
            check(matches.size >= 8) { "Only ${matches.size} feature points" }
            check(matches.all { it.x in 700 until 828 && it.y in 300 until 412 && it.score.isFinite() })
            check(matches.map { it.x to it.y }.distinct().size >= 8)
            check(directory.listFiles()!!.isEmpty())
            // Explicitly fail if configuring LSH is impossible. Never use BF or gray in that case.
            val noSuchDirectory = File(directory, "does-not-exist")
            val failure = runCatching {
                AdvancedTemplateMatcher.featureMatch(texture, texture, temporaryDirectory = noSuchDirectory)
            }.exceptionOrNull()
            check(failure is IllegalStateException && failure.message!!.contains("FLANN-LSH"))
            check(!texture.empty())
        } finally {
            check(directory.delete())
        }
    }

    private fun featureTextureless() = withMats { own ->
        val blank = own(Mat(100, 100, CvType.CV_8UC3, Scalar.all(64.0)))
        check(AdvancedTemplateMatcher.featureMatch(blank, blank).isEmpty())
    }

    private fun invalidInput() = withMats { own ->
        val gray = own(Mat(40, 40, CvType.CV_8UC1, Scalar.all(10.0)))
        val color = own(pattern(40, 40))
        for (mode in AdvancedTemplateMatcher.Mode.entries) {
            check(runCatching { AdvancedTemplateMatcher.match(mode, color, gray, 0.7) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(runCatching { AdvancedTemplateMatcher.preciseMatch(color, color, Double.NaN) }.exceptionOrNull() is IllegalArgumentException)
        val tooLarge = own(pattern(50, 50))
        check(AdvancedTemplateMatcher.preciseMatch(color, tooLarge).isEmpty())
        check(!gray.empty() && !color.empty())
    }

    private fun pattern(width: Int, height: Int, seed: Long = 123): Mat {
        val bytes = ByteArray(width * height * 3)
        Random(seed).nextBytes(bytes)
        return Mat(height, width, CvType.CV_8UC3).also { it.put(0, 0, bytes) }
    }

    private fun paste(source: Mat, target: Mat, x: Int, y: Int) {
        val roi = target.submat(Rect(x, y, source.cols(), source.rows()))
        try { source.copyTo(roi) } finally { roi.release() }
    }

    private fun withMats(block: ((Mat) -> Mat) -> Unit) {
        val owned = ArrayList<Mat>()
        try { block { it.also { mat -> owned += mat } } }
        finally { owned.asReversed().forEach { it.release() } }
    }
}
