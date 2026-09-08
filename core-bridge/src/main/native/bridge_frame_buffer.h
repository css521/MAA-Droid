#ifndef BRIDGE_FRAME_BUFFER_H
#define BRIDGE_FRAME_BUFFER_H

#include "bridge_internal.h"

#include <android/hardware_buffer.h>

typedef enum {
    FRAME_STATE_FREE = 0,
    FRAME_STATE_WRITING = 2
} FrameBufferState;

#define FRAME_BUFFER_COUNT 3

typedef struct {
    uint8_t *bgr_data;
    size_t bgr_size;
    int64_t frame_count;
    int width;
    int height;
    int index;
} FrameBuffer;

void InitFrameBuffers(int width, int height);

void ReleaseFrameBuffers();

bool WriteHardwareBufferToFrame(AHardwareBuffer *buffer);

jobject CreateFrameBufferBitmap(JNIEnv *env);

int64_t GetFrameCount();

/**
 * 把最新一帧 BGR 数据拷进调用方提供的直接缓冲区。
 *
 * 供跑在 App 进程的 Kotlin 引擎（边狱）取帧：它无法像 MaaCore 那样在提权进程内直接
 * 访问帧缓冲，只能靠跨进程共享内存。与 CreateFrameBufferBitmap 一样只在持锁期间做
 * 一次 memcpy，不做格式转换 —— BGR 对 OpenCV 是原生通道序。
 *
 * @param dst      目标缓冲区（须为 direct ByteBuffer 的地址）
 * @param capacity 目标容量（字节）
 * @param outMeta  输出 [width, height, stride, frameCount]，长度须 >= 4
 * @return 实际拷贝字节数；容量不足或无可用帧返回 0
 */
size_t CopyLatestFrameBgr(void *dst, size_t capacity, int64_t *outMeta);

#endif // BRIDGE_FRAME_BUFFER_H
