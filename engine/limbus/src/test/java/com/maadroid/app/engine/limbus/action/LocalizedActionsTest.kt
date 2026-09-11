package com.maadroid.app.engine.limbus.action

import com.maadroid.app.engine.limbus.recognize.Crop
import com.maadroid.app.engine.limbus.recognize.Match
import com.maadroid.app.engine.limbus.recognize.Recognizer
import com.maadroid.app.engine.limbus.recognize.TextMatch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocalizedActionsTest {
    @Before fun register() {
        ActionRegistry.clearForTest()
        LimbusActions.resetForTest()
        LimbusActions.install()
    }

    @Test fun chineseOcrReturnsTheOriginalGiftIdForAllowAndBlockLists() {
        val config = FakeConfig().put("language", "Lithograph", "石版字符")
        assertEquals("Lithograph", closestLocalizedName("石版字符", listOf("Lithograph", "Oracle"), config))
        assertNull(closestLocalizedName("完全不同", listOf("Lithograph"), config))
        assertEquals("Oracle", closestLocalizedName("Oracle", listOf("Oracle"), config))
        config.put("language", "RyoShu", "良秀")
        assertEquals("良秀", localizedName(config, "Ryoshu"))
    }

    @Test fun chineseFloorGiftUsesTheConfiguredPreference() = runTest {
        val fake = FakeRecognizer()
        val rec = object : Recognizer by fake {
            // 饰品名区域是 Crop(90,168,1090,60)，Acquire 标记区域是 Crop(110,138,1090,50)。
            // 用 y 区分二者：只有饰品名区域返回文字，让这条用例专测「倾向饰品被选中」。
            override suspend fun detectText(crop: Crop?, threshold: Double) =
                if (crop?.y == 168) listOf(TextMatch("石版字符", 400, 190, .95)) else emptyList()
        }
        val ctx = TestActionContext(node = nodeWith("""{"cfg_type":"mirror"}"""), recognize = rec,
            templates = FakeTemplateIndex(mapOf("ego_gifts" to listOf("Lithograph"))))
        ctx.fakeConfig.put("language", "Lithograph", "石版字符")
        ctx.fakeConfig.putGroups("mirror", "mirror_team_ego_allow_list", listOf(listOf("Lithograph")))
        ActionRegistry["mirror_select_floor_ego_gift"]!!.execute(ctx)
        assertTrue(ctx.fakeInput.clicks().contains(400 to 210))
    }

    @Test fun chineseEventChoosesTheRewardOptionInsteadOfClickingEveryOption() = runTest {
        val ctx = TestActionContext()
        ctx.fakeConfig.put("language", "Pass to gain", "成功时")
        ctx.fakeRecognizer.textHits = listOf(TextMatch("成功时获得饰品", 940, 310, .95))
        ActionRegistry["event_make_choice"]!!.execute(ctx)
        assertEquals(listOf(940 to 310), ctx.fakeInput.clicks())
    }

    @Test fun chineseRyoshuNameTriggersTheSavedSkillOrder() = runTest {
        val fake = FakeRecognizer().apply {
            onTemplateSequence("shop_purchased", emptyList(), listOf(Match(620, 230, .95)))
        }
        val rec = object : Recognizer by fake {
            override suspend fun detectText(crop: Crop?, threshold: Double) =
                if (crop?.y == 320) listOf(TextMatch("良秀", 620, 345, .95)) else emptyList()
        }
        val ctx = TestActionContext(node = nodeWith("""{"cfg_type":"mirror"}"""), recognize = rec)
        ctx.fakeConfig.put("language", "RyoShu", "良秀")
        ctx.fakeConfig.putRawGroups("mirror", "mirror_replace_skill", """{"Ryoshu":[3,1,2]}""")
        ActionRegistry["mirror_shop_replace_skill_and_purchase_ego_gifts"]!!.execute(ctx)
        assertEquals(listOf(620 to 265, 630 to 330, 300 to 330, 960 to 330, 790 to 535, 790 to 535),
            ctx.fakeInput.clicks())
    }

    @Test fun skipsPurchasedGiftWithoutRenumberingAndOffsetsAfterTheNextPurchase() = runTest {
        val fake = FakeRecognizer()
        val rec = object : Recognizer by fake {
            override suspend fun detectText(crop: Crop?, threshold: Double): List<TextMatch> = when (crop?.y) {
                335 -> listOf(TextMatch("石版字符", 620, 350, .95), TextMatch("Oracle", 780, 350, .95),
                    TextMatch("Third Gift", 940, 350, .95))
                220 -> listOf(TextMatch("Purchased", 620, 230, .95))
                else -> emptyList()
            }
        }
        val ctx = TestActionContext(node = nodeWith("""{"cfg_type":"mirror"}"""), recognize = rec,
            templates = FakeTemplateIndex(mapOf("ego_gifts" to listOf("Lithograph", "Oracle", "Third Gift"))))
        ctx.fakeConfig.put("language", "Lithograph", "石版字符")
        ctx.fakeConfig.putGroups("mirror", "mirror_team_ego_allow_list",
            listOf(listOf("Lithograph", "Oracle", "Third Gift")))
        ActionRegistry["mirror_shop_replace_skill_and_purchase_ego_gifts"]!!.execute(ctx)
        // 槽位 0 已购；槽位 1 买完后槽位 2 左移到 1。
        assertEquals(listOf(780 to 270, 740 to 480, 650 to 535, 780 to 270, 740 to 480, 650 to 535),
            ctx.fakeInput.clicks())
    }
}
