package com.aliothmoon.maadroid.engine.limbus.config

import com.aliothmoon.maadroid.engine.limbus.action.LimbusConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * 由 JSON 承载的边狱任务配置。
 *
 * 分节名沿用上游的 `*_cfg` 去掉后缀：`exp` / `thread` / `mirror` / `other_task` /
 * `theme_pack`，对应上游 `config/<section>_cfg.json`。
 *
 * **这些是用户配置，不是资源包内容**：资源包只带 `config/task` 与 `config/language`
 * （见 pack_engine_resource.py），任务配置由本 App 的设置界面产出并持久化。
 * 混在一起会让热更覆盖掉用户的队伍与饰品设置。
 *
 * 取值一律「取不到就回落默认」而不抛异常：上游加一个键、用户少配一组，
 * 都不该让整条任务链在半路崩掉。
 */
class JsonLimbusConfig(private val sections: Map<String, JsonObject>) : LimbusConfig {

    private fun valueOf(section: String, key: String): JsonElement? =
        sections[section]?.get(key)

    private fun primitive(section: String, key: String): JsonPrimitive? =
        valueOf(section, key) as? JsonPrimitive

    override fun int(section: String, key: String, default: Int): Int =
        primitive(section, key)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: default

    override fun bool(section: String, key: String, default: Boolean): Boolean =
        primitive(section, key)?.booleanOrNull ?: default

    override fun str(section: String, key: String, default: String): String =
        primitive(section, key)?.contentOrNull ?: default

    override fun list(section: String, key: String): List<String> =
        (valueOf(section, key) as? JsonArray)?.toStringList() ?: emptyList()

    // ---- 分组取值：上游写作 cfg["<key>"][cfg_index] ----

    override fun rawAt(section: String, key: String, index: Int): JsonElement? =
        (valueOf(section, key) as? JsonArray)?.getOrNull(index)

    override fun listAt(section: String, key: String, index: Int): List<String> =
        (rawAt(section, key, index) as? JsonArray)?.toStringList() ?: emptyList()

    override fun intAt(section: String, key: String, index: Int, default: Int): Int =
        (rawAt(section, key, index) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
            ?: default

    override fun boolAt(section: String, key: String, index: Int, default: Boolean): Boolean =
        (rawAt(section, key, index) as? JsonPrimitive)?.booleanOrNull ?: default

    override fun strAt(section: String, key: String, index: Int, default: String): String =
        (rawAt(section, key, index) as? JsonPrimitive)?.contentOrNull ?: default

    override fun groupCount(section: String, key: String): Int =
        (valueOf(section, key) as? JsonArray)?.size ?: 0

    /**
     * 用 `content` 而不是 `contentOrNull`：上游 `mirror_team_initial_ego_orders`
     * 是数字数组 `[1,2,3]`，`mirror_team_stars` 是字符串数组 `["0","6"]`，
     * 两者都要能按字符串表读出来。
     */
    private fun JsonArray.toStringList(): List<String> =
        mapNotNull { (it as? JsonPrimitive)?.content }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 上游的五份任务配置 */
        val SECTIONS = listOf("exp", "thread", "mirror", "other_task", "theme_pack")

        /**
         * 从目录加载 `<section>_cfg.json`。缺失的分节留空，由各动作取默认值 ——
         * 首次使用还没配过任何东西时，引擎也应当能起来并报「请先配置」而不是崩。
         */
        fun fromDirectory(dir: File): JsonLimbusConfig {
            val loaded = LinkedHashMap<String, JsonObject>()
            for (section in SECTIONS) {
                val f = File(dir, "${section}_cfg.json")
                if (!f.isFile) continue
                val obj = runCatching { json.parseToJsonElement(f.readText()) as? JsonObject }
                    .getOrNull() ?: continue
                loaded[section] = obj
            }
            return JsonLimbusConfig(loaded)
        }

        fun fromJsonStrings(raw: Map<String, String>): JsonLimbusConfig {
            val loaded = LinkedHashMap<String, JsonObject>()
            for ((section, text) in raw) {
                val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }
                    .getOrNull() ?: continue
                loaded[section] = obj
            }
            return JsonLimbusConfig(loaded)
        }

        fun empty() = JsonLimbusConfig(emptyMap())
    }
}
