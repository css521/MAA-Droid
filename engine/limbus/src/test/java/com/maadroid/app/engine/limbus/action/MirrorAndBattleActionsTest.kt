package com.maadroid.app.engine.limbus.action

import android.view.KeyEvent
import com.maadroid.app.engine.limbus.recognize.Crop
import com.maadroid.app.engine.limbus.recognize.Match
import com.maadroid.app.engine.limbus.recognize.TextMatch
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

    // ---- choose_team：闭环定位到指定编号的队伍 ----

    /**
     * 把 choose_team 接到 [SidebarSim] 上，返回模拟器以便断言最终选中了谁。
     *
     * 侧栏、标题两个区域各读各的：production 用 detectText 的不同 Crop 区分，
     * 单个 textHits 表达不了。
     */
    private fun wireSidebar(ctx: TestActionContext, sim: SidebarSim): SidebarSim {
        ctx.fakeRecognizer.onDetectText = { crop ->
            when (crop) {
                SidebarSim.SIDEBAR -> sim.rows(ctx.fakeInput.events)
                SidebarSim.TITLE -> sim.title(ctx.fakeInput.events)
                else -> null
            }
        }
        return sim
    }

    private fun teamCtx(target: Int, cfgType: String = "mirror"): TestActionContext {
        val ctx = ctxFor("choose_team", """{"cfg_type":"$cfgType"}""")
        ctx.fakeConfig.putGroups(cfgType, "team_orders", listOf(listOf("Yi Sang")))
        ctx.fakeConfig.putGroups(cfgType, "team_indexes", listOf(target))
        return ctx
    }

    @Test
    fun `第 1 套队伍在顶部直接点中`() = runTest {
        val ctx = teamCtx(1)
        val sim = wireSidebar(ctx, SidebarSim(teamCount = 40))

        ActionRegistry["choose_team"]!!.execute(ctx)

        assertEquals(1, sim.selected)
        // y=313 是真机三帧实测的顶部首行位置（上游 PC 值是 315）
        assertTrue("应点到顶部首行 y=313", ctx.fakeInput.clicks().contains(130 to 313))
    }

    /**
     * **这是最关键的一条**：把惯性倍率设成真机实测的 1.58，定位仍须准确。
     *
     * 上一版就是死在这里 —— 手指走 6 行、列表实际走 9.4 行，于是第 15 套被选成第 22 套，
     * 而且悄无声息。闭环定位不该依赖这个倍率是多少。
     */
    @Test
    fun `惯性把位移放大 1_58 倍时仍然选对队伍`() = runTest {
        val ctx = teamCtx(15)
        val sim = wireSidebar(ctx, SidebarSim(teamCount = 40, ratio = 1.58))

        ActionRegistry["choose_team"]!!.execute(ctx)

        assertEquals("第 15 套必须选中，实际选中第 ${sim.selected} 套", 15, sim.selected)
        assertTrue(
            "应记下标题核对通过",
            ctx.logs.any { it.contains("标题已确认 TEAMS #15") },
        )
    }

    /**
     * 队伍名可自定义，用户全部改过名时一个 `TEAMS #N` 都读不到 —— 此时唯一的绝对锚点
     * 是"列表顶部就是第 1 套"，位移靠连续行序列比对实测。这条用例连重名一起测：
     * 单个名字重复不足以定位，所以比对的是连续两行以上的序列。
     */
    @Test
    fun `所有队伍都改过名且名字重复时靠顶部锚点与序列比对定位`() = runTest {
        val renamed = (1..40).associateWith { if (it % 3 == 0) "MIRROR DUN" else "POISE-MIRROR" }
        val ctx = teamCtx(23)
        val sim = wireSidebar(ctx, SidebarSim(teamCount = 40, renamed = renamed, ratio = 1.58))

        ActionRegistry["choose_team"]!!.execute(ctx)

        assertEquals("第 23 套必须选中，实际选中第 ${sim.selected} 套", 23, sim.selected)
        assertTrue(
            "没有编号锚点时不该谎称核对通过",
            ctx.logs.none { it.contains("标题已确认") },
        )
    }

    @Test
    fun `队伍编号越界被夹到合法范围且仍能走到列表末尾`() = runTest {
        val ctx = teamCtx(999)
        val sim = wireSidebar(ctx, SidebarSim(teamCount = 60))

        ActionRegistry["choose_team"]!!.execute(ctx)

        // 夹到 MAX_TEAM_NO=59 → 第 60 套，而不是越界崩掉
        assertEquals(60, sim.selected)
    }

    @Test
    fun `目标队伍不存在时报警而不是乱点一个`() = runTest {
        val ctx = teamCtx(30)
        val sim = wireSidebar(ctx, SidebarSim(teamCount = 10))

        ActionRegistry["choose_team"]!!.execute(ctx)

        assertEquals("列表里没有第 30 套，不能选中任何队伍", 0, sim.selected)
        assertTrue(ctx.logs.any { it.contains("放弃选队") })
        assertFalse("放弃后不该再走镜牢的确认", ctx.fakeInput.clicks().contains(1140 to 590))
    }

    @Test
    fun `镜牢的选队还要额外确认一次`() = runTest {
        val ctx = teamCtx(1)
        wireSidebar(ctx, SidebarSim(teamCount = 40))

        ActionRegistry["choose_team"]!!.execute(ctx)

        assertTrue("镜牢要点确认", ctx.fakeInput.clicks().contains(1140 to 590))
        assertTrue("并按回车", ctx.fakeInput.keyPresses().contains(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun `非镜牢的选队不做额外确认`() = runTest {
        val ctx = teamCtx(1, cfgType = "exp")
        wireSidebar(ctx, SidebarSim(teamCount = 40))

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
    fun `开了 ego_enable 但无技能感知结果时不乱点`() = runTest {
        val ctx = ctxFor("battle_winrate")
        ctx.fakeConfig.put("other_task", "ego_enable", true)

        ActionRegistry["battle_winrate"]!!.execute(ctx)

        assertTrue("不该产生任何点击", ctx.fakeInput.clicks().isEmpty())
        assertTrue("应明确告知用户", ctx.logs.any { "未取得技能图标分类" in it })
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

/**
 * 队伍侧栏的滚动模拟器。
 *
 * 存在的理由很直接：队伍定位被改错过四次，每次的错法都一样 —— 假设"一次滑动走几行"。
 * 这里把那个假设变成可调参数 [ratio]（真机实测惯性下是 1.58），
 * 于是"定位不依赖倍率"这件事可以在纯 JVM 里被证伪，不必等一轮真机日志。
 *
 * 模型取自真机三帧：顶部首行 y=313、行距 36、可见 6 行、列表自由滚动不吸附行。
 */
private class SidebarSim(
    teamCount: Int,
    renamed: Map<Int, String> = emptyMap(),
    private val ratio: Double = 1.0,
) {
    private val names = (1..teamCount).map { renamed[it] ?: "TEAMS#$it" }
    private val maxOffset = ((teamCount - VISIBLE_ROWS) * ROW_H).coerceAtLeast(0).toDouble()
    private var offset = 0.0
    private var consumed = 0

    /** 最终选中的队伍编号；0 表示一个都没点中。 */
    var selected: Int = 0
        private set

    fun rows(events: List<String>): List<TextMatch> {
        replay(events)
        return names.indices.mapNotNull { i ->
            val y = (TOP_ROW_Y + i * ROW_H - offset).toInt()
            if (y in BAND_TOP..BAND_BOTTOM) TextMatch(names[i], COLUMN_X, y, 0.95) else null
        }
    }

    /** 标题栏显示当前选中队伍的名字；没选中过则读不到。 */
    fun title(events: List<String>): List<TextMatch> {
        replay(events)
        val no = selected
        return if (no == 0) emptyList() else listOf(TextMatch(names[no - 1], 260, 125, 0.99))
    }

    /**
     * 重放尚未处理的注入事件。滑动按 down→move…→up 一组，位移取 down.y 减最后一个 move.y，
     * 再乘 [ratio] —— 这就是惯性：手指走多少，列表走 ratio 倍。
     * 没有 move 的一组是点击，命中可见行就记为选中。
     */
    private fun replay(events: List<String>) {
        var downY: Int? = null
        var lastY = 0
        var moved = false
        var inColumn = false
        var index = consumed
        while (index < events.size) {
            val event = events[index]
            index++
            val match = EVENT.matchEntire(event) ?: continue
            val x = match.groupValues[2].toInt()
            val y = match.groupValues[3].toInt()
            when (match.groupValues[1]) {
                "down" -> {
                    downY = y
                    lastY = y
                    moved = false
                    inColumn = x == COLUMN_X
                }
                "move" -> {
                    lastY = y
                    moved = true
                }
                "up" -> {
                    val start = downY
                    if (start != null && inColumn) {
                        if (moved) {
                            offset = (offset + (start - lastY) * ratio).coerceIn(0.0, maxOffset)
                        } else if (y in BAND_TOP..BAND_BOTTOM) {
                            // 落在哪一行就选中哪一套；半行以内才算命中，与 production 一致
                            val exact = (y + offset - TOP_ROW_Y) / ROW_H
                            val row = Math.round(exact).toInt()
                            if (row in names.indices && Math.abs(exact - row) <= 0.5) {
                                selected = row + 1
                            }
                        }
                    }
                    downY = null
                    consumed = index
                }
            }
        }
    }

    companion object {
        // 与 BattleActions.kt 里的同名常量对应；那些是 file-private，测试里只能复述一份
        val SIDEBAR = Crop(74, 286, 118, 256)
        val TITLE = Crop(222, 108, 210, 34)
        const val TOP_ROW_Y = 313
        const val ROW_H = 36
        const val BAND_TOP = 305
        const val BAND_BOTTOM = 520
        const val COLUMN_X = 130
        const val VISIBLE_ROWS = 6
        private val EVENT = Regex("""(down|move|up)\((-?\d+),(-?\d+)\)""")
    }
}
