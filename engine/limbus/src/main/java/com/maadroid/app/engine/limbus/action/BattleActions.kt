package com.maadroid.app.engine.limbus.action

import com.maadroid.app.engine.limbus.action.InputHelper.click
import com.maadroid.app.engine.limbus.action.InputHelper.keyPress
import com.maadroid.app.engine.limbus.action.InputHelper.swipe
import com.maadroid.app.engine.limbus.recognize.BattlePerception
import com.maadroid.app.engine.limbus.recognize.Crop

/**
 * 战斗相关动作：battle_winrate / ready_to_battle / choose_team。
 *
 * 对应上游 task_action/utils.py 和 task_execution.py。
 */
object BattleActions {

    fun registerAll() {
        ActionRegistry.registerAll(
            "battle_winrate" to BattleWinrateAction,
            "ready_to_battle" to ReadyToBattleAction,
            "choose_team" to ChooseTeamAction,
        )
    }
}

private val TEAM_ORDER_MAP = mapOf(
    "Yi Sang" to (290 to 240),
    "Faust" to (420 to 240),
    "Don Quixote" to (550 to 240),
    "Ryoshu" to (680 to 240),
    "Meursault" to (810 to 240),
    "Hong Lu" to (940 to 240),
    "Heathcliff" to (290 to 440),
    "Ishmael" to (420 to 440),
    "Rodion" to (550 to 440),
    "Sinclair" to (680 to 440),
    "Outis" to (810 to 440),
    "Gregor" to (940 to 440),
)

/**
 * 队伍列表可点位置，从真机三帧实测：**首项 y=313，行距 36px**。
 *
 * 上游是 315/355/390/430/465/500（PC 布局），首项对得上但后面逐渐偏移，
 * 到第 6 项差 7px —— 不足半格所以单看不致命，但与滑动错位叠加就会选错队伍。
 */
private val TEAM_CLICK_POSITIONS = (0 until TEAMS_PER_PAGE).map {
    130 to TEAM_FIRST_ROW_Y + it * TEAM_ROW_HEIGHT
}

/** 首个可点队伍项的 y（真机实测 313） */
private const val TEAM_FIRST_ROW_Y = 313

/** 队伍项行距（真机实测 36px：313/349/385/421/457/494） */
private const val TEAM_ROW_HEIGHT = 36

/** 慢速滑动步数。40 步/640ms 把每步位移压到 5px 上下，抬手时速度接近 0，消除惯性续滑 */
private const val SLOW_SWIPE_STEPS = 40

private const val TEAMS_PER_PAGE = 6

/**
 * 队伍编号上限。上游是 19（假设最多 20 个队伍），但实机可以有 40 个以上
 * —— 用户实测配置了 40 个。上限过小会把大编号 coerce 成 19，静默选错队伍。
 */
private const val MAX_TEAM_NO = 59

/** 最大滑动页数，随 MAX_TEAM_NO 放大：59 / 6 ≈ 9 页 */
private const val MAX_SCROLL = 10

/**
 * 队伍列表滑动后的静置时间。上游是 0.5 秒（PC 量），安卓上列表有惯性动画，不够。
 */
private const val LIST_SETTLE = 2.0

/**
 * 编队时每个成员点击之间的间隔。
 *
 * 上游零间隔连点 12 次（PC 上够用），安卓上会被大量吞掉 —— 真机实测点完
 * 只剩 "1 SELECTED / 1/12"，随后的"进战斗"点击落在人数不足的界面上被游戏拒绝，
 * 整个流程卡在编队页。
 */
private const val MEMBER_CLICK_GAP = 0.35

/** 点选队伍后等阵容载入的时间；ready_to_battle 要读"已选/总数"，读到旧值会误判 */
private const val TEAM_LOAD = 1.5

