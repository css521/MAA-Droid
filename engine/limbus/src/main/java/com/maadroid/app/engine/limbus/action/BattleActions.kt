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
 * 队伍项行距（真机实测 36px，三帧一致：313/349/385/421/457/494）。
 *
 * 上游那套固定落点 315/355/390/430/465/500 是 PC 布局，且隐含"列表按行对齐"的假设 ——
 * 实测行首 y 在三帧里是 313.5 / 331.2 / 319.7，**列表是自由滚动、不吸附行**的，
 * 所以固定落点从根上不成立，只有行距可用。
 */
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
 * 未改名的队伍显示为 `TEAMS #N`，N 就是队伍编号。
 *
 * 这是**免费的精确校准**，不是定位的依据：队伍名可以自定义，用户全改过名时一个都读不到。
 * 真正的绝对锚点是"列表顶部就是队伍 #1"（见 [TOP_ROW_Y]），与名字无关。
 */
private val TEAM_NO_REGEX = Regex("""TEAMS#(\d+)""", RegexOption.IGNORE_CASE)

/**
 * 滑到顶时首行的 y。三帧实测 313.5 —— 列表滚到尽头就夹住，所以这是个稳定参照，
 * 且**与队伍名无关**：无论用户怎么改名，顶部那一行都是队伍 #1。
 */
private const val TOP_ROW_Y = 313
private const val TOP_ROW_TOLERANCE = 12

/**
 * 单步允许的最大**内容**位移，按行计。
 *
 * 取 4 行（可见 6 行减 2）是为了保证相邻两次观测一定有 ≥2 行重叠 ——
 * [matchShift] 要靠这段重叠反推实际位移。位移一旦超过可见行数就完全没有重叠，
 * 上一版滑 6 行实际走 9.4 行就是这样把自己的参照丢掉的。
 */
private const val OVERLAP_SAFE_ROWS = 4

/**
 * 首步的步长（行）。倍率还是初值时误差最大，而 [matchShift] 的消歧前提是
 * "预期与实际之差不足半个重名周期" —— 先用小步把倍率测准，后面才敢放大步子。
 */
private const val PROBE_ROWS = 2

/** 手指位移 → 内容位移的倍率，运行时实测校准。1.0 = 无惯性（[InputHelper.swipe] 的驻留生效）。 */
private const val RATIO_INITIAL = 1.0
private const val RATIO_MIN = 0.7
private const val RATIO_MAX = 2.5

/** 编队页标题栏。点完队伍后读它核对选中的编号，专门抓"点到隔壁队伍"这个故障。 */
private val TEAM_TITLE = Crop(222, 108, 210, 34)

/** 单次精确滑动的最大内容位移。侧栏可拖区域约 y∈[310,515]，留出余量。 */
private const val MAX_ALIGN_SWIPE_PX = 190

/**
 * 对齐循环上限。每轮最多走 [OVERLAP_SAFE_ROWS] 行，走到第 60 套需要 ceil(59/4)=15 轮，
 * 留出余量取 24。
 */
private const val MAX_ALIGN_ROUNDS = 24

/**
 * 精确对齐每步之后的静置时间。
 *
 * 不用 [LIST_SETTLE] 那 2 秒：那是为惯性续滑留的，而这里的滑动抬手前已经把速度压到 0，
 * 手指停下列表就停下，只需要覆盖一帧渲染延迟。按 15 步算，2.0 秒会白等 30 秒。
 */
private const val ALIGN_SETTLE = 0.6

/**
 * 队伍编号上限。上游是 19（假设最多 20 个队伍），但实机可以有 40 个以上
 * —— 用户实测配置了 40 个。上限过小会把大编号 coerce 成 19，静默选错队伍。
 */
private const val MAX_TEAM_NO = 59

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
 * 精确滚动侧栏：手指位移 [fingerPx]（正数=手指上移=内容上移）。返回实际发出的手指位移。
 *
 * 单次夹在 [MAX_ALIGN_SWIPE_PX]，因为侧栏可拖区域只有约 200px 高。
 */
private suspend fun dragSidebar(ctx: ActionContext, fingerPx: Int): Int {
    val step = fingerPx.coerceIn(-MAX_ALIGN_SWIPE_PX, MAX_ALIGN_SWIPE_PX)
    if (step == 0) return 0
    val from = if (step > 0) ROW_BAND_BOTTOM - 5 else ROW_BAND_TOP + 5
    swipe(ctx.input, TEAM_COLUMN_X, from, TEAM_COLUMN_X, from - step, steps = SLOW_SWIPE_STEPS)
    ctx.delay(ALIGN_SETTLE)
    return step
}

/**
 * 按名字序列比对两次观测，求**内容位移**（像素，正数=内容上移）。null = 没有重叠可比。
 *
 * 为什么比对连续序列而不是单个名字：队伍名可自定义**且会重复**，单行同名不足以定位；
 * 但连续两行同时重名的概率低得多。这是整个定位不依赖 `TEAMS #N` 的关键 ——
 * 用户把所有队伍都改了名，这条路照样走得通。
 *
 * [expectedPx] 用来消歧。名字序列可能**周期性重复**（比如 40 个队伍轮着用两个名字），
 * 那时"位移 0 行"和"位移 3 行"的重叠看起来完全一样，纯序列比对无解。
 * 所以在所有匹配解里挑物理上最接近预期位移的那个 —— 预期值来自上一次实测的倍率，
 * 只要步长小到让误差不足半个周期，这个选择就是对的。
 */
