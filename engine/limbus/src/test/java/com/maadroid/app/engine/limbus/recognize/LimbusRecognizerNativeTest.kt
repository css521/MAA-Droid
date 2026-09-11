package com.maadroid.app.engine.limbus.recognize

import com.maadroid.app.engine.Frame
import com.maadroid.app.engine.FrameSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs

/** 与 AdvancedTemplateMatcherNativeTest 一样用 main 运行，验证 Android 适配层的实际 Mat 调用。 */
object LimbusRecognizerNativeTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        System.load(File(args.single()).absolutePath)
        val directory = Files.createTempDirectory("limbus-recognizer-test").toFile()
        val template = Mat(96, 96, CvType.CV_8UC3)
        val screen = Mat(190, 240, CvType.CV_8UC3, Scalar.all(0.0))
        val bytes = ByteArray(96 * 96 * 3).also { Random(42).nextBytes(it) }
        template.put(0, 0, bytes)
        val roi = Mat(screen, Rect(61, 37, 96, 96))
        try { template.copyTo(roi) } finally { roi.release() }
        check(Imgcodecs.imwrite(File(directory, "target.png").absolutePath, template))
        val stride = screen.cols() * 3 + 16 // 行尾 padding 不能成为下一行的像素。
        val buffer = ByteBuffer.allocateDirect(stride * screen.rows())
        val row = ByteArray(screen.cols() * 3)
        repeat(screen.rows()) { y -> screen.get(y, 0, row); buffer.position(y * stride); buffer.put(row) }
        var seq = 0L
        val source = object : FrameSource {
            override suspend fun grab() = Frame(screen.cols(), screen.rows(), stride, ++seq, buffer)
            override fun close() = Unit
        }
        val index = object : TemplateIndex {
            override fun namesByTag(tag: String) = listOf("target")
            override fun contains(name: String) = name == "target"
        }
        val recognizer = LimbusRecognizer(source, index, { File(directory, "$it.png") })
        try {
            val crop = Crop(61, 37, 96, 96)
            val expected = 109 to 85
            val exact = recognizer.preciseTemplateMatch("target", .999, crop).single()
            check(exact.x to exact.y == expected)
            // 同名模板先走灰度再走彩色，验证缓存不会串用。
            check(recognizer.templateMatch("target", .999, crop).single().let { it.x to it.y } == expected)
            check(recognizer.colorTemplateMatch("target", .999, crop).single().let { it.x to it.y } == expected)
            println("PASS cropped grayscale/color/precise dispatch with padded BGR rows")
            val scaled = recognizer.pyramidTemplateMatch("target", .8, crop)
            check(scaled.any { kotlin.math.abs(it.x - expected.first) <= 2 && kotlin.math.abs(it.y - expected.second) <= 2 })
            println("PASS pyramid dispatch restores crop coordinates")
            Core.setRNGSeed(42)
            val features = recognizer.featureMatch("target", .75, crop)
            check(features.size >= 8 && features.all { it.x in 61 until 157 && it.y in 37 until 133 })
            println("PASS feature dispatch reads BGR and restores keypoint coordinates")
            check(recognizer.preciseTemplateMatch("target", .9, Crop(900, 900, 40, 40)).isEmpty())
            check(recognizer.templateMatch("target", .9, Crop(900, 900, 40, 40)).isEmpty())
            check(recognizer.templateMatch("target", .9, maskTemplate = Crop(200, 200, 20, 20)).isEmpty())
            println("PASS out-of-bounds crops never fall back to whole-screen matching")
            val failure = runCatching {
                AdvancedTemplateMatcher.pyramidMatch(screen, template, checkActive = { throw CancellationException("stop") })
            }.exceptionOrNull()
            check(failure is CancellationException && !screen.empty() && !template.empty())
            println("PASS pyramid cancellation preserves caller-owned Mats")
            println("PASS 5 recognizer integration cases; 0 skipped")
        } finally {
            recognizer.release()
            screen.release()
            template.release()
            check(directory.deleteRecursively())
        }
    }
}
