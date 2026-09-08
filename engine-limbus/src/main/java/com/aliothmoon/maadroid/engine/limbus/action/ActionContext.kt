package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.InputSink
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineNode
import com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer

/**
 * 动作执行上下文。
 *
 * 对应上游 LALC 里动作函数拿到的三样东西：当前节点（含 params）、`input_handler`、
 * `recognize_handler`。上游还有一个 `shared_params` 装各任务配置，这里对应 [config]。
 *
 * 有意做成接口：动作实现因此可在纯 JVM 单测里跑（喂假的识别器与输入），
 * 不必起设备 —— 35 个动作若只能真机验证，移植根本无法收敛。
 */
interface ActionContext {

    /** 触发本次执行的节点，动作从 [PipelineNode.params] 取自己的参数 */
    val node: PipelineNode

    val input: InputSink

    val recognize: Recognizer

    /** 任务配置（队伍轮换、饰品黑白名单、主题卡包权重等），键沿用上游的 *_cfg 命名 */
    val config: LimbusConfig

    /** 供动作写面向用户的日志；宿主会转成 EngineEvent.Log */
    fun log(message: String)

    /** 协作式中断点：长动作应在循环里检查，用户点停止后及时退出 */
    fun ensureActive()

    /** 睡眠，会响应中断 */
    suspend fun delay(seconds: Double)
}

/**
 * 边狱任务配置。
 *
 * 结构对齐上游的 config 目录下 JSON（exp_cfg / thread_cfg / mirror_cfg / other_task_cfg /
 * theme_pack_cfg），但只在这里声明访问方式 —— 具体字段随上游演进，故以 JSON 承载，
 * 由各动作按需读取，避免上游加一个开关就要改契约。
 */
interface LimbusConfig {
    fun int(section: String, key: String, default: Int): Int
    fun bool(section: String, key: String, default: Boolean): Boolean
    fun str(section: String, key: String, default: String): String
    fun list(section: String, key: String): List<String>
}
