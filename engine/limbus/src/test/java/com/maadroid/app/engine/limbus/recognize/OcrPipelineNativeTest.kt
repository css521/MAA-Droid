package com.maadroid.app.engine.limbus.recognize

import com.maadroid.app.engine.Frame
import com.maadroid.app.engine.FrameSource
import com.maadroid.app.engine.limbus.action.ActionOutcome
import com.maadroid.app.engine.limbus.action.ActionRegistry
import com.maadroid.app.engine.limbus.action.FakeConfig
import com.maadroid.app.engine.limbus.action.FakeTemplateIndex
import com.maadroid.app.engine.limbus.action.LuxcavationActions
import com.maadroid.app.engine.limbus.action.TestActionContext
import com.maadroid.app.engine.limbus.recognize.ocr.OcrGeometry
import com.maadroid.app.engine.limbus.recognize.ocr.OcrImageOps
import com.maadroid.app.engine.limbus.recognize.ocr.PpOcrEngine
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Real OpenCV/ORT replay of synthetic level text; proves OCR dispatch, not phone gameplay. */
object OcrPipelineNativeTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2) { "usage: <OpenCV JNI library> <LALC resource root>" }
        System.load(File(args[0]).absolutePath)
        val screen = Mat(720, 1280, CvType.CV_8UC3, Scalar(25.0, 32.0, 40.0))
        try {
            // Two separated EXP levels and a distractor outside the OCR region.
            for ((text, point) in listOf("09" to Point(380.0, 217.0), "60" to Point(810.0, 217.0),
                "999" to Point(370.0, 440.0))) {
                Imgproc.putText(screen, text, point, Imgproc.FONT_HERSHEY_SIMPLEX, 1.1,
                    Scalar(230.0, 240.0, 245.0), 2, Imgproc.LINE_AA)
            }
            val region = Rect(250, 180, 1000, 50)
            val original = screen.clone()
            val masked = OcrImageOps.maskedFrame(screen, region)
            try {
                check(masked.cols() == 1280 && masked.rows() == 720)
                check(masked.get(440, 400).all { it == 0.0 })
                val expected = Mat(screen, region)
                val actual = Mat(masked, region)
                try { check(Core.norm(expected, actual, Core.NORM_INF) == 0.0) }
                finally { expected.release(); actual.release() }
                check(Core.norm(screen, original, Core.NORM_INF) == 0.0)
                check(OcrGeometry.detInputSize(masked.cols(), masked.rows()) == 1312 to 736)
                println("PASS OCR mask retains 1280x720, zeros distractors, preserves input and limits det input to 1312x736")

                val gray = Mat()
                val expectedGray = Mat()
                val preparedGray = Mat()
                val prepared = OcrImageOps.prepare(masked)
                val clahe = Imgproc.createCLAHE(2.0, Size(16.0, 16.0))
                try {
                    Imgproc.cvtColor(masked, gray, Imgproc.COLOR_BGR2GRAY)
                    clahe.apply(gray, expectedGray)
                    Imgproc.cvtColor(prepared, preparedGray, Imgproc.COLOR_BGR2GRAY)
                    check(Core.norm(expectedGray, preparedGray, Core.NORM_INF) == 0.0)
                    check(prepared.channels() == 3 && prepared.size() == screen.size())
                    println("PASS grayscale and CLAHE match upstream preprocessing")
                } finally {
                    gray.release(); expectedGray.release(); preparedGray.release(); prepared.release()
                    clahe.collectGarbage(); clahe.clear()
                }
            } finally { masked.release(); original.release() }

            var seq = 0L
            val frames = object : FrameSource {
                override suspend fun grab(): Frame {
                    val pixels = ByteArray(screen.rows() * screen.cols() * 3)
                    screen.get(0, 0, pixels)
                    return Frame(1280, 720, 1280 * 3, ++seq,
                        ByteBuffer.allocateDirect(pixels.size).apply { put(pixels); flip() })
                }
                override fun close() = Unit
            }
            val reader = requireNotNull(PpOcrEngine.load(File(args[1]), ::println))
            val recognizer = LimbusRecognizer(frames, FakeTemplateIndex(), { null }, ocr = reader, onLog = ::println)
            try {
                val crop = Crop(region.x, region.y, region.width, region.height)
                val observed = recognizer.detectText(crop)
                println("OCR levels: $observed")
                check(observed.none { "999" in it.text }) { "OCR read outside the requested mask" }
                val stage = observed.single { it.text == "09" }
                check(stage.x in 390..415 && stage.y in 197..214) { "OCR coordinates shifted: $stage" }
                check(observed.any { it.text == "60" && it.x in 820..845 && it.y in 197..214 })
                check(recognizer.detectText(Crop(2000, 100, 20, 20)).isEmpty())
                println("PASS real OCR uses original coordinates, both levels detected, out-of-frame mask rejected")

                LuxcavationActions.registerAll()
                val ctx = TestActionContext(recognize = recognizer,
                    config = FakeConfig().put("exp", "exp_stage", "09").put("exp", "luxcavation_mode", "enter"))
                check(ActionRegistry["exp_select_stage"]!!.execute(ctx) == ActionOutcome.Continue)
                val click = ctx.fakeInput.clicks().single()
                check(click.first in 400..425 && click.second == 480) { "Wrong level input: $click" }
                println("PASS real OCR -> EXP selection input $click; synthetic image only, no game transition asserted")
            } finally { recognizer.release() }
        } finally { screen.release() }
    }
}
