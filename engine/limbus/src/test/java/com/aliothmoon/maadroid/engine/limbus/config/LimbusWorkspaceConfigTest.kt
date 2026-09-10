package com.aliothmoon.maadroid.engine.limbus.config

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class LimbusWorkspaceConfigTest {
    @Test fun teamSlotsAndSelectionOrderSurviveSaveAndReachBackend() {
        var cfg = LimbusWorkspaceConfig()
            .withTeam(5, LimbusTeamConfig(teamName = "破裂队", selectedMembers = listOf("Hong Lu", "Ryoshu"), selectedTeamStyleType = "Rupture"))
            .withTeam(1, LimbusTeamConfig(teamName = "流血队", selectedMembers = listOf("Faust", "Yi Sang")))
        cfg = cfg.withTask("Mirror", cfg.task("Mirror").copy(teams = listOf(6, 2), count = 3))
        val saved = LimbusWorkspaceConfig.decode(cfg.encode())
        val params = JsonLimbusConfig.fromSectionsJson(saved.backendSections().toString())
        assertEquals("破裂队", saved.team(5).teamName)
        assertEquals(listOf("6", "2"), params.list("mirror", "team_indexes"))
        assertEquals(listOf("Hong Lu", "Ryoshu"), params.listAt("mirror", "team_orders", 0))
        assertEquals("Bleed", params.strAt("mirror", "mirror_team_styles", 1, ""))
        assertEquals(3, params.int("mirror", "check_node_target_count", 0))
    }

    @Test fun giftListsStarsAndSkillPairsMatchUpstreamConversion() {
        val team = LimbusTeamConfig(selectedMembers = listOf("Faust"),
            giftName2Status = mapOf("Lithograph" to "Allow List", "Oracle" to "Block List"),
            mirrorStarEnabled = mapOf("0" to true, "7" to true, "1" to false),
            mirrorStarValues = mapOf("0" to "0+", "7" to "7++"),
            skillReplacementEnabled = mapOf("Faust" to true, "Yi Sang" to false),
            skillReplacementOrders = mapOf("Faust" to listOf(listOf(1, 3), listOf(1, 2), listOf(2, 3))),
        )
        val cfg = LimbusWorkspaceConfig().withTeam(0, team).let { it.withTask("Mirror", it.task("Mirror").copy(teams = listOf(1))) }
        val p = JsonLimbusConfig.fromSectionsJson(cfg.backendSections().toString())
        assertEquals(listOf("Lithograph"), p.listAt("mirror", "mirror_team_ego_allow_list", 0))
        assertEquals(listOf("Oracle"), p.listAt("mirror", "mirror_team_ego_block_list", 0))
        assertEquals(listOf("0+", "7++"), p.listAt("mirror", "mirror_team_stars", 0))
        assertEquals(Json.parseToJsonElement("{\"Faust\":[3,1,2]}"), p.rawAt("mirror", "mirror_replace_skill", 0))
    }

    @Test fun taskOnlyRunStillCarriesSharedEgoAndWeights() {
        var cfg = LimbusWorkspaceConfig(themePackWeights = mapOf("Hell's Chicken" to 80))
        for (key in listOf("Mail", "EXP", "Thread", "Reward")) cfg = cfg.withTask(key, cfg.task(key).copy(enabled = false))
        cfg = cfg.withTask("E.G.O", cfg.task("E.G.O").copy(enabled = true))
        val selected = cfg.selectedTasks()
        assertEquals(listOf("mirror"), selected.map { it.first })
        val params = JsonLimbusConfig.fromSectionsJson(selected.single().second)
        assertTrue(params.bool("other_task", "ego_enable", false))
        assertEquals(80, LimbusWeights.packs(params)["Hell's Chicken"])
        assertEquals(0, params.int("exp", "check_node_target_count", -1))
    }

    @Test fun nestedRouteScoresWinAndEmptyNodesStayDisabled() {
        val params = JsonLimbusConfig.fromSectionsJson("""{"mirror":{"node_scores":{"node_event":72,"node_empty":90}}}""")
        val scores = LimbusWeights.nodes(params, mapOf("node_event" to 20, "node_shop" to 0))
        assertEquals(72, scores["node_event"])
        assertEquals(-100, scores["node_empty"])
        assertEquals(0, scores["node_shop"])
    }

    @Test fun oldFiveSwitchSettingsMigrateWithoutLosingStageOrRewardChoice() {
        val cfg = LimbusWorkspaceConfig.migrate(mapOf("mail" to false), mapOf("exp" to """{"exp":{"exp_stage":"07","luxcavation_mode":"skip battle"}}""", "mirror" to """{"mirror":{"accept_reward":false}}"""))
        assertFalse(cfg.task("Mail").enabled)
        assertEquals("07", cfg.task("EXP").string("expStage", ""))
        assertEquals("Skip Battle", cfg.task("EXP").string("luxcavationMode", ""))
        assertFalse(cfg.task("Mirror").flag("accept_reward", true))
        assertFalse(cfg.task("Daily Lunacy Purchase").enabled)
    }

    @Test fun refuseUnconfiguredBattleTeamsBeforeStarting() {
        assertNotNull(LimbusWorkspaceConfig().validationError())
        var cfg = LimbusWorkspaceConfig()
        for (key in listOf("Thread", "Mirror")) cfg = cfg.withTask(key, cfg.task(key).copy(enabled = false))
        cfg = cfg.withTask("EXP", cfg.task("EXP").with("luxcavationMode", JsonPrimitive("Skip Battle")))
        assertNull(cfg.validationError())
    }

    @Test fun enabledLuxcavationsRejectUnknownAndMalformedModes() {
        val invalid = listOf<JsonElement>(JsonPrimitive(""), JsonPrimitive(" "), JsonPrimitive("skip"),
            JsonPrimitive("SkipBattle"), JsonPrimitive("unexpected"), JsonPrimitive(true), JsonPrimitive(1),
            JsonNull, JsonObject(emptyMap()), JsonArray(emptyList()))
        for (key in listOf("EXP", "Thread")) for (mode in invalid) {
            val cfg = luxcavation(key).let { it.withTask(key, it.task(key).with("luxcavationMode", mode)) }
            val error = cfg.validationError().orEmpty()
            assertTrue("$key mode=$mode: $error", key in error && "模式" in error)
        }
    }

    @Test fun stageValidationRunsForBothEnterAndSkipBattle() {
        val invalid = listOf<JsonElement>(JsonPrimitive(""), JsonPrimitive(" "), JsonPrimitive("\t"),
            JsonPrimitive("09 "), JsonPrimitive("-1"), JsonPrimitive("+1"), JsonPrimitive("1.5"),
            JsonPrimitive("1e2"), JsonPrimitive("abc"), JsonPrimitive("１２"), JsonPrimitive(false),
            JsonNull, JsonObject(emptyMap()), JsonArray(emptyList()))
        for (key in listOf("EXP", "Thread")) for (mode in listOf("Enter", "Skip Battle")) for (stage in invalid) {
            val cfg = luxcavation(key, mode).let { it.withTask(key, it.task(key).with(stageKey(key), stage)) }
            val error = cfg.validationError().orEmpty()
            assertTrue("$key mode=$mode stage=$stage: $error", key in error && "关卡" in error)
        }
    }

    @Test fun validStageStringsRoundTripUnchangedWithoutAnUpperBound() {
        for (key in listOf("EXP", "Thread")) for (mode in listOf("Enter", "Skip Battle")) {
            for (stage in listOf("0", "09", "60", "123456789012345678901234567890")) {
                val cfg = luxcavation(key, mode).let { it.withTask(key, it.task(key).with(stageKey(key), JsonPrimitive(stage))) }
                val saved = LimbusWorkspaceConfig.decode(cfg.encode())
                assertNull("$key mode=$mode stage=$stage", saved.validationError())
                val section = saved.backendSections().getValue(key.lowercase()).jsonObject
                assertEquals(stage, section.getValue("${key.lowercase()}_stage").jsonPrimitive.content)
                assertEquals(mode.lowercase(), section.getValue("luxcavation_mode").jsonPrimitive.content)
            }
        }
    }

    @Test fun missingLegacyFieldsKeepDefaultsAndNumericStagesRemainSupported() {
        for (key in listOf("EXP", "Thread")) {
            val cfg = luxcavation(key).let { it.withTask(key, it.task(key).copy(params = JsonObject(emptyMap()))) }
            assertNull(cfg.validationError())
            val section = cfg.backendSections().getValue(key.lowercase()).jsonObject
            assertEquals("enter", section.getValue("luxcavation_mode").jsonPrimitive.content)
            assertEquals(if (key == "EXP") "09" else "60", section.getValue("${key.lowercase()}_stage").jsonPrimitive.content)
            val numericStage = cfg.withTask(key, cfg.task(key).with(stageKey(key), JsonPrimitive(120)))
            assertNull(numericStage.validationError())
        }
    }

    @Test fun disabledLuxcavationsDoNotBlockOtherTasksWithUnusedInvalidFields() {
        var cfg = luxcavation("EXP")
        for (key in listOf("EXP", "Thread")) {
            cfg = cfg.withTask(key, cfg.task(key).copy(enabled = false)
                .with("luxcavationMode", JsonPrimitive("unknown"))
                .with(stageKey(key), JsonPrimitive("")))
        }
        cfg = cfg.withTask("Mail", cfg.task("Mail").copy(enabled = true))
        assertNull(cfg.validationError())
    }

    @Test fun legacyModesAndStagesMigrateWithoutHidingInvalidValues() {
        for (key in listOf("EXP", "Thread")) {
            val section = key.lowercase()
            for ((legacy, current) in listOf("enter" to "Enter", "skip battle" to "Skip Battle")) {
                val cfg = migratedLuxcavation(key, JsonPrimitive(legacy), JsonPrimitive("007"))
                assertEquals(current, cfg.task(key).string("luxcavationMode", ""))
                assertNull(cfg.validationError())
                assertEquals("007", cfg.backendSections().getValue(section).jsonObject
                    .getValue("${section}_stage").jsonPrimitive.content)
            }
            for (mode in listOf(JsonPrimitive("invalid"), JsonPrimitive(""), JsonNull, JsonObject(emptyMap()))) {
                val cfg = migratedLuxcavation(key, mode, JsonPrimitive("09"))
                assertEquals(mode, cfg.task(key).params["luxcavationMode"])
                assertTrue(cfg.validationError().orEmpty().contains("模式"))
            }
            val emptyStage = migratedLuxcavation(key, JsonPrimitive("skip battle"), JsonPrimitive(""))
            assertTrue(emptyStage.validationError().orEmpty().contains("关卡"))
        }
    }

    private fun luxcavation(key: String, mode: String = "Enter"): LimbusWorkspaceConfig {
        val cfg = LimbusWorkspaceConfig(taskConfigs = LimbusWorkspaceConfig.defaultTasks()
            .mapValues { (_, task) -> task.copy(enabled = false) })
            .withTeam(0, LimbusTeamConfig(selectedMembers = listOf("Faust")))
        return cfg.withTask(key, cfg.task(key).copy(enabled = true, teams = if (mode == "Skip Battle") emptyList() else listOf(1))
            .with("luxcavationMode", JsonPrimitive(mode)))
    }

    private fun migratedLuxcavation(key: String, mode: JsonElement, stage: JsonElement): LimbusWorkspaceConfig {
        val section = key.lowercase()
        val raw = buildJsonObject { put(section, buildJsonObject { put("luxcavation_mode", mode); put("${section}_stage", stage) }) }
        val cfg = LimbusWorkspaceConfig.migrate(
            mapOf("exp" to (key == "EXP"), "thread" to (key == "Thread"), "mirror" to false),
            mapOf(section to raw.toString()),
        ).withTeam(0, LimbusTeamConfig(selectedMembers = listOf("Faust")))
        return cfg.withTask(key, cfg.task(key).copy(teams = listOf(1)))
    }

    private fun stageKey(key: String) = if (key == "EXP") "expStage" else "threadStage"
}
