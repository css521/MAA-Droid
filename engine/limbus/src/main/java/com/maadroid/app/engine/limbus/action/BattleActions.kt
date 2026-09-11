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

private val TEAM_CLICK_POSITIONS = listOf(
    130 to 315, 130 to 355, 130 to 390, 130 to 430, 130 to 465, 130 to 500
)

private const val TEAMS_PER_PAGE = 6
private const val MAX_TEAM_NO = 19
private const val MAX_SCROLL = 3

/** 18、19 落在最后一页的第 5、6 格上，故从这里起改用偏移而非取模 */
private const val LAST_PAGE_FIRST_TEAM = 18
private const val LAST_PAGE_OFFSET = 14

/**
 * 队伍列表滑动后的静置时间。上游是 0.5 秒（PC 量），安卓上列表有惯性动画，不够。
 */
private const val LIST_SETTLE = 2.0

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
            // 所以两轮不能合并，也不能改顺序
            for (member in teamOrders) {
                TEAM_ORDER_MAP[member]?.let { click(ctx.input, it.first, it.second) }
            }
            for ((member, pos) in TEAM_ORDER_MAP) {
                if (member !in teamOrders) click(ctx.input, pos.first, pos.second)
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
        // 配置里是 1..20 的人类编号，减一化为 0..19
        val teamNo = (ctx.config.intAt(cfgType, "team_indexes", cfgIndex, 1) - 1)
            .coerceIn(0, MAX_TEAM_NO)
        val scrollCount = (teamNo / TEAMS_PER_PAGE).coerceIn(0, MAX_SCROLL)
        val clickIndex =
            if (teamNo < LAST_PAGE_FIRST_TEAM) teamNo % TEAMS_PER_PAGE
            else teamNo - LAST_PAGE_OFFSET
        ctx.log("选择队伍 #${teamNo + 1}（滑 $scrollCount 页，点第 $clickIndex 格 @${TEAM_CLICK_POSITIONS[clickIndex]}）")

        // 先划到顶部重置，否则续跑时列表停在上次位置，下面的滚动次数就对不上。
        // 每次滑动后必须等列表**惯性滑动停下**：上游给 0.5 秒是 PC 的量，安卓上不够——
        // 真机实测 "选择队伍→完成选择队伍" 只花 2 秒就点完了格子并交给 ready_to_battle，
        // 游戏还没稳定就被点，表现为进不了战斗。
        repeat(2) {
            swipe(ctx.input, 130, 320, 130, 720)
            ctx.delay(LIST_SETTLE)
        }

        repeat(scrollCount) {
            swipe(ctx.input, 130, 500, 130, 280)
            ctx.delay(LIST_SETTLE)
        }

        // 采帧：看翻完后列表停在第几格，用于修正安卓上的滑动距离和格位坐标
        ctx.recognize.dumpFrame("team_list_after_scroll_$scrollCount")
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
