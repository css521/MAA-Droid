package com.aliothmoon.maadroid.engine.limbus.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 任务面板与参数 JSON 之间的读写。
 *
 * 参数 JSON 的形状是**分节表**（`{"exp": {...}, "other_task": {...}}`），与
 * `LimbusEngine.appendTask` 的约定一致。面板不该手搓 JSON —— 宿主会把这串字符串
 * 原样存储、原样回传，写错一个层级不会有任何编译期报错，只会让引擎读到默认值，
 * 表现为「设置了但没生效」。
 *
 * 取不到就返回默认值：用户第一次打开面板时参数是空串，面板必须能正常渲染。
 */
class TaskParams private constructor(private val sections: Map<String, JsonObject>) {

    fun str(section: String, key: String, default: String): String =
        (sections[section]?.get(key) as? JsonPrimitive)?.contentOrNull ?: default

    fun bool(section: String, key: String, default: Boolean): Boolean =
        (sections[section]?.get(key) as? JsonPrimitive)?.booleanOrNull ?: default

    fun int(section: String, key: String, default: Int): Int =
        (sections[section]?.get(key) as? JsonPrimitive)
            ?.contentOrNull?.toDoubleOrNull()?.toInt() ?: default

    /** 改一个字段后序列化回分节表，其余分节与字段原样保留 */
    fun with(section: String, key: String, value: String): String =
        write(section) { it[key] = JsonPrimitive(value) }

    fun with(section: String, key: String, value: Boolean): String =
        write(section) { it[key] = JsonPrimitive(value) }

    fun with(section: String, key: String, value: Int): String =
        write(section) { it[key] = JsonPrimitive(value) }

    private fun write(section: String, mutate: (MutableMap<String, JsonPrimitive>) -> Unit): String {
        val existing = sections[section].orEmpty()
        val patch = LinkedHashMap<String, JsonPrimitive>()
        mutate(patch)
        val mergedSection = JsonObject(existing + patch)
        val merged = sections + (section to mergedSection)
        return json.encodeToString(JsonObject(merged))
    }

    private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> =
        this ?: emptyMap()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(paramsJson: String): TaskParams {
            if (paramsJson.isBlank()) return TaskParams(emptyMap())
            val root = runCatching { json.parseToJsonElement(paramsJson) as? JsonObject }
                .getOrNull() ?: return TaskParams(emptyMap())
            val loaded = LinkedHashMap<String, JsonObject>()
            for ((k, v) in root) (v as? JsonObject)?.let { loaded[k] = it }
            return TaskParams(loaded)
        }
    }
}
