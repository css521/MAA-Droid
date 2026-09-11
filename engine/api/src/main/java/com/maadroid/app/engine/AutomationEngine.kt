package com.maadroid.app.engine

import kotlinx.coroutines.flow.SharedFlow

/**
 * 自动化引擎的生命周期契约，宿主只通过它驱动任务。
 *
 * **两种执行位置都必须支持**，这是本契约最关键的约束：
 * - 方舟：MaaCore 是 native，只能跑在提权进程；其实现是 AIDL 代理（对应现有
 *   `MaaCoreService`），本地方法调用转成跨进程调用。
 * - 边狱：移植自 LALC 的 Kotlin 流水线 + OpenCV/ONNX，跑在 App 进程；靠
 *   [FrameSource] / [InputSink] / [DeviceControl] 向提权进程要帧与输入。
 *
 * 因此接口刻意只描述「做什么」，不假设实现在哪个进程。
 */
interface AutomationEngine {

    val profile: GameProfile

    /** 引擎事件流。宿主据此更新 UI、写日志、发通知，无需知道引擎内部结构 */
    val events: SharedFlow<EngineEvent>

    val isRunning: Boolean

    /** Optional host-owned diagnostics. Do not send task configuration, credentials or frames. */
    fun setDiagnosticSink(sink: EngineDiagnosticSink?) {}

    /**
     * 装载资源（模板图 / 流水线 / 模型）。按 [ResourcePackSpec] 从集合取得各包路径。
     * [EngineResources] 只表达路径；资源内容与兼容性校验由装载流程负责。
     */
    suspend fun prepare(resources: EngineResources): Result<Unit>

    /** 连接到设备。方舟走 MaaCore 的 AsyncConnect，边狱则是打开帧通道与输入通道 */
    suspend fun connect(device: DeviceHandle): Result<Unit>

    /**
     * 追加一个任务。[type] 是引擎自定义的任务标识（方舟如 "Fight"，边狱如 "mirror"），
     * [paramsJson] 由该引擎的任务面板产出，宿主不解释其内容。
     * @return 任务 id，失败返回 [INVALID_TASK_ID]
     */
    fun appendTask(type: String, paramsJson: String): Int

    fun setTaskParams(taskId: Int, paramsJson: String): Boolean

    suspend fun start(): Boolean

    suspend fun stop(): Boolean

    /** 在 stop 完成后释放本地模型、模板缓存等会话资源。 */
    fun release() {}

    companion object {
        const val INVALID_TASK_ID = 0
    }
}

/**
 * 设备句柄。引擎需要的设备侧能力打包在此，由 core-remote 基于 `RemoteService` AIDL 实现。
 *
 * 跑在提权进程的引擎（方舟）实际不用这些 —— 它在进程内直接访问 native 桥；
 * 跑在 App 进程的引擎（边狱）则全靠它跨进程取帧和注入。
 */
interface DeviceHandle {
    val frames: FrameSource
    val input: InputSink
    val control: DeviceControl
}
