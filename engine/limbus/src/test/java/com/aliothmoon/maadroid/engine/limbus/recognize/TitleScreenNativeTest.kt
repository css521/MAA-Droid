package com.aliothmoon.maadroid.engine.limbus.recognize

import com.aliothmoon.maadroid.engine.Frame
import com.aliothmoon.maadroid.engine.FrameSource
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.opencv.imgcodecs.Imgcodecs

/** 用真实资源和已裁剪为 1280x720 的手机游戏画面运行；缺少输入即失败，不静默跳过。 */
object TitleScreenNativeTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 3) { "usage: <OpenCV JNI library> <LALC resource root> <1280x720 title screenshot>" }
        System.load(File(args[0]).absolutePath)
        val index = ResourcePackTemplateIndex.load(File(args[1]), "zh")
        val screen = Imgcodecs.imread(args[2])
        require(screen.cols() == 1280 && screen.rows() == 720)
        val pixels = ByteArray(1280 * 720 * 3)
        screen.get(0, 0, pixels)
        val buffer = ByteBuffer.allocateDirect(pixels.size).apply { put(pixels); flip() }
        val frames = object : FrameSource {
            override suspend fun grab() = Frame(1280, 720, 1280 * 3, 1, buffer)
            override fun close() = Unit
        }
        val recognizer = LimbusRecognizer(frames, index, index::fileOf, titleAnchorFiles = index.titleAnchors)
        try {
            check(recognizer.templateMatch("clear_all_caches").isEmpty())
            val target = requireNotNull(recognizer.titleScreenStart())
            check(target.x == 640 && target.y == 540)
            println("PASS selected Chinese template misses English title; downloaded English anchor locates safe start at ${target.x},${target.y}, score=${target.score}")
            check(index.fileOf("clear_all_caches")!!.path.contains("/zh/"))
            println("PASS game language remains Chinese; title adaptation does not change gameplay templates")
        } finally {
            recognizer.release()
            screen.release()
        }
    }
}
