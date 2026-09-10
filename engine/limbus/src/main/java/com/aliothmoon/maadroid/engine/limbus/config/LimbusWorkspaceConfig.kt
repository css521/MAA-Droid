package com.aliothmoon.maadroid.engine.limbus.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** 沿用 LALC 前端配置结构；转换规则对应 upstream server.py 的配置转换函数。 */
@Serializable
data class LimbusWorkspaceConfig(
    val schemaVersion: Int = 1,
    val taskConfigs: Map<String, LimbusTaskConfig> = defaultTasks(),
    val teamConfigs: Map<String, LimbusTeamConfig> = (0 until 20).associate { "$it" to LimbusTeamConfig(teamName = "Team ${it + 1}") },
    val themePackWeights: Map<String, Int> = emptyMap(),
    val language: String = "en",
) {
    fun task(key: String) = taskConfigs[key] ?: defaultTasks().getValue(key)
    fun team(index: Int) = teamConfigs["$index"] ?: LimbusTeamConfig(teamName = "Team ${index + 1}")
    fun withTask(key: String, value: LimbusTaskConfig) = copy(taskConfigs = taskConfigs + (key to value))
    fun withTeam(index: Int, value: LimbusTeamConfig) = copy(teamConfigs = teamConfigs + ("$index" to value))
    fun encode(): String = json.encodeToString(this)

    fun validationError(): String? {
        if (schemaVersion != 1) return "此配置需要更新版本的 MAA-Droid"
        if (language !in setOf("en", "zh")) return "请选择支持的游戏语言"
        for (key in listOf("EXP", "Thread", "Mirror")) {
            val task = task(key)
            if (!task.enabled) continue
            if (task.count !in 1..999) return "$key 的执行次数应为 1–999"
            if (key != "Mirror") {
                // Missing legacy fields use the same defaults as backendSections; explicit
                // null/non-primitive values must not silently fall back to a runnable task.
                val modeValue = task.params["luxcavationMode"]
                val mode = if (modeValue == null) "Enter" else (modeValue as? JsonPrimitive)?.contentOrNull
                if (mode !in setOf("Enter", "Skip Battle")) return "$key 的战斗模式应为 Enter 或 Skip Battle"
                val stageKey = if (key == "EXP") "expStage" else "threadStage"
                val stageValue = task.params[stageKey]
                val stage = if (stageValue == null) {
                    if (key == "EXP") "09" else "60"
                } else (stageValue as? JsonPrimitive)?.contentOrNull
                // Do not parse as Int: EXP uses leading zeroes and upstream may add levels.
                if (stage.isNullOrEmpty() || stage.any { it !in '0'..'9' }) return "$key 的关卡应为非空数字（0–9）"
                if (mode == "Skip Battle") continue // Only battle-team validation is skipped.
            }
            if (task.teams.isEmpty()) return "请为 $key 选择队伍，并在队伍页配置出战顺序"
            if (task.teams.distinct().size != task.teams.size) return "$key 的队伍重复"
            for (slot in task.teams) {
                if (slot !in 1..20) return "队伍槽位应为 1–20"
                val t = team(slot - 1)
                if (t.selectedMembers.isEmpty()) return "请配置 ${t.teamName} 的出战顺序"
                if (t.selectedMembers.distinct().size != t.selectedMembers.size || t.selectedMembers.any { it !in SINNERS }) return "${t.teamName} 的罪人名单无效"
                if (key == "Mirror" && t.selectedPreferEgoGiftTypes.isEmpty()) return "请为 ${t.teamName} 选择饰品偏好流派"
            }
        }
        if (task("Daily Lunacy Purchase").count !in 0..10) return "每日狂气兑换次数应为 0–10"
        return null
    }

    /** 20 个独立队伍槽位转为任务选中顺序对应的平行数组，不能按槽位排序。 */
    fun backendSections(): JsonObject = buildJsonObject {
        for ((uiKey, section) in listOf("EXP" to "exp", "Thread" to "thread", "Mirror" to "mirror")) {
            val cfg = task(uiKey)
            val teams = cfg.teams.map { team(it - 1) }
            put(section, buildJsonObject {
                put("check_node_target_count", if (cfg.enabled) cfg.count else 0)
                put("team_indexes", JsonArray(cfg.teams.map(::JsonPrimitive)))
                put("team_orders", JsonArray(teams.map { strings(it.selectedMembers) }))
                if (section != "mirror") {
                    put("luxcavation_mode", cfg.string("luxcavationMode", "Enter").lowercase())
                    put("${section}_stage", cfg.string(if (section == "exp") "expStage" else "threadStage", if (section == "exp") "09" else "60"))
                } else {
                    put("mirror_stop_purchase_gift_money", cfg.number("stopPurchaseGiftMoney", 600))
                    put("mirror_mode", cfg.string("mirror_mode", "normal"))
                    for (key in listOf("accept_reward", "enable_fuse_ego_gifts", "enable_replace_skill_purchase_ego_gifts", "enable_enhance_ego_gifts")) put(key, cfg.flag(key, true))
                    put("node_scores", cfg.params["node_scores"] ?: defaultNodeScores())
                    put("mirror_team_styles", JsonArray(teams.map { JsonPrimitive(it.selectedTeamStyleType) }))
                    put("mirror_team_ego_gift_styles", JsonArray(teams.map { strings(it.selectedPreferEgoGiftTypes) }))
                    put("mirror_team_ego_allow_list", JsonArray(teams.map { t -> strings(t.giftName2Status.filterValues { it == "Allow List" }.keys.toList()) }))
                    put("mirror_team_ego_block_list", JsonArray(teams.map { t -> strings(t.giftName2Status.filterValues { it == "Block List" }.keys.toList()) }))
                    put("mirror_shop_heal", JsonArray(teams.map { JsonPrimitive(it.shopHealAll) }))
                    put("mirror_team_initial_ego_orders", JsonArray(teams.map { JsonArray(it.initialEgoGifts.map(::JsonPrimitive)) }))
                    put("mirror_team_stars", JsonArray(teams.map { t -> strings((0..9).filter { t.mirrorStarEnabled["$it"] == true }.map { t.mirrorStarValues["$it"] ?: "$it" }) }))
                    put("mirror_replace_skill", JsonArray(teams.map { t -> buildJsonObject {
                        for (sinner in SINNERS) if (t.skillReplacementEnabled[sinner] == true) {
                            val order = t.skillReplacementOrders[sinner] ?: DEFAULT_SKILLS
                            put(sinner, JsonArray(order.map { pair -> JsonPrimitive(when (pair) {
                                listOf(1, 2) -> 1
                                listOf(2, 3) -> 2
                                listOf(1, 3) -> 3
                                else -> error("无效技能替换组合: $pair")
                            }) }))
                        }
                    } }))
                }
            })
        }
        put("theme_pack", buildJsonObject {
            themePackWeights.forEach { (name, weight) -> put(name, buildJsonObject { put("weight", weight) }) }
        })
        put("other_task", buildJsonObject {
            val purchase = task("Daily Lunacy Purchase")
            put("lunary_purchase_target", if (purchase.enabled) purchase.count else 0)
            put("ego_enable", task("E.G.O").enabled)
            put("language", language)
            put("test_mode", false)
            // 桌面关机/退出进程在 Android 上对应可选的结束游戏。
            put("close_game", task("At Last").enabled && task("At Last").string("action", "nothing") == "close_game")
        })
    }

    fun selectedTasks(): List<Pair<String, String>> {
        val sections = backendSections().toString()
        return listOf("Mail" to "mail", "EXP" to "exp", "Thread" to "thread", "Mirror" to "mirror", "Reward" to "reward")
            .filter { task(it.first).enabled }.map { it.second to sections }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
        fun decode(raw: String): LimbusWorkspaceConfig = if (raw.isBlank()) LimbusWorkspaceConfig() else json.decodeFromString(raw)
        val SINNERS = listOf("Yi Sang", "Faust", "Don Quixote", "Ryoshu", "Meursault", "Hong Lu", "Heathcliff", "Ishmael", "Rodion", "Sinclair", "Outis", "Gregor")
        val STYLES = listOf("Bleed", "Burn", "Rupture", "Poise", "Tremor", "Blunt", "Pierce", "Slash", "Charge", "Sinking", "Keywordless")
        val DEFAULT_SKILLS = listOf(listOf(1, 3), listOf(2, 3), listOf(1, 2))
        fun defaultNodeScores() = buildJsonObject {
            listOf("event" to 20, "regular_encounter" to 9, "elite_encounter" to 1, "focused_encounter" to 0, "abnormality_encounter" to 0, "shop" to 0, "boss_encounter" to 0)
                .forEach { (k, v) -> put("node_$k", v) }
        }
        fun defaultTasks(): Map<String, LimbusTaskConfig> = linkedMapOf(
            "Daily Lunacy Purchase" to LimbusTaskConfig(enabled = false, count = 0),
            "Mail" to LimbusTaskConfig(), "E.G.O" to LimbusTaskConfig(enabled = false),
            "EXP" to LimbusTaskConfig(params = buildJsonObject { put("luxcavationMode", "Enter"); put("expStage", "09") }),
            "Thread" to LimbusTaskConfig(params = buildJsonObject { put("luxcavationMode", "Enter"); put("threadStage", "60") }),
            "Mirror" to LimbusTaskConfig(params = buildJsonObject {
                put("stopPurchaseGiftMoney", 600); put("mirror_mode", "normal"); put("node_scores", defaultNodeScores())
            }),
            "Reward" to LimbusTaskConfig(), "At Last" to LimbusTaskConfig(enabled = false),
        )

        /** 旧五开关页面保留勾选及已填关卡，不能升级后默默重置。 */
        fun migrate(enabled: Map<String, Boolean>, params: Map<String, String>): LimbusWorkspaceConfig {
            var result = LimbusWorkspaceConfig()
            for ((key, type) in listOf("Mail" to "mail", "EXP" to "exp", "Thread" to "thread", "Mirror" to "mirror", "Reward" to "reward")) {
                val old = JsonLimbusConfig.sectionsOf(params[type].orEmpty())[type].orEmpty()
                var t = result.task(key).copy(enabled = enabled[type] ?: result.task(key).enabled)
                for ((from, to) in mapOf("exp_stage" to "expStage", "thread_stage" to "threadStage", "mirror_mode" to "mirror_mode", "accept_reward" to "accept_reward")) old[from]?.let { t = t.with(to, it) }
                old["luxcavation_mode"]?.let { mode ->
                    t = t.with("luxcavationMode", when ((mode as? JsonPrimitive)?.contentOrNull) {
                        "enter", "Enter" -> JsonPrimitive("Enter")
                        "skip battle", "Skip Battle" -> JsonPrimitive("Skip Battle")
                        else -> mode // Preserve invalid legacy values so validation can reject them.
                    })
                }
                result = result.withTask(key, t)
            }
            return result
        }
        private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    }
}

