package com.maadroid.app.engine.limbus.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作层共用逻辑的验证。
 *
 * 这里每条断言都对应一个上游语义，其中相似度的期望值是**实跑 CPython difflib 取的**
 * （`difflib.SequenceMatcher(None, a, b).ratio()`）—— 移植这个比值而非用编辑距离代替，
 * 是因为上游的 0.8 阈值只在这个比值下才有意义。
 */
class ActionSupportTest {

    // ---- similarity: 与 CPython difflib 逐值对齐 ----

    @Test
    fun `相同字符串相似度为 1`() {
        assertEquals(1.0, similarity("Burning Branch", "Burning Branch"), 1e-9)
        assertEquals(1.0, similarity("Owned", "Owned"), 1e-9)
    }

    @Test
    fun `OCR 常见误认的相似度与 Python difflib 一致`() {
        // rn -> m 是 OCR 最常见的误认
        assertEquals(0.888889, similarity("Buming Branch", "Burning Branch"), 1e-6)
        assertEquals(0.857143, similarity("Bumning Braneh", "Burning Branch"), 1e-6)
        // O -> 0
        assertEquals(0.944444, similarity("Acquire E.G.O Gift", "Acquire E.G.0 Gift"), 1e-6)
    }

    @Test
    fun `不同饰品名的相似度低于阈值`() {
        // 只有 " Damage Up" 公共部分，0.733 < 0.8，不能被认成同一个
        assertEquals(0.733333, similarity("Slash Damage Up", "Blunt Damage Up"), 1e-6)
    }

    @Test
    fun `完全不同与空串相似度为 0`() {
        assertEquals(0.0, similarity("abc", "xyz"), 1e-9)
        assertEquals(0.0, similarity("", "abc"), 1e-9)
    }

    // ---- closestName: 对应 difflib.get_close_matches(cutoff=0.8) 取第一个 ----

    @Test
    fun `closestName 把 OCR 误认修回已知饰品名`() {
        val names = listOf("Burning Branch", "Blunt Damage Up", "Slash Damage Up", "Sinking Deluge")
        assertEquals("Burning Branch", closestName("Buming Branch", names))
        assertEquals("Burning Branch", closestName("Bumning Braneh", names))
        assertEquals("Slash Damage Up", closestName("Slash Damage Up", names))
    }

    @Test
    fun `closestName 低于 cutoff 时返回 null 而不是硬凑一个`() {
        val names = listOf("Burning Branch", "Blunt Damage Up")
        // 认不出就该放弃这一项；硬凑会去点错的饰品
        assertNull(closestName("zzzz", names))
    }

    // ---- parseSlash*: "11/12" ----

    @Test
    fun `解析已选与总数`() {
        assertEquals(11, parseSlashCount("11/12"))
        assertEquals(12, parseSlashTotal("11/12"))
    }

    @Test
    fun `解析失败一律返回 null 交由调用方取默认值`() {
        for (bad in listOf(null, "", "12", "/12", "abc/def", "11/")) {
            assertTrue("不该从 $bad 解析出数字",
                parseSlashCount(bad) == null || parseSlashTotal(bad) == null)
        }
        assertNull(parseSlashCount("/12"))
        assertNull(parseSlashTotal("11/"))
    }

    // ---- dedupeByX ----

    @Test
    fun `dedupeByX 保留先出现的并丢掉同列的`() {
        val pts = listOf(100 to 1, 150 to 2, 300 to 3, 320 to 4, 500 to 5)
        val kept = dedupeByX(pts, threshold = 100) { it.first }
        // 150 距 100 不足 100 被丢；320 距 300 不足 100 被丢
        assertEquals(listOf(100 to 1, 300 to 3, 500 to 5), kept)
    }

    // ---- resolveCfgIndex: 队伍轮换 ----

    @Test
    fun `队伍轮换按 check 节点计数取模`() {
        val ctx = TestActionContext()
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(
            listOf("Yi Sang"), listOf("Faust"), listOf("Rodion"),
        ))
        assertEquals(0, resolveCfgIndex(ctx, "mirror"))
        ctx.setCounter("mirror_check", 1)
        assertEquals(1, resolveCfgIndex(ctx, "mirror"))
        ctx.setCounter("mirror_check", 4)
        // 三套队伍，第 5 轮回到第二套
        assertEquals(1, resolveCfgIndex(ctx, "mirror"))
    }

    @Test
    fun `没配队伍时轮换下标退化为 0 而不是除零`() {
        assertEquals(0, resolveCfgIndex(TestActionContext(), "mirror"))
    }

    // ---- preferredEgoGifts: 体系展开 + 白名单 - 黑名单 ----

    @Test
    fun `倾向饰品由体系展开并叠加白名单`() {
        val ctx = TestActionContext(
            templates = FakeTemplateIndex(mapOf(
                "ego_gifts_Burn" to listOf("Burning Branch", "Ember"),
                "ego_gifts_Bleed" to listOf("Bloodfeast"),
            ))
        )
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "mirror_team_ego_gift_styles",
            listOf(listOf("Burn", "Bleed")))
        ctx.fakeConfig.putGroups("mirror", "mirror_team_ego_allow_list",
            listOf(listOf("Special Gift")))

        assertEquals(
            setOf("Burning Branch", "Ember", "Bloodfeast", "Special Gift"),
            preferredEgoGifts(ctx, "mirror", 0),
        )
    }

    @Test
    fun `黑名单最后生效可以剔掉同体系的饰品`() {
        val ctx = TestActionContext(
            templates = FakeTemplateIndex(mapOf(
                "ego_gifts_Burn" to listOf("Burning Branch", "Ember"),
            ))
        )
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "mirror_team_ego_gift_styles", listOf(listOf("Burn")))
        ctx.fakeConfig.putGroups("mirror", "mirror_team_ego_block_list", listOf(listOf("Ember")))

        // Ember 同属 Burn 体系，但被拉黑就必须不在名单里
        assertEquals(setOf("Burning Branch"), preferredEgoGifts(ctx, "mirror", 0))
    }

    @Test
    fun `无用饰品是全部减去倾向`() {
        val ctx = TestActionContext(
            templates = FakeTemplateIndex(mapOf(
                "ego_gifts" to listOf("Burning Branch", "Ember", "Bloodfeast", "Junk"),
                "ego_gifts_Burn" to listOf("Burning Branch", "Ember"),
            ))
        )
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "mirror_team_ego_gift_styles", listOf(listOf("Burn")))

        assertEquals(setOf("Bloodfeast", "Junk"), uselessEgoGifts(ctx, "mirror", 0))
    }
}
