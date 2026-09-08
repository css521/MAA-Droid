package com.aliothmoon.maadroid.engine.limbus.action

import android.view.KeyEvent
import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import com.aliothmoon.maadroid.engine.limbus.recognize.TextMatch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 镜牢与战斗动作里「算错就会点错地方」的那部分逻辑。
 *
 * 这些用例的价值在于钉住上游那些**看起来像随手写、其实不能改**的细节：
 * 队伍列表最后一页的偏移、已持有饰品的左侧判定、点击的倒序。
 */
class MirrorAndBattleActionsTest {

    @Before
    fun setUp() {
        ActionRegistry.clearForTest()
        LimbusActions.resetForTest()
        LimbusActions.install()
    }

    private fun ctxFor(action: String, paramsJson: String = """{"cfg_type":"mirror"}""") =
        TestActionContext(node = nodeWith(paramsJson, action = action))

    // ---- choose_team：队伍编号 → 滚动次数 + 格位 ----

    @Test
    fun `第 1 套队伍不滚动点第一格`() = runTest {
        val ctx = ctxFor("choose_team")
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "team_indexes", listOf(1))

        ActionRegistry["choose_team"]!!.execute(ctx)

        // 前两次是划到顶部重置，之后不该再有滚动
        val swipes = ctx.fakeInput.events.count { it.startsWith("down(130,320)") }
        assertEquals(2, swipes)
        assertTrue("应点到第一格 y=315", ctx.fakeInput.clicks().contains(130 to 315))
    }

    @Test
    fun `第 19 套队伍落在最后一页第五格`() = runTest {
        // teamNo=18：上游用 18-14=4 而不是 18%6=0 —— 最后一页只剩两格可点
        val ctx = ctxFor("choose_team")
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "team_indexes", listOf(19))

        ActionRegistry["choose_team"]!!.execute(ctx)

        assertTrue("19 套应点第五格 y=465", ctx.fakeInput.clicks().contains(130 to 465))
        assertFalse("绝不能按取模点到第一格", ctx.fakeInput.clicks().contains(130 to 315))
    }

    @Test
    fun `第 20 套队伍落在最后一页第六格`() = runTest {
        val ctx = ctxFor("choose_team")
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "team_indexes", listOf(20))

        ActionRegistry["choose_team"]!!.execute(ctx)
        assertTrue(ctx.fakeInput.clicks().contains(130 to 500))
    }

    @Test
    fun `队伍编号越界被夹到合法范围不抛异常`() = runTest {
        val ctx = ctxFor("choose_team")
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "team_indexes", listOf(999))

        ActionRegistry["choose_team"]!!.execute(ctx)
        // 夹到 19（第 20 套）而不是越界崩掉
        assertTrue(ctx.fakeInput.clicks().contains(130 to 500))
    }

    @Test
    fun `镜牢的选队还要额外确认一次`() = runTest {
        val ctx = ctxFor("choose_team")
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "team_indexes", listOf(1))

        ActionRegistry["choose_team"]!!.execute(ctx)

        assertTrue("镜牢要点确认", ctx.fakeInput.clicks().contains(1140 to 590))
        assertTrue("并按回车", ctx.fakeInput.keyPresses().contains(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun `非镜牢的选队不做额外确认`() = runTest {
        val ctx = ctxFor("choose_team", """{"cfg_type":"exp"}""")
        ctx.fakeConfig.putGroups("exp", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("exp", "team_indexes", listOf(1))

        ActionRegistry["choose_team"]!!.execute(ctx)
        assertFalse(ctx.fakeInput.clicks().contains(1140 to 590))
    }

    // ---- ready_to_battle：OCR 人数决定是否重排 ----

    @Test
    fun `人数已满时直接开战不重排`() = runTest {
        val ctx = ctxFor("ready_to_battle")
        ctx.fakeRecognizer.textHits = listOf(TextMatch("12/12", 1150, 520, 0.9))

        ActionRegistry["ready_to_battle"]!!.execute(ctx)

        assertEquals("只该点开战按钮", listOf(1140 to 590), ctx.fakeInput.clicks())
    }

    @Test
    fun `人数不满时重排并按配置顺序点人`() = runTest {
        val ctx = ctxFor("ready_to_battle")
        ctx.fakeRecognizer.textHits = listOf(TextMatch("11/12", 1150, 520, 0.9))
        ctx.fakeConfig.putGroups("mirror", "team_orders",
            listOf(listOf("Rodion", "Faust")))

        ActionRegistry["ready_to_battle"]!!.execute(ctx)

        val clicks = ctx.fakeInput.clicks()
        val rodion = clicks.indexOf(550 to 440)
        val faust = clicks.indexOf(420 to 240)
        assertTrue("Rodion 与 Faust 都应被点到", rodion >= 0 && faust >= 0)
        assertTrue("必须按配置顺序：Rodion 先于 Faust", rodion < faust)
        // 其余 10 人补齐，共 12 人 + 重置按钮 + 开战按钮
        assertEquals(14, clicks.size)
    }

    @Test
    fun `OCR 认不出人数时按不满处理`() = runTest {
        val ctx = ctxFor("ready_to_battle")
        ctx.fakeRecognizer.textHits = emptyList()
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(emptyList<String>()))

        ActionRegistry["ready_to_battle"]!!.execute(ctx)

        // 宁可多重置一次，也不要带着不完整队伍进战斗
        assertTrue("应走重置分支", ctx.fakeInput.clicks().contains(1140 to 480))
    }

    // ---- battle_winrate ----

    @Test
    fun `战斗推进按 p`() = runTest {
        val ctx = ctxFor("battle_winrate")
        ActionRegistry["battle_winrate"]!!.execute(ctx)
        assertEquals(listOf(KeyEvent.KEYCODE_P), ctx.fakeInput.keyPresses())
    }

    @Test
    fun `开了 ego_enable 也不乱点，只提示尚未实现`() = runTest {
        val ctx = ctxFor("battle_winrate")
        ctx.fakeConfig.put("other_task", "ego_enable", true)

        ActionRegistry["battle_winrate"]!!.execute(ctx)

        assertTrue("不该产生任何点击", ctx.fakeInput.clicks().isEmpty())
        assertTrue("应明确告知用户", ctx.logs.any { "尚未实现" in it })
    }

    // ---- mirror_select_floor_ego_gift：已持有判定与倒序点击 ----

    @Test
    fun `左侧 200px 内有 Owned 的饰品视为已持有`() = runTest {
        val ctx = TestActionContext(
            node = nodeWith("""{"cfg_type":"mirror"}"""),
            templates = FakeTemplateIndex(mapOf(
                "ego_gifts" to listOf("Burning Branch"),
                "ego_gifts_Burn" to listOf("Burning Branch"),
            )),
        )
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "mirror_team_ego_gift_styles", listOf(listOf("Burn")))
        // 倾向饰品在 x=400，而 Owned 在 x=300（相距 100 < 200）→ 已持有，不该选
        ctx.fakeRecognizer.textHits = listOf(
            TextMatch("Burning Branch", 400, 190, 0.9),
            TextMatch("Owned", 300, 130, 0.9),
        )

        ActionRegistry["mirror_select_floor_ego_gift"]!!.execute(ctx)

        assertFalse("已持有的不该被点",
            ctx.fakeInput.clicks().any { it.first == 400 })
    }

    @Test
    fun `倾向且未持有的饰品会被点到框体内`() = runTest {
        val ctx = TestActionContext(
            node = nodeWith("""{"cfg_type":"mirror"}"""),
            templates = FakeTemplateIndex(mapOf(
                "ego_gifts" to listOf("Burning Branch"),
                "ego_gifts_Burn" to listOf("Burning Branch"),
            )),
        )
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "mirror_team_ego_gift_styles", listOf(listOf("Burn")))
        ctx.fakeRecognizer.textHits = listOf(TextMatch("Burning Branch", 400, 190, 0.9))

        ActionRegistry["mirror_select_floor_ego_gift"]!!.execute(ctx)

        // 文字在框上沿，点击要下偏 20 才落进框体
        assertTrue(ctx.fakeInput.clicks().contains(400 to 210))
    }

    @Test
    fun `全认不出时退到模板兜底而不是啥都不做`() = runTest {
        val ctx = ctxFor("mirror_select_floor_ego_gift")
        ctx.fakeRecognizer.textHits = emptyList()
        ctx.fakeRecognizer.onTemplate("acquire_ego_gift", Match(700, 190, 0.9))

        ActionRegistry["mirror_select_floor_ego_gift"]!!.execute(ctx)

        assertTrue("应点兜底识别到的位置", ctx.fakeInput.clicks().contains(700 to 210))
    }

    // ---- mirror_victory：领奖与弃奖两条路径 ----

    @Test
    fun `领奖走七次回车`() = runTest {
        val ctx = ctxFor("mirror_victory")
        ctx.fakeConfig.put("mirror", "accept_reward", true)

        ActionRegistry["mirror_victory"]!!.execute(ctx)

        assertEquals(7, ctx.fakeInput.keyPresses().count { it == KeyEvent.KEYCODE_ENTER })
    }

    @Test
    fun `弃奖要点放弃按钮`() = runTest {
        val ctx = ctxFor("mirror_victory")
        ctx.fakeConfig.put("mirror", "accept_reward", false)

        ActionRegistry["mirror_victory"]!!.execute(ctx)

        assertTrue("应点放弃奖励", ctx.fakeInput.clicks().contains(395 to 550))
        assertEquals(3, ctx.fakeInput.keyPresses().count { it == KeyEvent.KEYCODE_ENTER })
    }

    // ---- mirror_select_next_node：进不去要回主页而不是原地重试 ----

    @Test
    fun `三条路都进不去且没有车头时回主页重开`() = runTest {
        val ctx = ctxFor("mirror_select_next_node")
        val outcome = ActionRegistry["mirror_select_next_node"]!!.execute(ctx)
        assertEquals(ActionOutcome.Goto("back_to_init_page"), outcome)
    }

    @Test
    fun `某条路能进就按回车进入`() = runTest {
        val ctx = ctxFor("mirror_select_next_node")
        ctx.fakeRecognizer.onTemplate("node_enter", Match(1, 1, 0.9))

        val outcome = ActionRegistry["mirror_select_next_node"]!!.execute(ctx)

        assertEquals(ActionOutcome.Continue, outcome)
        assertTrue(ctx.fakeInput.keyPresses().contains(KeyEvent.KEYCODE_ENTER))
    }

    // ---- mirror_shop_heal_sinner：钱不够直接跳过 ----

    @Test
    fun `钱不够就不治疗`() = runTest {
        val ctx = ctxFor("mirror_shop_heal_sinner")
        ctx.fakeRecognizer.textHits = listOf(TextMatch("50", 600, 140, 0.9))

        ActionRegistry["mirror_shop_heal_sinner"]!!.execute(ctx)
        assertTrue("钱不够不该有任何点击", ctx.fakeInput.clicks().isEmpty())
    }

    @Test
    fun `配置关掉治疗时即便有钱也跳过`() = runTest {
        val ctx = ctxFor("mirror_shop_heal_sinner")
        ctx.fakeRecognizer.textHits = listOf(TextMatch("500", 600, 140, 0.9))
        ctx.fakeConfig.putGroups("mirror", "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups("mirror", "mirror_shop_heal", listOf(false))

        ActionRegistry["mirror_shop_heal_sinner"]!!.execute(ctx)

        assertTrue(ctx.fakeInput.clicks().isEmpty())
        assertTrue(ctx.logs.any { "跳过" in it })
    }
}
