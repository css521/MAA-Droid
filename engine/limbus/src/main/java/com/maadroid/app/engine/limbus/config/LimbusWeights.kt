package com.maadroid.app.engine.limbus.config

import com.maadroid.app.engine.limbus.action.LimbusConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 读取 LALC 原始嵌套结构，同时接受旧 Android 草稿的扁平键。 */
internal object LimbusWeights {
    fun nodes(config: LimbusConfig, defaults: Map<String, Int>): Map<String, Int> {
        val scores = config.section("mirror")["node_scores"] as? JsonObject
        return defaults.mapValues { (key, value) ->
            (scores?.get(key) as? JsonPrimitive)?.intOrNull ?: config.int("mirror", "node_score_$key", value)
        } + ("node_empty" to -100)
    }
    fun packs(config: LimbusConfig): Map<String, Int> {
        val weights = config.section("theme_pack").mapNotNull { (name, data) ->
            ((data as? JsonObject)?.get("weight") as? JsonPrimitive)?.intOrNull?.let { name to it }
        }.toMap().ifEmpty {
            config.list("theme_pack", "names").associateWith { config.int("theme_pack", "weight_$it", 0) }
        }
        return weights.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }
}
