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

/** 精确滑动的步数。真正消掉惯性靠的是 [InputHelper.swipe] 抬手前的原地驻留，不是步数。 */
private const val SLOW_SWIPE_STEPS = 24

/** 队伍列表可点的 x：侧栏横跨 x≈78..182，取中。 */
private const val TEAM_COLUMN_X = 130

/**
 * 队伍侧栏的取字区域（编队页左侧那条窄列表），真机三帧实测。
 *
 * 注意这不是"队伍选择页"——安卓上根本没有独立的队伍选择页，队伍列表就是编队页
 * 左边这条窄边栏，而右侧同屏显示着 12 名罪人。上游按 PC 布局假设的是另一回事。
 */
private val TEAM_SIDEBAR = Crop(74, 286, 118, 256)

/**
 * 只认完整可见的行。被列表上下边界切掉的行 OCR 出来是乱码
 * （真机实测把 `RUPTURE·MIRROR` 读成 `DIIDTIIEZ.AAIDDA`，置信度还有 0.84），
 * 一旦把它算作一行，按索引推算的位置就整体错一格。
 */
private const val ROW_BAND_TOP = 305
private const val ROW_BAND_BOTTOM = 520
private const val ROW_BAND_CENTER = (ROW_BAND_TOP + ROW_BAND_BOTTOM) / 2

/**
 * 未改名的队伍显示为 `TEAMS #N`，N 就是队伍编号 —— 列表里唯一的**绝对位置锚点**。
 *
 * 用户改过名的队伍显示自定义名，且名字会重复，所以按名字定位不成立；但只要视野里
 * 有任意一行还是默认名，就能反推出每一行的编号（列表按编号顺序排，真机实测
 * `MIRROR DUN.-BL.` / `TEAMS #21` / `TEAMS #22` / `TEAMS #23` 连续相邻）。
 */
private val TEAM_NO_REGEX = Regex("""TEAMS#(\d+)""", RegexOption.IGNORE_CASE)

/** 编队页标题栏。点完队伍后读它核对选中的编号，专门抓"点到隔壁队伍"这个故障。 */
private val TEAM_TITLE = Crop(222, 108, 210, 34)

/** 单次精确滑动的最大内容位移。侧栏可拖区域约 y∈[310,515]，留出余量。 */
private const val MAX_ALIGN_SWIPE_PX = 190

/** 对齐循环上限。每轮最多走 190px≈5.2 行，12 轮足够覆盖 MAX_TEAM_NO。 */
private const val MAX_ALIGN_ROUNDS = 12

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

/** 侧栏一行：文字 + 行中心 y；[teamNo] 仅当该行还是默认名 `TEAMS #N` 时非空。 */
private data class SidebarRow(val text: String, val y: Int, val teamNo: Int?)

/** 读侧栏里**完整可见**的行，自上而下。 */
private suspend fun readSidebar(ctx: ActionContext): List<SidebarRow> =
    ctx.recognize.detectText(TEAM_SIDEBAR)
        .filter { it.y in ROW_BAND_TOP..ROW_BAND_BOTTOM }
        .sortedBy { it.y }
        .map { match ->
            // OCR 会吞掉空格（真机实测读成 "TEAMS#21"），比对前统一去掉
            val compact = match.text.filterNot(Char::isWhitespace)
            SidebarRow(compact, match.y, TEAM_NO_REGEX.find(compact)?.groupValues?.get(1)?.toIntOrNull())
        }

/**
 * 精确滚动侧栏：让**内容**上移 [deltaPx] 像素（正数=目标在下方）。
 *
 * 手指位移与内容位移 1:1 —— 前提是 [InputHelper.swipe] 默认的抬手前驻留把惯性消掉了。
 * 单次夹在 [MAX_ALIGN_SWIPE_PX]，剩下的交给外层循环下一轮重新观测后再走。
 */
private suspend fun alignSidebar(ctx: ActionContext, deltaPx: Int) {
    val step = deltaPx.coerceIn(-MAX_ALIGN_SWIPE_PX, MAX_ALIGN_SWIPE_PX)
    if (step == 0) return
    // 内容上移 → 手指也上移，所以起点要留出 step 的行程
    val from = if (step > 0) ROW_BAND_BOTTOM - 5 else ROW_BAND_TOP + 5
    swipe(ctx.input, TEAM_COLUMN_X, from, TEAM_COLUMN_X, from - step, steps = SLOW_SWIPE_STEPS)
    ctx.delay(LIST_SETTLE)
}

