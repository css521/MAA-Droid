package com.maadroid.app.engine.limbus.recognize

import com.maadroid.app.engine.Frame
import com.maadroid.app.engine.FrameSource
import com.maadroid.app.engine.limbus.action.FakeInput
import com.maadroid.app.engine.limbus.action.ActionOutcome
import com.maadroid.app.engine.limbus.action.ActionRegistry
import com.maadroid.app.engine.limbus.action.LimbusActions
import com.maadroid.app.engine.limbus.action.TestActionContext
import com.maadroid.app.engine.limbus.pipeline.NodeRecognizer
import com.maadroid.app.engine.limbus.pipeline.PipelineRegistry
import com.maadroid.app.engine.limbus.pipeline.PipelineRunner
import com.maadroid.app.engine.limbus.recognize.ocr.PpOcrEngine
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs

/** Real OCR/template replay of a resized phone preview, not an original native capture. */
object MailboxNativeTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 3) { "usage: <OpenCV JNI> <LALC resource root> <empty-mailbox-preview.png>" }
        System.load(File(args[0]).absolutePath)
        val root = File(args[1])
        val screen = Imgcodecs.imread(args[2])
        require(screen.cols() == 1280 && screen.rows() == 720)
        val noClose = screen.clone()
        Mat(noClose, Rect(650, 515, 300, 70)).let { area ->
            try { area.setTo(Scalar.all(0.0)) } finally { area.release() }
        }
        val blank = Mat(screen.rows(), screen.cols(), screen.type(), Scalar.all(0.0))
        var current = screen
        var grabs = 0L
        val frames = object : FrameSource {
            override suspend fun grab(): Frame {
                val pixels = ByteArray(1280 * 720 * 3).also { current.get(0, 0, it) }
                return Frame(1280, 720, 3840, ++grabs,
                    ByteBuffer.allocateDirect(pixels.size).apply { put(pixels); flip() })
            }
            override fun close() = Unit
        }
        val index = ResourcePackTemplateIndex.load(root, "en")
        val reader = requireNotNull(PpOcrEngine.load(root, ::println))
        val recognizer = LimbusRecognizer(frames, index, index::fileOf, ocr = reader, gameLanguage = "en")
        try {
            // This is the actual failure: desktop templates miss the visible empty mailbox,
            // so the unmodified upstream inverse gate admits claim_mail.
            check(recognizer.templateMatch("no_mail_in_storage").isEmpty())
            check(recognizer.pyramidTemplateMatch("no_mail_in_storage", .85).isEmpty())
            check(recognizer.templateMatch("rewards_acquired_confirm").isEmpty())
            check(recognizer.pyramidTemplateMatch("rewards_acquired_confirm", .85).isEmpty())
            val source = File(root, "config/task").listFiles()!!
                .filter { it.extension == "json" }.associate { it.name to it.readText() }
            val registry = PipelineRegistry.load(source).withAndroidMailEntry()
            val gate = NodeRecognizer(recognizer)
            check(gate.recognize(registry.require("claim_mail")).hit)

            val before = grabs
            val mailbox = requireNotNull(recognizer.observeMailbox()) { "Supplied empty mailbox not recognized" }
            check(grabs == before + 1) { "Mailbox anchors were assembled from different frames" }
            check(mailbox.empty)
            val close = requireNotNull(mailbox.close)
            check(close.x in 760..795 && close.y in 535..565)
            println("PASS resized phone preview: empty mailbox, actual Close target=$close; desktop templates miss")

            LimbusActions.install()
            for (entry in listOf("claim_mail", "confirm_reward")) {
                val recorded = FakeInput()
                val trace = mutableListOf<String>()
                val runner = PipelineRunner(registry,
                    contextFactory = { name, node, hits -> TestActionContext(node, name, recorded, recognizer, recognizeResult = hits) },
                    recognizeGate = gate::recognize, onLog = trace::add,
                ).also { it.delayer = {} }

                // Replaying the same frame after input models a Close tap with no observed
                // effect. A reported tap must never advance the completion counter itself.
                check(runner.run(entry) == "邮箱仍未关闭或主页尚未就绪，请检查游戏画面后重试")
                check(trace.any { "节点 exit_mailbox 执行动作 key" in it }) { "Wrong mail branch: $trace" }
                check(trace.none { "节点 check_mail 执行动作" in it }) { "Replay must not claim post-click completion" }
                check(recorded.clicks() == listOf(close.x to close.y)) { "Wrong mail input: ${recorded.events}" }
                check(recorded.keyPresses().isEmpty())
                println("PASS upstream $entry -> exit_mailbox emits one Close tap; unchanged phone frame fails without completion, repeated claim or Escape")
            }

            current = noClose
            check(recognizer.observeMailbox() == MailboxObservation(close = null, empty = true)) {
                "A missing Close must not turn the visible mailbox into absence"
            }
            val blocked = TestActionContext(registry.require("exit_mailbox"), "exit_mailbox", recognize = recognizer)
            check(ActionRegistry["key"]!!.execute(blocked) ==
                ActionOutcome.Finish(false, "无法确认邮箱关闭按钮，请放大游戏画面检查邮箱后重试"))
            check(blocked.fakeInput.events.isEmpty())
            current = blank
            check(recognizer.observeMailbox() == null) { "A later blank frame reused old mailbox anchors" }
            println("PASS missing Close preserves mailbox presence and emits no input; later blank frame has no cached mailbox")
        } finally {
            recognizer.release()
            noClose.release()
            blank.release()
            screen.release()
        }
    }
}
