package com.maadroid.app.engine.limbus.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LimbusCatalogTest {
    @get:Rule val temp = TemporaryFolder()

    private fun write(path: String, text: String = "image fixture") = File(temp.root, path).also {
        it.parentFile!!.mkdirs()
        it.writeText(text)
    }

    @Test fun derivesOnlyExistingImagesAndKeepsLalcIdsAndChineseTitles() {
        write("img/general/ego_gifts/Bleed/A Gift.png")
        write("img/general/ego_gifts/Burn/Unknown.png")
        write("img/general/ego_gifts/Burn/ignore.txt")
        write("img/general/theme_packs/A Pack.png")
        write("img/general/theme_packs/nested/Not A Pack.png")
        write("config/language/zh/ego_gifts.json", """{"A Gift":"饰品译名","Unknown":null,"No Image":"不应出现"}""")
        write("config/theme_pack_cfg.json", """{"A Pack":{"weight":99}}""")
        val catalog = LimbusCatalog.load(temp.root)
        assertEquals(listOf("A Gift", "Unknown"), catalog.gifts.map { it.name })
        assertEquals("饰品译名", catalog.gifts.first().title)
        assertEquals("Bleed", catalog.gifts.first().style)
        assertEquals("Unknown", catalog.gifts.last().title)
        assertEquals(listOf("A Pack"), catalog.packs.map { it.name })
        assertEquals("A Pack", catalog.packs.single().title)
        assertEquals(10, catalog.packs.single().weight(emptyMap()))
        assertEquals(0, catalog.packs.single().weight(mapOf("A Pack" to 0)))
        assertTrue((catalog.gifts + catalog.packs).all { File(temp.root, it.path).isFile })
    }

    @Test fun missingResourcesDoNotRequireAssetsOrInventEntries() {
        assertEquals(LimbusCatalog(), LimbusCatalog.load(null))
        assertEquals(LimbusCatalog(), LimbusCatalog.load(temp.root))
        val state = LimbusCatalogReader().refresh(temp.root, null)
        assertTrue(state.message!!.contains("先下载"))
        assertTrue(state.message.contains("配置已保留"))
        assertEquals(LimbusCatalog(), state.catalog)
    }

    @Test fun unlistedTranslationFallsBackToTheExactFileName() {
        write("img/general/ego_gifts/Keywordless/An Upstream Typo.png")
        assertEquals("An Upstream Typo", LimbusCatalog.load(temp.root).gifts.single().title)
    }

    @Test fun samePathRevisionReplacesRemovedEntriesAndTranslationsWithoutTouchingWeights() {
        val reader = LimbusCatalogReader()
        val old = write("img/general/ego_gifts/Bleed/Old.png")
        write("img/general/theme_packs/A Pack.png")
        val weights = mapOf("A Pack" to 37, "Retired Pack" to 81)
        val first = reader.refresh(temp.root, "revision-one")
        assertSame(first, reader.refresh(temp.root, "revision-one"))
        old.delete()
        write("img/general/ego_gifts/Burn/New.png")
        write("config/language/zh/ego_gifts.json", """{"New":"新的译名"}""")
        val second = reader.refresh(temp.root, "revision-two")
        assertEquals("revision-two", second.revision)
        assertEquals(listOf("New"), second.catalog.gifts.map { it.name })
        assertEquals("新的译名", second.catalog.gifts.single().title)
        assertEquals(37, second.catalog.packs.single().weight(weights))
        assertEquals(mapOf("A Pack" to 37, "Retired Pack" to 81), weights)
        write("config/language/zh/ego_gifts.json", """{"New":"更新的译名"}""")
        assertEquals("更新的译名", reader.refresh(temp.root, "revision-three").catalog.gifts.single().title)
    }

    @Test fun imageOnlyUpdateStillChangesTheArtworkRevision() {
        write("img/general/theme_packs/Same.png")
        val reader = LimbusCatalogReader()
        val first = reader.refresh(temp.root, "before")
        write("img/general/theme_packs/Same.png", "replacement image")
        val second = reader.refresh(temp.root, "after")
        assertEquals(first.catalog, second.catalog)
        assertNotEquals(first.revision, second.revision)
    }

    @Test fun missingManifestHidesOldCatalogAndReinstallationRestoresIt() {
        write("img/general/theme_packs/Pack.png")
        val reader = LimbusCatalogReader()
        assertEquals(1, reader.refresh(temp.root, "one").catalog.packs.size)
        assertTrue(reader.refresh(temp.root, null).catalog.packs.isEmpty())
        assertEquals(1, reader.refresh(temp.root, "one").catalog.packs.size)
    }

    @Test fun corruptLanguageTableShowsRecoveryMessageAndCanRecoverOnUpdate() {
        write("img/general/ego_gifts/Burn/Gift.png")
        write("config/language/zh/ego_gifts.json", "broken json")
        val reader = LimbusCatalogReader()
        val broken = reader.refresh(temp.root, "broken")
        assertTrue(broken.catalog.gifts.isEmpty())
        assertTrue(broken.message!!.contains("重新下载"))
        write("config/language/zh/ego_gifts.json", """{"Gift":"译名"}""")
        val repaired = reader.refresh(temp.root, "repaired")
        assertNull(repaired.message)
        assertEquals("译名", repaired.catalog.gifts.single().title)
    }
}
