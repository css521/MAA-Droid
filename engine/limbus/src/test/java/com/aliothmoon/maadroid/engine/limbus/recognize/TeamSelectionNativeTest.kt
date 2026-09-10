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
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs

/** Real OCR on a paused preview, not an Android capture or a completed battle. */
object TeamSelectionNativeTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size >= 3) { "usage: <OpenCV JNI> <LALC resource root> <1280x720 team preview> [negative images...]" }
        System.load(File(args[0]).absolutePath)
        val root = File(args[1])
        val original = Imgcodecs.imread(args[2])
        require(original.cols() == 1280 && original.rows() == 720)
        var current = original
        var grabs = 0L
        val frames = object : FrameSource {
            override suspend fun grab(): Frame {
                val bytes = ByteArray(1280 * 720 * 3).also { current.get(0, 0, it) }
                return Frame(1280, 720, 3840, ++grabs, ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() })
            }
            override fun close() = Unit
        }
        val index = ResourcePackTemplateIndex.load(root, "en")
        LimbusActions.install()
        val pipeline = PipelineRegistry.load(File(root, "config/task").listFiles()!!.filter { it.extension == "json" }
            .associate { it.name to it.readText() })
        val recognizer = LimbusRecognizer(frames, index, index::fileOf, gameLanguage = "en",
            ocr = requireNotNull(PpOcrEngine.load(root, ::println)), onInfo = ::println,
            onDiagnostic = { phase, detail -> println("$phase $detail") })
        try {
            check(recognizer.templateMatch("details").isEmpty()) { "Expected a desktop template miss on this Android preview" }
            val before = grabs
            check(recognizer.observeTeamSelection() != null)
            check(grabs == before + 1) { "Mixed OCR evidence from different frames" }
            for (section in listOf("exp", "thread")) {
                check(NodeRecognizer(recognizer).recognize(pipeline.require("${section}_choose_team")).hit)
            }
            println("PASS paused Android team preview: actual LALC Details misses, same-frame OCR authorizes exp/thread team gates")

            for (region in listOf(Rect(1100, 495, 160, 55), Rect(200, 170, 850, 400), Rect(200, 170, 850, 130))) {
                val partial = original.clone()
                val area = Mat(partial, region)
                area.setTo(Scalar.all(0.0)); area.release()
                current = partial
                try { check(recognizer.observeTeamSelection() == null) { "Incomplete team evidence passed: $region" } }
                finally { partial.release() }
            }
            for (path in args.drop(3)) {
                current = Imgcodecs.imread(path)
                try {
                    check(recognizer.observeTeamSelection() == null) { "Other game page misidentified as team selection: $path" }
                } finally { current.release() }
            }
            println("PASS missing counter, missing grid, missing upper row, and ${args.size - 3} other-page negative images")
        } finally { recognizer.release(); original.release() }
    }
}