private object ChooseTeamAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val cfgType = ctx.node.str("cfg_type") ?: return ActionOutcome.Continue
        val cfgIndex = resolveCfgIndex(ctx, cfgType)
        // 配置里是人类编号（1 起）
        val target = ctx.config.intAt(cfgType, "team_indexes", cfgIndex, 1)
            .coerceIn(1, MAX_TEAM_NO + 1)
        ctx.log("选择队伍 #$target")

        // 先甩到顶部：越界会被自动夹住，所以这里要的是"快"而不是"准"，
        // 传 settleMillis=0 保留惯性，两下就能从任意位置回到第一项。
        repeat(2) {
            swipe(ctx.input, TEAM_COLUMN_X, 320, TEAM_COLUMN_X, ROW_BAND_BOTTOM, settleMillis = 0)
            ctx.delay(LIST_SETTLE)
        }
        ctx.recognize.dumpFrame("team_list_top", overwrite = true)

        // 到顶后首行就是队伍 #1，据此粗跳到目标附近；此时滑动已无惯性，位移可预测。
        val coarse = (target - 1) * TEAM_ROW_HEIGHT - (ROW_BAND_CENTER - ROW_BAND_TOP)
        var remaining = coarse
        var jumps = 0
        while (remaining > 0 && jumps++ < MAX_ALIGN_ROUNDS) {
            alignSidebar(ctx, remaining)
            remaining -= remaining.coerceAtMost(MAX_ALIGN_SWIPE_PX)
        }
        ctx.recognize.dumpFrame("team_list_coarse", overwrite = true)

        // 闭环对齐：用侧栏里的 TEAMS #N 直接算出目标行的 y。
        // 这一步不依赖"滑了几行"，也不依赖行索引（被切掉的行 OCR 是乱码，计数会错），
        // 只依赖"列表按编号顺序排"这一条 —— 真机三帧已证实相邻编号连续。
        var clicked: SidebarRow? = null
        var fallbackY = TEAM_CLICK_POSITIONS[(target - 1) % TEAMS_PER_PAGE].second
        for (round in 1..MAX_ALIGN_ROUNDS) {
            ctx.ensureActive()
            val rows = readSidebar(ctx)
            if (rows.isEmpty()) {
                ctx.log("侧栏读不到任何队伍名（第 $round 轮），可能不在编队页")
                break
            }
            val anchor = rows.firstOrNull { it.teamNo != null }
            if (anchor == null) {
                // 所有可见队伍都被改过名：没有绝对锚点，只能相信粗跳的落点
                fallbackY = rows[((target - 1) % TEAMS_PER_PAGE).coerceAtMost(rows.lastIndex)].y
                ctx.log("侧栏无 TEAMS #N 锚点（可见 ${rows.size} 行），按粗跳落点点击 y=$fallbackY")
                break
            }
            val anchorNo = requireNotNull(anchor.teamNo)
            val targetY = anchor.y + (target - anchorNo) * TEAM_ROW_HEIGHT
            if (targetY in ROW_BAND_TOP..ROW_BAND_BOTTOM) {
                clicked = rows.minByOrNull { kotlin.math.abs(it.y - targetY) }
                    ?.takeIf { kotlin.math.abs(it.y - targetY) <= TEAM_ROW_HEIGHT / 2 }
                ctx.log("锚点 #$anchorNo@${anchor.y} → 队伍 #$target 在 y=$targetY（${clicked?.text ?: "该行未读到文字"}）")
                click(ctx.input, TEAM_COLUMN_X, targetY)
                fallbackY = targetY
                break
            }
            ctx.log("锚点 #$anchorNo@${anchor.y} → 目标 y=$targetY 在视野外，第 $round 轮继续对齐")
            alignSidebar(ctx, targetY - ROW_BAND_CENTER)
            if (round == MAX_ALIGN_ROUNDS) {
                ctx.log("对齐 $MAX_ALIGN_ROUNDS 轮仍未把队伍 #$target 带进视野，按粗跳落点点击")
                click(ctx.input, TEAM_COLUMN_X, fallbackY)
            }
        }
        if (clicked == null && fallbackY != 0) click(ctx.input, TEAM_COLUMN_X, fallbackY)

        // 点完队伍要等罪人阵容真的载入：上游点完即返回，后续 ready_to_battle 会读
        // "已选/总数"（Crop(1130,500,100,50)），读到旧值就会做出错误判断。
        ctx.delay(TEAM_LOAD)

        // 核对：标题栏显示当前队伍。目标未改名时它就是 "TEAMS #<target>"，
        // 专门抓"点到隔壁队伍"这个故障 —— 之前 #15 被选成 #22 就是这样静默发生的。
        // 目标改过名时标题是自定义名，读不出编号，此时不报警免得误伤。
        val title = ctx.recognize.detectText(TEAM_TITLE).firstOrNull()?.text?.filterNot(Char::isWhitespace)
        val shown = title?.let { TEAM_NO_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        when {
            shown == null -> ctx.log("完成选择队伍（标题「${title ?: "读不到"}」未含编号，无法核对）")
            shown == target -> ctx.log("完成选择队伍：标题已确认 TEAMS #$target")
            else -> ctx.log("警告：目标是队伍 #$target，但标题显示 TEAMS #$shown —— 选错了")
        }
        ctx.recognize.dumpFrame("team_selected", overwrite = true)

        if (cfgType == "mirror") {
            ctx.delay(0.5)
            click(ctx.input, 1140, 590)
            ctx.delay(1.0)
            keyPress(ctx.input, "enter")
        }
        return ActionOutcome.Continue
    }
}