private fun matchShift(prev: List<SidebarRow>, next: List<SidebarRow>, expectedPx: Int): Int? {
    val candidates = mutableListOf<Int>()
    fun scan(a: List<SidebarRow>, b: List<SidebarRow>, aIsPrev: Boolean) {
        for (shift in a.indices) {
            val tail = a.drop(shift)
            val len = minOf(tail.size, b.size)
            if (len < 2) break
            if ((0 until len).all { tail[it].text == b[it].text }) {
                // 同一物理行在两帧里的 y 之差，恒定写成"prev 的 y 减 next 的 y"
                candidates += if (aIsPrev) tail[0].y - b[0].y else b[0].y - tail[0].y
            }
        }
    }
    scan(prev, next, aIsPrev = true)
    scan(next, prev, aIsPrev = false)
    return candidates.distinct().minByOrNull { kotlin.math.abs(it - expectedPx) }
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
        // 传 settleMillis=0 保留惯性，三下就能从任意位置回到第一项。
        repeat(3) {
            swipe(ctx.input, TEAM_COLUMN_X, ROW_BAND_TOP + 5, TEAM_COLUMN_X, ROW_BAND_BOTTOM, settleMillis = 0)
            ctx.delay(LIST_SETTLE)
        }
        ctx.recognize.dumpFrame("team_list_top", overwrite = true)

        var rows = readSidebar(ctx)
        if (rows.isEmpty()) {
            ctx.log("侧栏读不到任何队伍名，可能不在编队页；放弃选队，沿用当前编队")
            return ActionOutcome.Continue
        }
        // 绝对锚点：滑到顶后首行就是队伍 #1，**与队伍名无关**。
        // 顶部位置被列表夹住，所以首行 y 应当落在 TOP_ROW_Y 附近；差太多说明没真的到顶。
        if (kotlin.math.abs(rows[0].y - TOP_ROW_Y) > TOP_ROW_TOLERANCE) {
            ctx.log("已甩到顶但首行在 y=${rows[0].y}（预期 $TOP_ROW_Y±$TOP_ROW_TOLERANCE），定位可能偏一行")
        }
        // anchorY 是"队伍 anchorNo 那一行当前的 y"，可以是视野外的值 —— 公式只用差值。
        var anchorY = rows[0].y
        var anchorNo = 1
        var ratio = RATIO_INITIAL
        var measured = false
        ctx.log("到顶：首行「${rows[0].text}」@${rows[0].y}，可见 ${rows.size} 行")

        var landedY: Int? = null
        for (round in 1..MAX_ALIGN_ROUNDS) {
            ctx.ensureActive()
            // 免费的精确校准：视野里只要有一行还是默认名，就能把 anchor 钉死。
            rows.firstOrNull { it.teamNo != null }?.let { numbered ->
                val no = requireNotNull(numbered.teamNo)
                val predicted = anchorNo + (numbered.y - anchorY) / TEAM_ROW_HEIGHT
                if (predicted != no) {
                    ctx.log("校准：推算该行是 #$predicted，实为 TEAMS #$no（差 ${no - predicted} 行）")
                }
                anchorY = numbered.y
                anchorNo = no
            }
            val targetY = anchorY + (target - anchorNo) * TEAM_ROW_HEIGHT
            if (targetY in ROW_BAND_TOP..ROW_BAND_BOTTOM) {
                val row = rows.minByOrNull { kotlin.math.abs(it.y - targetY) }
                    ?.takeIf { kotlin.math.abs(it.y - targetY) <= TEAM_ROW_HEIGHT / 2 }
                ctx.log("队伍 #$target 在 y=$targetY（「${row?.text ?: "该行未读到文字"}」），点击")
                click(ctx.input, TEAM_COLUMN_X, targetY)
                landedY = targetY
                break
            }
            if (round == MAX_ALIGN_ROUNDS) {
                ctx.log("对齐 $MAX_ALIGN_ROUNDS 轮仍未把队伍 #$target 带进视野（目标 y=$targetY），放弃选队")
                break
            }
            // 想要的内容位移；夹到"一定留下重叠"的幅度，否则下一轮就没法实测位移了。
            // 首步用更小的 PROBE_ROWS：此时倍率还是初值，而 matchShift 的消歧依赖
            // "预期与实际之差不足半个重名周期"，步子越小这个前提越稳。
            val stepRows = if (measured) OVERLAP_SAFE_ROWS else PROBE_ROWS
            val want = (targetY - ROW_BAND_CENTER)
                .coerceIn(-stepRows * TEAM_ROW_HEIGHT, stepRows * TEAM_ROW_HEIGHT)
            val previous = rows
            val finger = dragSidebar(ctx, (want / ratio).toInt())
            rows = readSidebar(ctx)
            if (rows.isEmpty()) {
                ctx.log("滑动后侧栏读不到内容，放弃选队")
                break
            }
            val moved = matchShift(previous, rows, expectedPx = (finger * ratio).toInt())
            if (moved != null && finger != 0) {
                measured = true
                anchorY -= moved
                val observed = moved.toDouble() / finger
                if (observed in RATIO_MIN..RATIO_MAX) {
                    if (kotlin.math.abs(observed - ratio) > 0.15) {
                        ctx.log("倍率校准：手指 ${finger}px → 内容 ${moved}px，倍率 ${"%.2f".format(observed)}")
                    }
                    ratio = observed
                }
            } else {
                // 没重叠可比：按预期位移推进，靠下一轮的数字锚点或再次比对纠正
                anchorY -= (finger * ratio).toInt()
                ctx.log("两次观测无重叠（手指 ${finger}px），位移按预期值估算")
            }
        }
        ctx.recognize.dumpFrame("team_list_aligned", overwrite = true)
        if (landedY == null) return ActionOutcome.Continue

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
