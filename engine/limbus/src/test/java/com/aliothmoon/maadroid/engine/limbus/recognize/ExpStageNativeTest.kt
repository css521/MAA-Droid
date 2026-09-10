package com.aliothmoon.maadroid.engine.limbus.recognize

import com.aliothmoon.maadroid.engine.Frame
import com.aliothmoon.maadroid.engine.FrameSource
import com.aliothmoon.maadroid.engine.limbus.action.ActionOutcome
import com.aliothmoon.maadroid.engine.limbus.action.ActionRegistry
import com.aliothmoon.maadroid.engine.limbus.action.FakeConfig
import com.aliothmoon.maadroid.engine.limbus.action.FakeTemplateIndex
import com.aliothmoon.maadroid.engine.limbus.action.LuxcavationActions
import com.aliothmoon.maadroid.engine.limbus.action.TestActionContext
import com.aliothmoon.maadroid.engine.limbus.recognize.ocr.PpOcrEngine
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.opencv.imgcodecs.Imgcodecs

/** Real user-image/model/action replay. Does not assert a game transition after the recorded input. */
object ExpStageNativeTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 3) { "usage: <OpenCV JNI> <LALC resource root> <test resources/ocr>" }
        System.load(File(args[0]).absolutePath)
        LuxcavationActions.registerAll()
        for (name in listOf("exp-stage-linear.png", "exp-stage-nearest.png")) {
            val screen = Imgcodecs.imread(File(args[2], name).absolutePath)
            require(screen.cols() == 1280 && screen.rows() == 720)
            val pixels = ByteArray(1280 * 720 * 3).also { screen.get(0, 0, it) }
            var grabs = 0L
            val source = object : FrameSource {
                override suspend fun grab() = Frame(1280, 720, 3840, ++grabs,
                    ByteBuffer.allocateDirect(pixels.size).apply { put(pixels); flip() })
                override fun close() = Unit
            }
            val diagnostics = mutableListOf<String>()
            val reader = requireNotNull(PpOcrEngine.load(File(args[1]), ::println))
            val recognizer = LimbusRecognizer(source, FakeTemplateIndex(), { null }, ocr = reader,
                onDiagnostic = { phase, detail -> diagnostics += "$phase $detail" })
            try {
                val crop = Crop(250, 180, 1000, 50)
                for ((stage, range) in listOf("07" to 320..360, "08" to 660..705, "09" to 1005..1045)) {
                    val before = grabs
                    val matches = recognizer.findText(stage, crop)
                    check(grabs == before + 1) { "Fallback read a different frame" }
                    check(matches.size == 1 && matches.single().x in range) { "$name $stage: $matches" }
                }
                check(diagnostics.any { "target=09" in it && "size=1280x720 stride=3840" in it && "hits=1" in it })
                if (name.contains("nearest")) {
                    check(diagnostics.any { "target=09" in it && " color=[" in it && "hits=1" in it })
                }
                check(recognizer.findText("109", crop).isEmpty())
                check(recognizer.findText("9", crop).none { it.x in 1005..1045 }) {
                    "Leading zero was discarded from the stage number (the title may independently contain #9)"
                }
                check(recognizer.findText("09", Crop(250, 300, 1000, 50)).isEmpty())
                check(recognizer.findText("09", crop, threshold = 1.0).isEmpty()) { "Fallback lowered confidence" }

                for ((mode, y) in listOf("enter" to 480, "skip battle" to 515)) {
                    val ctx = TestActionContext(recognize = recognizer,
                        config = FakeConfig().put("exp", "exp_stage", "09").put("exp", "luxcavation_mode", mode))
                    check(ActionRegistry["exp_select_stage"]!!.execute(ctx) == ActionOutcome.Continue)
                    val click = ctx.fakeInput.clicks().single()
                    check(click.first in 1015..1055 && click.second == y) { "Wrong card/button: $click" }
                    check(ctx.slept.isEmpty()) { "Visible stage should not trigger a swipe" }
                    println("PASS $name $mode: 09 -> input $click")
                }
                println("PASS $name: 07/08/09, same-frame fallback, numeric boundary, mask and confidence; ${diagnostics.first { "target=09" in it }}")
            } finally { recognizer.release(); screen.release() }
        }
    }
}
