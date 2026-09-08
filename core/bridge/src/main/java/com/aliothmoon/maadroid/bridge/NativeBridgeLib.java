package com.aliothmoon.maadroid.bridge;

import android.graphics.Bitmap;
import android.view.Surface;

import com.aliothmoon.maadroid.third.Ln;

import dalvik.annotation.optimization.FastNative;

public class NativeBridgeLib {
    public static boolean LOADED;

    static {
        try {
            System.loadLibrary("bridge");
            LOADED = true;
        } catch (Throwable e) {
            LOADED = false;
            Ln.e("NativeBridgeLib static initializer: ", e);
        }
    }

    // for test
    @FastNative
    public static native String ping();

    public static native Surface setupNativeCapturer(int width, int height);

    public static native void releaseNativeCapturer();

    @FastNative
    public static native void setPreviewSurface(Object surface);

    /**
     * 测试用
     */
    public static native Bitmap getFrameBufferBitmap();

    @FastNative
    public static native long getFrameCount();

    /**
     * 把最新一帧 BGR 数据拷进 {@code dst}，元数据写入 {@code outMeta}。
     *
     * <p>供跑在 App 进程的 Kotlin 引擎（边狱）取帧 —— 它不像 MaaCore 那样在提权进程内
     * 直接访问帧缓冲。走直接缓冲区而非返回 byte[]，避免每帧在 JVM 堆分配约 2.6 MB
     * 造成 GC 抖动。
     *
     * @param dst     必须是 direct ByteBuffer，容量不小于 width*height*3
     * @param outMeta 长度 >= 4 的数组，回填 [width, height, stride, frameCount]
     * @return 实际拷贝字节数；无可用帧或容量不足返回 0（不做截断，截断帧会让识别得到错误结果）
     */
    public static native int copyLatestFrame(java.nio.ByteBuffer dst, long[] outMeta);

}
