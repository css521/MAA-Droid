package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.click
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.keyPress
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.swipe
import com.aliothmoon.maadroid.engine.limbus.recognize.Crop

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

private object BattleWinrateAction : ActionBackend {
    /**
     * 战斗推进：按 `p` 让本回合自动战斗。
     *
     * 上游在这之后还有一整套「主动触发 EGO」逻辑，本项目**尚未实现**，原因是它依赖
     * 三个当前 [Recognizer] 表达不出的能力：
     *
     * 1. `pyramid_template_match` 要返回命中的**缩放比**（上游按 scale >= 1.2 切换偏移量）
     * 2. skill_icon 分类器要连**每个图标的中心坐标**一起返回，才能把「劣势拼点」对到罪人
     * 3. 罪人头像检测（多边形涂黑 + 颜色筛选 + 打分）整条链路
     *
     * 这三样做完之前，这里只按 `p` —— 战斗仍能正常打完，只是不会为了保硬币主动开 EGO。
     * 不做「简化版」猜坐标：点错位置会打断战斗流程，比不开 EGO 糟得多。
     *
     * 与上游一致，EGO 触发本身受 `ego_enable` 开关控制且默认关闭，故默认路径无差异。
     */
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        keyPress(ctx.input, "p")

        if (ctx.config.bool("other_task", "ego_enable", false)) {
            ctx.log("EGO 主动触发尚未实现（需罪人头像检测），本回合按默认战斗推进")
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
        ctx.log("选择队伍")
        val cfgType = ctx.node.str("cfg_type") ?: return ActionOutcome.Continue
        val cfgIndex = resolveCfgIndex(ctx, cfgType)
        // 配置里是 1..20 的人类编号，减一化为 0..19
        val teamNo = (ctx.config.intAt(cfgType, "team_indexes", cfgIndex, 1) - 1)
            .coerceIn(0, MAX_TEAM_NO)

        // 先划到顶部重置，否则续跑时列表停在上次位置，下面的滚动次数就对不上
        repeat(2) {
            swipe(ctx.input, 130, 320, 130, 720)
            ctx.delay(0.5)
        }

        val scrollCount = (teamNo / TEAMS_PER_PAGE).coerceIn(0, MAX_SCROLL)
        repeat(scrollCount) {
            swipe(ctx.input, 130, 500, 130, 280)
            ctx.delay(0.5)
        }

        // 最后一页只剩两格可点（18→4、19→5），不能再按整页取模
        val clickIndex =
            if (teamNo < LAST_PAGE_FIRST_TEAM) teamNo % TEAMS_PER_PAGE
            else teamNo - LAST_PAGE_OFFSET

        click(ctx.input, TEAM_CLICK_POSITIONS[clickIndex].first, TEAM_CLICK_POSITIONS[clickIndex].second)
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
