package com.aliothmoon.maadroid.engine

/**
 * 引擎事件。宿主据此更新 UI、写日志、发通知。
 *
 * 事件面对齐现有 MaaCore 回调（`AsstMsg`）里宿主真正用到的那一层语义，
 * 而不是照搬 MaaCore 的消息号 —— 否则边狱引擎得伪造方舟的消息格式。
 * 引擎特有的细节放进 [Raw.payload] 由该引擎自己的 UI 解释。
 */
sealed interface EngineEvent {

    /** 连接阶段状态变化 */
    data class Connection(val state: ConnectionState, val detail: String? = null) : EngineEvent

    /** 一条面向用户的日志。[level] 决定 UI 着色与是否计入错误统计 */
    data class Log(val level: LogLevel, val message: String) : EngineEvent

    /** 任务链推进：某个任务开始 / 完成 / 失败 */
    data class Task(
        val taskId: Int,
        val type: String,
        val phase: TaskPhase,
        val message: String? = null,
    ) : EngineEvent

    /** 全部任务结束。[success] 为 false 表示中途失败或被中止 */
    data class AllTasksFinished(val success: Boolean) : EngineEvent

    /** 引擎内部错误，通常意味着需要重连或重载资源 */
    data class Failure(val reason: String, val cause: Throwable? = null) : EngineEvent

    /**
     * 引擎特有事件的透传通道。
     *
     * 有意保留：方舟有公招识别结果、基建换班详情、掉落统计等大量结构化回调，
     * 边狱有镜牢寻路、饰品选择等 —— 把它们塞进统一枚举会让契约随引擎无限膨胀。
     * 由各引擎的 [EngineUi] 自己解释 [payload]（JSON）。
     */
    data class Raw(val kind: String, val payload: String) : EngineEvent
}

enum class ConnectionState { Connecting, Connected, Disconnected, Failed }

enum class LogLevel { Trace, Debug, Info, Warn, Error }

enum class TaskPhase { Started, Completed, Failed, Stopped }
