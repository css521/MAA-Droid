package com.maadroid.app.engine.limbus.config

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LimbusLanguageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun mergesUpstreamLanguageFilesWithoutReplacingTaskSettings() {
        val root = temp.newFolder()
        write(root, "zh/sinners.json", """{"RyoShu":"良秀"}""")
        write(root, "zh/ego_gifts.json", """{"Lithograph":"石版字符"}""")
        val config = JsonLimbusConfig.fromSectionsJson("""{"mirror":{"check_node_target_count":4}}""")
            .withLanguage(root, "zh")
        assertEquals("良秀", config.str("language", "RyoShu", ""))
        assertEquals("石版字符", config.str("language", "Lithograph", ""))
        assertEquals(4, config.int("mirror", "check_node_target_count", 0))
    }

    @Test fun englishWithoutTranslationDirectoryUsesResourceNames() {
        val config = JsonLimbusConfig.empty().withLanguage(temp.newFolder(), "en")
        assertEquals("Lithograph", config.str("language", "Lithograph", "Lithograph"))
    }

    @Test fun aNewInstalledLanguageTableIsReadOnTheNextRun() {
        val root = temp.newFolder()
        write(root, "zh/ego_gifts.json", """{"Lithograph":"旧名称"}""")
        val before = JsonLimbusConfig.empty().withLanguage(root, "zh")
        write(root, "zh/ego_gifts.json", """{"Lithograph":"石版字符"}""")
        val after = JsonLimbusConfig.empty().withLanguage(root, "zh")
        assertEquals("旧名称", before.str("language", "Lithograph", ""))
        assertEquals("石版字符", after.str("language", "Lithograph", ""))
    }

    @Test fun rejectsMissingOrMalformedChineseTablesAndUnsupportedLanguages() {
        val root = temp.newFolder()
        assertThrows(IllegalArgumentException::class.java) { LimbusLanguage.load(root, "zh") }
        File(root, "config/language/zh").mkdirs()
        assertThrows(IllegalArgumentException::class.java) { LimbusLanguage.load(root, "zh") }
        write(root, "zh/bad.json", """{"Lithograph":123}""")
        assertThrows(IllegalArgumentException::class.java) { LimbusLanguage.load(root, "zh") }
        assertThrows(IllegalArgumentException::class.java) { LimbusLanguage.load(root, "../en") }
    }

    private fun write(root: File, path: String, json: String) {
        File(root, "config/language/$path").apply { parentFile!!.mkdirs(); writeText(json) }
    }
}
