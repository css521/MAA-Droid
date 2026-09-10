package com.aliothmoon.maadroid.engine.limbus.recognize

import com.aliothmoon.maadroid.engine.Frame
import com.aliothmoon.maadroid.engine.FrameSource
import com.aliothmoon.maadroid.engine.limbus.action.ActionOutcome
import com.aliothmoon.maadroid.engine.limbus.action.ActionRegistry
import com.aliothmoon.maadroid.engine.limbus.action.LimbusActions
import com.aliothmoon.maadroid.engine.limbus.action.TestActionContext
import com.aliothmoon.maadroid.engine.limbus.pipeline.NodeRecognizer
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineRegistry
import com.aliothmoon.maadroid.engine.limbus.recognize.ocr.PpOcrEngine
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs

/** Real input-image/model/action boundary; no post-key Android transition is asserted. */
object RetainedPageNativeTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 4) { "usage: <OpenCV JNI> <LALC resources> <retained EXP screenshot> <home screenshot>" }
        System.load(File(args[0]).absolutePath)
        val root = File(args[1])
        val retained = Imgcodecs.imread(args[2])
        val home = Imgcodecs.imread(args[3])
        require(listOf(retained, home).all { it.cols() == 1280 && it.rows() == 720 })
        val blank = Mat(720, 1280, retained.type(), Scalar.all(0.0))
        var current = retained
        val frames = object : FrameSource {
            override suspend fun grab(): Frame {
                val bytes = ByteArray(1280 * 720 * 3).also { current.get(0, 0, it) }
                return Frame(1280, 720, 3840, 1, ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() })
            }
            override fun close() = Unit
        }
        LimbusActions.install()
        val source = File(root, "config/task").listFiles()!!.filter { it.extension == "json" }
            .associate { it.name to it.readText() }
        val pipeline = PipelineRegistry.load(source)
        val index = ResourcePackTemplateIndex.load(root, "en")
        val recognizer = LimbusRecognizer(frames, index, index::fileOf,
            ocr = requireNotNull(PpOcrEngine.load(root, ::println)), gameLanguage = "en")
        try {
            val gate = NodeRecognizer(recognizer)
            check(gate.recognize(pipeline.require("back_to_init_page")).hit)
            val label = recognizer.pyramidTemplateMatch("luxcavation", .85, Crop(20, 120, 260, 300))
            check(label.any { it.x in 120..170 && it.y in 175..220 }) { "Missing LALC title on retained page: $label" }
            val ctx = TestActionContext(recognize = recognizer)
            check(ActionRegistry["back_to_init_page"]!!.execute(ctx) == ActionOutcome.Continue)
            check(ctx.fakeInput.keyPresses() == listOf(111)) { "Expected upstream Esc: ${ctx.fakeInput.events}" }
            check(ctx.fakeInput.clicks().isEmpty())
            check(ctx.logs.any { "已识别采光副本选关页" in it })
            check(ctx.logs.none { "等待游戏加载或登录" in it })
            println("PASS retained EXP screenshot: LALC title $label; immediate Esc(111), no login wait")

            current = home
            check(!gate.recognize(pipeline.require("back_to_init_page")).hit) { "Actual home still enters recovery" }
            println("PASS separate actual home screenshot exits recovery gate; post-Esc transition not supplied")

            current = blank
            val loading = TestActionContext(recognize = recognizer)
            check(ActionRegistry["back_to_init_page"]!!.execute(loading) == ActionOutcome.Continue)
            check(loading.fakeInput.events.isEmpty())
            check(loading.logs.any { "等待游戏加载或登录" in it })
            println("PASS blank loading screenshot waits without key input")
        } finally {
            recognizer.release(); retained.release(); home.release(); blank.release()
        }
    }
}
