package com.maadroid.app.engine

import java.nio.ByteBuffer

/**
 * 一帧屏幕内容。
 *
 * [buffer] 是直接缓冲区（映射自跨进程共享内存），生命周期只在本次 [FrameSource.grab]
 * 到下次 grab 之间有效 —— 需要留存请自行拷贝。
 *
 * 像素格式 **BGR 三通道**，与 native 帧缓冲一致（bridge_frame_buffer 存 bgr_data，
 * MaaCore 也按此消费）。这对 OpenCV 是原生通道序，构造 Mat 无需转换。
 * [stride] 为行距（字节），当前实现为紧凑的 `width * 3`，但读取时仍应按 stride 步进
 * 以免将来引入对齐填充后失效。
 */
class Frame(
    val width: Int,
    val height: Int,
    val stride: Int,
    val seq: Long,
    val buffer: ByteBuffer,
)

/**
 * 帧来源。
 *
 * 实现走「常驻共享内存 + 每帧一次 memcpy」：1280x720 BGR 一帧 2.6 MB，映射只做一次，
 * 单次拷贝亚毫秒级，而识别频率只有 1–5 fps，开销可忽略。这样 OpenCV 与 onnxruntime
 * 都能留在普通 App 进程，不必在 app_process 里加载。
 */
interface FrameSource {
    /** 取最新一帧；设备未就绪或通道已关闭返回 null */
    suspend fun grab(): Frame?

    fun close()
}

/**
 * 输入注入。坐标是 [GameProfile.display] 的逻辑坐标，由实现映射到真实显示器。
 *
 * [contact] 是手指 id（0..15），多指手势用不同 contact 并行下压。
 */
interface InputSink {
    fun touchDown(x: Int, y: Int, contact: Int = 0)
    fun touchMove(x: Int, y: Int, contact: Int = 0)
    fun touchUp(x: Int, y: Int, contact: Int = 0)

    /** 整体取消当前手势，释放所有在场手指 */
    fun touchCancel()

    /**
     * 按键注入。边狱的 Android 客户端认硬件按键，LALC 流水线里的 key 节点
     * 直接映射为 keycode（enter→66、esc→111、p→44）。
     */
    fun keyDown(keyCode: Int)
    fun keyUp(keyCode: Int)
}

/** 设备与游戏进程控制 */
interface DeviceControl {
    fun startApp(packageName: String): Boolean
    fun stopApp(packageName: String)
    fun isAppAlive(packageName: String): Boolean
    fun isPackageInstalled(packageName: String): Boolean

    /** 强制显示规格；[GameProfile.display] 要求的分辨率靠它落地 */
    fun setDisplaySize(width: Int, height: Int, dpi: Int): Boolean
}
