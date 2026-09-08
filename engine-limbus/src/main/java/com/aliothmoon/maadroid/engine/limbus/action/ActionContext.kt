package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.InputSink
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineNode
import com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer
import com.aliothmoon.maadroid.engine.limbus.recognize.TemplateIndex
import kotlinx.serialization.json.JsonElement

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

    /**
     * 本次执行的节点名。
     *
     * [PipelineNode] 自身不存名字 —— 名字是注册表的键（照抄上游 task_registry 的形状），
     * 而 check_out_update 之类的动作需要按名字记账，故由上下文补上。
     */
    val nodeName: String

    val input: InputSink

    val recognize: Recognizer

    /** 素材索引，镜牢的饰品体系展开靠它（见 [TemplateIndex]） */
    val templates: TemplateIndex

    /** 任务配置（队伍轮换、饰品黑白名单、主题卡包权重等），键沿用上游的 *_cfg 命名 */
    val config: LimbusConfig

    /** 供动作写面向用户的日志；宿主会转成 EngineEvent.Log */
    fun log(message: String)

    /** 协作式中断点：长动作应在循环里检查，用户点停止后及时退出 */
    fun ensureActive()

    /** 睡眠，会响应中断 */
    suspend fun delay(seconds: Double)

    /**
     * 读取 check 节点的执行计数。
     *
     * 上游把它写在节点的 `params.execute_count` 里并**原地自增**
     * （`check_out_update` 动作），队伍轮换靠它取模：
     * `cfg_index = get_task("${cfg_type}_check").execute_count % team_count`。
     * 这里 [PipelineNode] 是不可变数据类（要能被资源包整体替换），故计数外置到上下文。
     */
    fun counterOf(nodeName: String): Int

    /** 自增并返回新值，对应上游 check_out_update */
    fun incrementCounter(nodeName: String): Int
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

    // ---- 「每套队伍一组」的二维配置 ----
    //
    // 上游许多键是按队伍分组的，取用时一律写成 `cfg["<key>"][cfg_index]`
    // （team_orders / team_indexes / mirror_team_stars / mirror_team_styles /
    //  mirror_team_ego_gift_styles / mirror_team_ego_allow_list / mirror_shop_heal …）。
    // 下标由 `<cfg_type>_check` 节点的计数取模得到，见 resolveCfgIndex。
    // 越界一律回落到默认值而不是抛异常 —— 用户少配一组不该让整条链路崩掉。

    fun listAt(section: String, key: String, index: Int): List<String>
    fun intAt(section: String, key: String, index: Int, default: Int): Int
    fun boolAt(section: String, key: String, index: Int, default: Boolean): Boolean
    fun strAt(section: String, key: String, index: Int, default: String): String

    /** [key] 下有多少组，供 [ActionContext.counterOf] 取模做轮换 */
    fun groupCount(section: String, key: String): Int

    /**
     * 取原始 JSON。
     *
     * 逃生口，给形状不规则的键用 —— 例如 `mirror_replace_skill` 每组是
     * 「罪人名 → 技能顺序」的字典（`{"Faust": [3,2,1], …}`），既不是标量也不是字符串表。
     * 上游这类结构会随版本增减，为它们各加一个专用取值方法只会让契约随上游膨胀。
     */
    fun rawAt(section: String, key: String, index: Int): JsonElement?
}
