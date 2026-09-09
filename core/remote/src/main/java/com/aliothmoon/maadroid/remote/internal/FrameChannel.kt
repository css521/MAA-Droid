package com.aliothmoon.maadroid.remote.internal

import android.os.SharedMemory
import android.system.OsConstants
import com.aliothmoon.maadroid.bridge.NativeBridgeLib
import com.aliothmoon.maadroid.third.Ln
import java.nio.ByteBuffer

/**
 * 提权进程侧的帧通道：把 native 帧缓冲的最新一帧交给 App 进程。
 *
 * 为什么需要它：MaaCore 跑在提权进程内，直接访问帧缓冲；但边狱引擎是 Kotlin + OpenCV，
 * 必须跑在普通 App 进程（onnxruntime 与 OpenCV 的 Java 绑定在 app_process 里加载不可靠），
 * 于是需要一条跨进程的取帧通道。
 *
 * 设计取舍：**常驻共享内存 + 每帧一次 memcpy**，而不是每帧新建 ashmem 或走 Binder 传数组。
 * 1280x720 BGR 一帧 2.6 MB，映射只做一次；Binder 单次事务上限约 1 MB，根本传不下整帧，
 * 而每帧新建 fd 会有可观的系统调用与 SELinux 开销。识别频率只有 1–5 fps，
 * 单次 memcpy 的成本可忽略。
 */
class FrameChannel {

    private var memory: SharedMemory? = null
    private var mapped: ByteBuffer? = null
    private var capacity = 0
    private val meta = LongArray(4)

    @Synchronized
    fun isOpen(): Boolean = memory != null

    /**
     * 建立或复用共享内存。尺寸变化时重建。
     *
     * @return 供 App 进程映射的 [SharedMemory]；失败返回 null
     */
    @Synchronized
    fun open(width: Int, height: Int): SharedMemory? {
        if (width <= 0 || height <= 0 || width.toLong() * height > Int.MAX_VALUE / BYTES_PER_PIXEL) {
            Ln.w("$TAG: open ignored, bad size ${width}x$height")
            return null
        }
        val needed = width * height * BYTES_PER_PIXEL
        memory?.let { existing ->
            if (capacity >= needed) return existing
            // 尺寸变大：旧内存不够用，释放重建
            close()
        }
        return runCatching {
            val shm = SharedMemory.create("maadroid-frame", needed)
            memory = shm // close the fd as well if mapReadWrite fails
            // 提权侧只写，App 侧只读：去掉写权限前先自己映射为可写
            mapped = shm.mapReadWrite()
            capacity = needed
            Ln.i("$TAG: opened ${width}x$height ($needed bytes)")
            shm
        }.onFailure {
            Ln.e("$TAG: open failed: ${it.message}")
            close()
        }.getOrNull()
    }

    /**
     * 拷一帧进共享内存。
     *
     * @return [width, height, stride, seq]；无可用帧或容量不足返回 null。
     *   容量不足时不做截断 —— 截断的帧会让模板匹配得到错误结果，宁可让调用方重开通道。
     */
    @Synchronized
    fun grab(): LongArray? {
        val buf = mapped ?: return null
        buf.clear()
        val copied = runCatching { NativeBridgeLib.copyLatestFrame(buf, meta) }
            .onFailure { Ln.e("$TAG: copyLatestFrame failed: ${it.message}") }
            .getOrDefault(0)
        if (copied <= 0) return null
        return meta.copyOf()
    }

    @Synchronized
    fun close() {
        mapped?.let { runCatching { SharedMemory.unmap(it) } }
        mapped = null
        memory?.let { runCatching { it.close() } }
        memory = null
        capacity = 0
    }

    private companion object {
        const val TAG = "FrameChannel"

        /** native 帧缓冲存 BGR 三通道（见 bridge_frame_buffer 的 bgr_data） */
        const val BYTES_PER_PIXEL = 3
    }
}
