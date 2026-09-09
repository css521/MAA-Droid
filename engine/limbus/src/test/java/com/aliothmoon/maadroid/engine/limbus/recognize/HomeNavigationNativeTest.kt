package com.aliothmoon.maadroid.engine.limbus.recognize

import com.aliothmoon.maadroid.engine.Frame
import com.aliothmoon.maadroid.engine.FrameSource
import com.aliothmoon.maadroid.engine.limbus.action.LimbusActions
import com.aliothmoon.maadroid.engine.limbus.pipeline.NodeRecognizer
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineRegistry
import com.aliothmoon.maadroid.engine.limbus.recognize.ocr.PpOcrEngine
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs

/** Replay a supplied, cropped 1280x720 phone screenshot with real upstream PNGs and OCR models. */
object HomeNavigationNativeTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 3) { "usage: <OpenCV JNI library> <LALC resource root> <1280x720 home screenshot>" }
        System.load(File(args[0]).absolutePath)
        val root = File(args[1])
        val screen = Imgcodecs.imread(args[2])
        require(screen.cols() == 1280 && screen.rows() == 720)
        LimbusActions.install()
        val pipeline = PipelineRegistry.load(File(root, "config/task").listFiles()!!
            .filter { it.extension == "json" }.associate { it.name to it.readText() })
        val blank = Mat(screen.rows(), screen.cols(), screen.type(), Scalar.all(0.0))
        var current = screen
        val frames = object : FrameSource {
            override suspend fun grab(): Frame {
                val pixels = ByteArray(1280 * 720 * 3)
                current.get(0, 0, pixels)
                return Frame(1280, 720, 1280 * 3, 1, ByteBuffer.allocateDirect(pixels.size).apply { put(pixels); flip() })
            }
            override fun close() = Unit
        }
        try {
            for (language in listOf("en", "zh")) {
                val index = ResourcePackTemplateIndex.load(root, language)
                val reader = requireNotNull(PpOcrEngine.load(root, ::println))
                val recognizer = LimbusRecognizer(frames, index, index::fileOf, ocr = reader,
                    gameLanguage = language, onLog = ::println)
                try {
                    current = screen
                    val desktop = Imgcodecs.imread(index.fileOf("main_drive_no_text")!!.absolutePath)
                    try {
                        check(TemplateMatcher.match(screen, desktop, .85).isEmpty())
                    } finally { desktop.release() }
                    val drive = recognizer.templateMatch("main_drive_no_text").single()
                    check(drive.x in 960..1000 && drive.y in 635..670) { "Wrong Drive target: $drive" }
                    val window = recognizer.templateMatch("main_window_no_text").single()
                    check(window.x in 805..845 && window.y in 630..670) { "Wrong Window target: $window" }
                    val gate = NodeRecognizer(recognizer)
                    val labels = Mat(screen, Rect(640, 400, 640, 320))
                    try { println("OCR navigation: ${reader.detect(labels, mergeX = false, mergeY = false)}") }
                    finally { labels.release() }
                    check(!gate.recognize(pipeline.require("back_to_init_page")).hit)
                    check(gate.recognize(pipeline.require("main_window_confirm")).hit)
                    check(gate.recognize(pipeline.require("main_drive_confirm")).hit)
                    val wrongLanguage = gate.recognize(pipeline.require("game_language_confirm")).hit
                    check(wrongLanguage == (language != "en")) { "Language gate wrong for $language" }
                    println("PASS $language: desktop misses; phone navigation locates Drive=$drive Window=$window; recovery bypassed; wrongLanguage=$wrongLanguage")
                    current = blank
                    check(recognizer.templateMatch("main_drive_no_text").isEmpty())
                    check(recognizer.templateMatch("main_window_no_text").isEmpty())
                    check(recognizer.templateMatch("main_drive_with_text").isEmpty())
                    println("PASS blank/loading frame has no cached navigation hit")
                } finally { recognizer.release() }
            }
        } finally {
            blank.release()
            screen.release()
        }
    }
}