private object BattleWinrateAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.ensureActive()
        keyPress(ctx.input, "p")
        if (!ctx.config.bool("other_task", "ego_enable", false)) return ActionOutcome.Continue

        ctx.delay(0.5)
        ctx.ensureActive()
        val skills = ctx.recognize.battleSkillIcons()
        ctx.ensureActive()
        if (skills.isEmpty()) {
            ctx.log("未取得技能图标分类，本回合跳过主动 EGO")
            return ActionOutcome.Continue
        }
        if (BattlePerception.allUnselected(skills)) {
            // 720 是图外一行；等价的左下角点击限制在逻辑画面内。
            click(ctx.input, 10, 719)
            ctx.delay(1.0)
            ctx.ensureActive()
            return ActionOutcome.RetrySelf
        }
        if (!BattlePerception.hasDanger(skills)) return ActionOutcome.Continue
        val avatars = BattlePerception.threatenedAvatars(skills, ctx.recognize.battleSinnerAvatars())
        var anySelected = false
        for (avatar in avatars) {
            ctx.ensureActive()
            ctx.input.touchDown(avatar.x, avatar.y)
            try {
                ctx.delay(3.0)
                ctx.ensureActive()
            } finally {
                // InputHelper.longPress 没有 finally；这里必须保证取消/异常也抬起触点。
                ctx.input.touchUp(avatar.x, avatar.y)
            }
            var panel = ctx.recognize.battleEgoPanel()
            ctx.ensureActive()
            var selected = false // 每名罪人独立，不能沿用前一人的成功状态。
            val tried = mutableSetOf<Int>()
            while (panel != null && panel.details.isNotEmpty()) {
                ctx.ensureActive()
                val card = BattlePerception.safeEgoDetails(panel).firstOrNull { it.x !in tried } ?: break
                tried += card.x
                // 第一次点击可能已关闭面板；观察后才决定是否需要上游的第二次点击。
                click(ctx.input, card.x - 20, card.y + 100)
                ctx.delay(0.3)
                ctx.ensureActive()
                panel = ctx.recognize.battleEgoPanel()
                ctx.ensureActive()
                if (panel?.closed == true) {
                    selected = true
                    break
                }
                val sameCard = panel?.let { BattlePerception.safeEgoDetails(it) }
                    ?.any { it.x == card.x && it.y == card.y } == true
                if (sameCard) {
                    click(ctx.input, card.x - 20, card.y + 100)
                    ctx.delay(0.5)
                    ctx.ensureActive()
                    panel = ctx.recognize.battleEgoPanel()
                    ctx.ensureActive()
                    if (panel?.closed == true) {
                        selected = true
                        break
                    }
                }
            }
            if (selected) {
                anySelected = true
                ctx.log("已确认 EGO 选择界面关闭（罪人位置 ${avatar.x}）")
                click(ctx.input, 30, 700)
            } else {
                ctx.log("未确认安全 EGO 选择（罪人位置 ${avatar.x}）")
                if (panel?.closed != true) {
                    ctx.ensureActive()
                    click(ctx.input, avatar.x, avatar.y)
                    ctx.delay(0.3)
                    ctx.ensureActive()
                    val dismissed = ctx.recognize.battleEgoPanel()
                    ctx.ensureActive()
                    if (dismissed?.closed != true) {
                        // 未知界面上继续 p 或长按下一人会误操作；明确失败交给宿主展示。
                        return ActionOutcome.Finish(false, "无法确认 EGO 选择界面已关闭，停止战斗操作")
                    }
                }
            }
        }
        if (anySelected) {
            repeat(3) { i ->
                ctx.ensureActive()
                keyPress(ctx.input, "p")
                if (i < 2) ctx.delay(0.3)
            }
        }
        return ActionOutcome.Continue
    }
}

private object ReadyToBattleAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("准备战斗")
        val ocr = ctx.recognize.detectText(Crop(1130, 500, 100, 50))

        // OCR 出的是 "11/12" 这种已选/总数。认不出时取 0/1 让下面走重置分支 ——
        // 上游同样如此：宁可多重置一次，也不要带着不完整的队伍进战斗。
        val selectedCount = parseSlashCount(ocr.firstOrNull()?.text) ?: 0
        val allCount = parseSlashTotal(ocr.firstOrNull()?.text) ?: 1

        if (selectedCount != allCount) {
            click(ctx.input, 1140, 480)
            ctx.delay(1.0)
            if (ctx.recognize.templateMatch("reset_deployment_order").isNotEmpty()) {
                keyPress(ctx.input, "enter")
                ctx.delay(1.0)
            }

            val cfgType = ctx.node.str("cfg_type") ?: "mirror"
            val teamOrders = ctx.config.listAt(
                cfgType, "team_orders", resolveCfgIndex(ctx, cfgType)
            )

            // 先按配置顺序点指定成员，再把剩下的补齐 —— 点击顺序就是出场顺序，
            // 所以两轮不能合并，也不能改顺序。
            //
            // 每次点击后必须留间隔：上游是零间隔连点 12 次，安卓上会被大量吞掉 ——
            // 真机实测点完只有 "1 SELECTED / 1/12"，于是本动作末尾的"进战斗"点击
            // 落在人数不足的界面上，游戏拒绝，整个流程卡在编队页。
            for (member in teamOrders) {
                TEAM_ORDER_MAP[member]?.let {
                    click(ctx.input, it.first, it.second)
                    ctx.delay(MEMBER_CLICK_GAP)
                }
            }
            for ((member, pos) in TEAM_ORDER_MAP) {
                if (member !in teamOrders) {
                    click(ctx.input, pos.first, pos.second)
                    ctx.delay(MEMBER_CLICK_GAP)
                }
            }

            // 点完确认一次是否真的选满。没满就再等一轮让 UI 追上，仍不满则报出来 ——
            // 带着不完整的队伍点"进战斗"只会卡在这一页，不如把人数说清楚。
            ctx.delay(TEAM_LOAD)
            val after = ctx.recognize.detectText(Crop(1130, 500, 100, 50)).firstOrNull()?.text
            val nowSelected = parseSlashCount(after) ?: 0
            val nowAll = parseSlashTotal(after) ?: 0
            if (nowAll > 0 && nowSelected != nowAll) {
                ctx.log("编队仅选上 $nowSelected/$nowAll，可能有点击被吞；仍尝试进入战斗")
            }
        }

        click(ctx.input, 1140, 590)
        return ActionOutcome.Continue
    }
}

