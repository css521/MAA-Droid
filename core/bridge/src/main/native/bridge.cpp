#include "bridge_capture.h"
#include "bridge_frame_buffer.h"
#include "bridge_input.h"
#include "bridge_internal.h"
#include "bridge_preview.h"

static jstring ping(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return env->NewStringUTF("LibBridge");
}

static jobject nativeGetFrameBufferBitmap(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return CreateFrameBufferBitmap(env);
}

static void nativeSetPreviewSurface(JNIEnv *env, jclass clazz, jobject jSurface) {
    (void) clazz;
    SetPreviewSurface(env, jSurface);
}

static jobject nativeSetupNativeCapturer(JNIEnv *env, jclass clazz, jint width, jint height) {
    (void) clazz;
    return SetupNativeCapturer(env, width, height);
}

static void nativeReleaseNativeCapturer(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    ReleaseNativeCapturer();
}

static jlong nativeGetFrameCount(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return static_cast<jlong>(GetFrameCount());
}

/**
 * 把最新帧拷进 Java 侧的直接缓冲区，元数据经 long[] 回传。
 *
 * 供跑在 App 进程的 Kotlin 引擎取帧。走 direct buffer 而非返回 byte[]，
 * 是为了避免每帧在 JVM 堆上分配 2.6 MB 造成 GC 抖动。
 */
static jint nativeCopyLatestFrame(JNIEnv *env, jclass clazz, jobject dstBuffer, jlongArray outMeta) {
    (void) clazz;
    if (!dstBuffer || !outMeta) return 0;
    void *dst = env->GetDirectBufferAddress(dstBuffer);
    const jlong capacity = env->GetDirectBufferCapacity(dstBuffer);
    if (!dst || capacity <= 0) return 0;
    if (env->GetArrayLength(outMeta) < 4) return 0;

    int64_t meta[4] = {0, 0, 0, 0};
    const size_t copied = CopyLatestFrameBgr(dst, static_cast<size_t>(capacity), meta);
    if (copied == 0) return 0;

    jlong javaMeta[4] = {meta[0], meta[1], meta[2], meta[3]};
    env->SetLongArrayRegion(outMeta, 0, 4, javaMeta);
    return static_cast<jint>(copied);
}

static JNINativeMethod gMethods[] = {
        {"ping",                  "()Ljava/lang/String;",        reinterpret_cast<void *>(ping)},
        {"setupNativeCapturer",   "(II)Landroid/view/Surface;",  reinterpret_cast<void *>(nativeSetupNativeCapturer)},
        {"releaseNativeCapturer", "()V",                         reinterpret_cast<void *>(nativeReleaseNativeCapturer)},
        {"setPreviewSurface",     "(Ljava/lang/Object;)V",       reinterpret_cast<void *>(nativeSetPreviewSurface)},
        {"getFrameBufferBitmap",  "()Landroid/graphics/Bitmap;", reinterpret_cast<void *>(nativeGetFrameBufferBitmap)},
        {"getFrameCount",         "()J",                         reinterpret_cast<void *>(nativeGetFrameCount)},
        {"copyLatestFrame",       "(Ljava/nio/ByteBuffer;[J)I",  reinterpret_cast<void *>(nativeCopyLatestFrame)},
};

static constexpr char kNativeBridgeClass[] = "com/aliothmoon/maadroid/bridge/NativeBridgeLib";
static constexpr char kDriverClass[] = "com/aliothmoon/maadroid/maa/DriverClass";

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;

    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return JNI_ERR;
    }

    jclass nativeLibClass = env->FindClass(kNativeBridgeClass);
    if (!nativeLibClass) {
        CheckJNIException(env, "FindClass(NativeBridgeLib)");
        return JNI_ERR;
    }

    if (env->RegisterNatives(
            nativeLibClass, gMethods,
            static_cast<jint>(sizeof(gMethods) / sizeof(gMethods[0]))) < 0) {
        CheckJNIException(env, "RegisterNatives(NativeBridgeLib)");
        env->DeleteLocalRef(nativeLibClass);
        return JNI_ERR;
    }
    env->DeleteLocalRef(nativeLibClass);

    if (!InitInputBridge(vm, env, kDriverClass)) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void) reserved;

    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env) {
        ShutdownPreview(env);
        ReleaseInputBridge(env);
    }
}
