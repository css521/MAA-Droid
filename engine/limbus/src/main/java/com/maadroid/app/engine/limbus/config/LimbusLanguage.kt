package com.maadroid.app.engine.limbus.config

import java.io.File
import kotlinx.serialization.json.*

/** 上游把所选语言目录内的 JSON 合并成一个词表；英文无词表时原样使用资源标识。 */
internal object LimbusLanguage {
    fun load(resourceDir: File, language: String): JsonObject {
        require(language in setOf("en", "zh")) { "不支持的游戏语言: $language" }
        val dir = File(resourceDir, "config/language/$language")
        if (language == "en" && !dir.exists()) return JsonObject(emptyMap())
        require(dir.isDirectory) { "缺少游戏语言表: $language" }
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.toList()
        require(files.isNotEmpty()) { "游戏语言表为空: $language" }
        val entries = linkedMapOf<String, JsonElement>()
        for (file in files) {
            val data = Json.parseToJsonElement(file.readText()).jsonObject
            for ((key, value) in data) {
                require(value is JsonPrimitive && value.isString) { "无效语言条目: ${file.name}/$key" }
                entries[key] = value
            }
        }
        return JsonObject(entries)
    }
}