private object ChooseTeamAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val cfgType = ctx.node.str("cfg_type") ?: return ActionOutcome.Continue
        val cfgIndex = resolveCfgIndex(ctx, cfgType)
        // 配置里是人类编号（1 起），减一化为 0 起的索引
        val teamNo = (ctx.config.intAt(cfgType, "team_indexes", cfgIndex, 1) - 1)
            .coerceIn(0, MAX_TEAM_NO)
        val scrollCount = (teamNo / TEAMS_PER_PAGE).coerceIn(0, MAX_SCROLL)
        // 统一取模。上游对最后一页有个特例（队伍 18/19 落在第 5/6 格，改用固定偏移），
        // 那是"总共 20 个队伍"才成立的假设；实机可以有 40 个以上，特例反而会算错。
        val clickIndex = teamNo % TEAMS_PER_PAGE
        ctx.log("选择队伍 #${teamNo + 1}（滑 $scrollCount 页，点第 $clickIndex 格 @${TEAM_CLICK_POSITIONS[clickIndex]}）")

        // 先划到顶部重置，否则续跑时列表停在上次位置，下面的滚动次数就对不上。
        // 每次滑动后必须等列表**惯性滑动停下**：上游给 0.5 秒是 PC 的量，安卓上不够——
        // 真机实测 "选择队伍→完成选择队伍" 只花 2 秒就点完了格子并交给 ready_to_battle，
        // 游戏还没稳定就被点，表现为进不了战斗。
        repeat(2) {
            swipe(ctx.input, 130, 320, 130, 720)
            ctx.delay(LIST_SETTLE)
        }

        // 滑到顶之后先采一帧，作为"第 0 页"的基准
        ctx.recognize.dumpFrame("team_list_top", overwrite = true)
        repeat(scrollCount) { i ->
            // 慢速滑动消除惯性。真机三帧实测：默认 8 步/300ms 的快滑，手指位移 220px
            // （≈6 格）却让列表走了约 9.5 格 —— 惯性把位移放大了 1.6 倍。滑 2 次后
            // 首个可见项落在第 20 项（由 "TEAMS #24" 在第 5 个可见位置反推），
            // 而上游算法预期第 13 项，于是队伍 #15 被选成了 #22。
            //
            // 40 步 / 640ms 把每步位移压到 5px 上下，抬手时速度已接近 0，列表不再续滑。
            // 位移取 TEAMS_PER_PAGE × 格距，与「一次滑动翻一页」的算法假设对齐。
            swipe(
                ctx.input, 130, 500, 130, 500 - TEAMS_PER_PAGE * TEAM_ROW_HEIGHT,
                steps = SLOW_SWIPE_STEPS,
            )
            ctx.delay(LIST_SETTLE)
            ctx.recognize.dumpFrame("team_list_scroll_${i + 1}", overwrite = true)
        }
        click(ctx.input, TEAM_CLICK_POSITIONS[clickIndex].first, TEAM_CLICK_POSITIONS[clickIndex].second)
        // 点完队伍要等罪人阵容真的载入：上游点完即返回，后续 ready_to_battle 会读
        // "已选/总数"（Crop(1130,500,100,50)），读到旧值就会做出错误判断。
        ctx.delay(TEAM_LOAD)
        ctx.log("完成选择队伍")

        if (cfgType == "mirror") {
            ctx.delay(0.5)
            click(ctx.input, 1140, 590)
            ctx.delay(1.0)
            keyPress(ctx.input, "enter")
        }
        return ActionOutcome.Continue
    }
}
