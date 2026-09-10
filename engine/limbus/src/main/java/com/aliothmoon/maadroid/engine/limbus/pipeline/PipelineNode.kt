package com.aliothmoon.maadroid.engine.limbus.pipeline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 流水线节点，与上游 LALC 的 `config/task` 下的 JSON 一一对应。
 *
 * 字段名与语义严格照抄上游（`workflow/task_node.py`），这样上游改流水线时
 * 我们的资源包能无感跟随、不需要转换层。当前上游 v5.0.0 共 133 个节点。
 */
@Serializable
data class PipelineNode(
    /** 节点类型：normal 负责路由，basic 是执行终点，check 是计数检查点 */
    val type: String = TYPE_NORMAL,

    /**
     * 动作名。上游要求它必须同时是一个已注册节点名（见 `replace_action_with_task_node`），
     * 其中 35 个有实现体、10 个是纯路由。
     */
    val action: String = "empty",

    /** 识别方式。上游 JSON 里实际只用到 direct(56) 与 template_match(77) */
    val recognition: String = RECOGNITION_DIRECT,

    /** 取反：识别不中才算命中，用于「不在主界面就先返回主界面」这类判断 */
    val inverse: Boolean = false,

    /** 本节点单次路由的最小耗时（秒），用于限速避免空转 */
    @SerialName("rate_limit")
    val rateLimit: Double = 1.0,

    val enable: Boolean = true,

    /** 上游用 desc 写注释，保留以便对照 */
    val desc: String? = null,

    /** 动作与识别参数。结构因 action 而异，故保持为原始 JSON */
    val params: JsonObject = JsonObject(emptyMap()),

    /** 后继候选，按顺序取第一个识别命中的 */
    val next: List<String> = emptyList(),

    /**
     * 中断候选。next 全不命中时才尝试，命中后执行完会回到本节点继续路由。
     * 上游默认值是 ["error_handler"]（见 `create_task_node`），仅 error.json
     * 里的节点被清空以避免自我递归 —— 这个默认必须复刻，否则异常处理会失效。
     */
    val interrupt: List<String>? = null,
) {
    val isCheckNode: Boolean get() = type == TYPE_CHECK

    /** 取字符串参数 */
    fun str(key: String): String? =
        (params[key] as? JsonPrimitive)?.contentOrNull

    /** 取数值参数 */
    fun num(key: String): Double? =
        (params[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    /** 取坐标参数，形如 [x, y] 或 [x, y, w, h] */
    fun ints(key: String): List<Int>? =
        params[key]?.let { el ->
            runCatching { el.jsonArray.map { it.jsonPrimitive.content.toDouble().toInt() } }.getOrNull()
        }

    /**
     * 该节点识别所用的模板名。可能是单个字符串或字符串数组。
     * 打包器据此校验模板存在性（当前上游 50 个引用全部命中）。
     */
    fun templates(): List<String> = when (val t = params["template"]) {
        null -> emptyList()
        is JsonPrimitive -> listOfNotNull(t.contentOrNull)
        else -> runCatching { t.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }
            .getOrDefault(emptyList())
    }

    /** Only validate fields whose semantics this runtime actually consumes. */
    internal fun compatibilityError(): String? {
        if (type !in SUPPORTED_TYPES) {
            return "不支持的节点 type '$type'，请升级 App 后重新加载资源包"
        }
        if (recognition !in SUPPORTED_RECOGNITIONS) {
            return "不支持的 recognition '$recognition'，请升级 App 后重新加载资源包"
        }
        if (recognition == RECOGNITION_DIRECT) return null

        // TaskNode.get_param requires a template for all three matching modes. Empty or
        // malformed templates must not become a MISS that inverse can turn into success.
        val template = params["template"]
        val validTemplates = when (template) {
            is JsonPrimitive -> template.isString && template.content.isNotBlank()
            is JsonArray -> template.isNotEmpty() && template.all {
                it is JsonPrimitive && it.isString && it.content.isNotBlank()
            }
            else -> false
        }
        if (!validTemplates) return "recognition '$recognition' 的 params.template 必须为非空字符串或非空字符串数组"

        if ("threshold" in params) {
            val threshold = num("threshold")
            if (threshold == null || !threshold.isFinite()) {
                return "params.threshold 必须为有限数值，不能回退到默认阈值"
            }
            // AdvancedTemplateMatcher rejects this range itself; reject before its failure
            // could be translated into empty matches. Grayscale keeps its existing range.
            if (recognition != RECOGNITION_TEMPLATE_MATCH && threshold !in 0.0..1.0) {
                return "recognition '$recognition' 的 params.threshold 必须在 [0,1] 内"
            }
        }
        if ("mask" in params) {
            val mask = params["mask"] as? JsonArray
            val coordinates = mask?.map { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
            if (coordinates == null || coordinates.size != 4 || coordinates.any {
                    it == null || !it.isFinite() || it % 1.0 != 0.0 ||
                        it < Int.MIN_VALUE.toDouble() || it > Int.MAX_VALUE.toDouble()
                }) {
                return "params.mask 必须为四个整数 [x,y,width,height]，不能回退到全屏识别"
            }
            if (coordinates[2]!! <= 0 || coordinates[3]!! <= 0) {
                return "params.mask 的 width 和 height 必须大于 0"
            }
        }
        return null
    }

    companion object {
        const val TYPE_NORMAL = "normal"
        const val TYPE_BASIC = "basic"
        const val TYPE_CHECK = "check"

        val SUPPORTED_TYPES = setOf(TYPE_NORMAL, TYPE_BASIC, TYPE_CHECK)

        const val RECOGNITION_DIRECT = "direct"
        const val RECOGNITION_TEMPLATE_MATCH = "template_match"
        const val RECOGNITION_COLOR_TEMPLATE_MATCH = "color_template_match"
        const val RECOGNITION_FEATURE_MATCH = "feature_match"

        // Recognizer has other methods for actions; TaskNode.do_recognize and NodeRecognizer
        // expose only these four modes to pipeline JSON.
        val SUPPORTED_RECOGNITIONS = setOf(
            RECOGNITION_DIRECT, RECOGNITION_TEMPLATE_MATCH,
            RECOGNITION_COLOR_TEMPLATE_MATCH, RECOGNITION_FEATURE_MATCH,
        )

        /** 上游 `create_task_node` 里 interrupt 的缺省值 */
        val DEFAULT_INTERRUPT = listOf("error_handler")
    }
}