@Serializable
data class LimbusTaskConfig(
    val enabled: Boolean = true,
    val count: Int = 1,
    val params: JsonObject = JsonObject(emptyMap()),
    val teams: List<Int> = emptyList(),
) {
    fun string(key: String, default: String) = (params[key] as? JsonPrimitive)?.contentOrNull ?: default
    fun number(key: String, default: Int) = (params[key] as? JsonPrimitive)?.intOrNull ?: default
    fun flag(key: String, default: Boolean) = (params[key] as? JsonPrimitive)?.booleanOrNull ?: default
    fun with(key: String, value: JsonElement) = copy(params = JsonObject(params + (key to value)))
}

@Serializable
data class LimbusTeamConfig(
    val teamName: String = "",
    val selectedMembers: List<String> = emptyList(),
    val selectedTeamStyleType: String = "Bleed",
    val selectedPreferEgoGiftTypes: List<String> = listOf("Bleed"),
    val selectedAccessoryTypes: List<String> = emptyList(),
    val giftName2Status: Map<String, String> = emptyMap(),
    val shopHealAll: Boolean = false,
    val initialEgoGifts: List<Int> = listOf(1, 2, 3),
    val mirrorStarEnabled: Map<String, Boolean> = emptyMap(),
    val mirrorStarValues: Map<String, String> = emptyMap(),
    val skillReplacementEnabled: Map<String, Boolean> = emptyMap(),
    val skillReplacementOrders: Map<String, List<List<Int>>> = emptyMap(),
)
