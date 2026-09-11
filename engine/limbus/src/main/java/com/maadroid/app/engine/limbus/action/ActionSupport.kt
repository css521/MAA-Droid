package com.maadroid.app.engine.limbus.action

import kotlin.math.abs

/**
 * 动作实现共用的小工具。
 *
 * 抽出来的都是上游反复出现、且**改了会静默出错**的片段：等连接框消失、
 * 队伍轮换下标、按 x 去重。放在一处便于单测直接验证。
 */

/**
 * 等「连接中」提示消失。
 *
 * 上游写作 `self.exec_wait_disappear(get_task("wait_connecting_disappear"))`，
 * 在 mirror/utils 里出现 14 次 —— 每次网络请求后都要等，漏一次就会点到还没刷新的界面。
 *
 * 与上游的差别：上游是无上限 `while`，网络卡死时会永久挂住；这里给 [maxWaitSec]
 * 上限后放行，让后续节点的识别去决定怎么走，而不是让用户对着不动的界面干等。
 */
internal suspend fun waitConnectingDisappear(ctx: ActionContext, maxWaitSec: Double = 60.0) {
    var waited = 0.0
    while (waited < maxWaitSec) {
        ctx.ensureActive()
        if (ctx.recognize.templateMatch(TEMPLATE_CONNECTING).isEmpty()) return
        ctx.delay(POLL_INTERVAL_SEC)
        waited += POLL_INTERVAL_SEC
    }
    ctx.log("等待「连接中」消失超过 ${maxWaitSec.toInt()} 秒，放行后续识别")
}

private const val TEMPLATE_CONNECTING = "connecting"
private const val POLL_INTERVAL_SEC = 1.0

/**
 * 解析 "11/12" 形态的计数，返回斜杠左侧的数字。
 *
 * 上游在充值脑啡肽与整队人数两处都靠它；OCR 认错时上游取默认值继续，不中断流程。
 */
internal fun parseSlashCount(text: String?): Int? {
    val idx = text?.indexOf('/') ?: return null
    if (idx <= 0) return null
    return text.substring(0, idx).trim().toIntOrNull()
}

/** 解析 "11/12" 右侧的总数 */
internal fun parseSlashTotal(text: String?): Int? {
    val idx = text?.indexOf('/') ?: return null
    if (idx < 0 || idx == text.length - 1) return null
    return text.substring(idx + 1).trim().toIntOrNull()
}

/**
 * 队伍轮换下标，对齐上游 `_get_using_cfg_index`：
 * `get_task("${cfgType}_check").execute_count % len(cfg["team_orders"])`。
 *
 * 语义要点：模的是 **team_orders 的组数**，计数来自对应的 `*_check` 节点。
 * 这样连刷多轮时会依次用完每套队伍，而不是每轮都用第一套。
 */
internal fun resolveCfgIndex(ctx: ActionContext, cfgType: String): Int {
    val groups = ctx.config.groupCount(cfgType, "team_orders")
    if (groups <= 0) return 0
    return ctx.counterOf("${cfgType}_check") % groups
}

/**
 * 按 x 坐标去重，保留先出现的。
 *
 * 上游 `mirror_select_floor_ego_gift` 用它把 OCR 与模板的多路结果合并成
 * 「每列最多点一次」—— 阈值 100 是饰品栏的列宽，改动会导致同一列被点两次。
 */
internal fun <T> dedupeByX(items: List<T>, threshold: Int, xOf: (T) -> Int): List<T> {
    val kept = ArrayList<T>(items.size)
    for (item in items) {
        if (kept.none { abs(xOf(item) - xOf(it)) < threshold }) kept += item
    }
    return kept
}

/**
 * 倾向饰品名单：体系展开 + 白名单 - 黑名单。
 *
 * 上游在 `mirror_select_floor_ego_gift`、`mirror_shop_enhance_ego_gifts`、
 * `mirror_shop_replace_skill_and_purchase_ego_gifts` 三处逐字重复同一段，抽出为一处。
 *
 * 顺序不可调整：**黑名单最后生效**，否则用户拉黑的饰品会因为同属倾向体系而被买回来。
 */
internal fun preferredEgoGifts(ctx: ActionContext, cfgType: String, cfgIndex: Int): Set<String> {
    val styles = ctx.config.listAt(cfgType, "mirror_team_ego_gift_styles", cfgIndex)
    val prefer = LinkedHashSet<String>()
    for (style in styles) {
        prefer += ctx.templates.namesByTag("ego_gifts_$style")
    }
    prefer += ctx.config.listAt(cfgType, "mirror_team_ego_allow_list", cfgIndex)
    prefer -= ctx.config.listAt(cfgType, "mirror_team_ego_block_list", cfgIndex).toSet()
    return prefer
}

/**
 * 无用饰品名单：全部饰品 - 倾向饰品。用于商店融合（把不要的三个合成一个）。
 * 对应上游 `mirror_shop_fuse_ego_gifts` 里的 `useless_gifts`。
 */
internal fun uselessEgoGifts(ctx: ActionContext, cfgType: String, cfgIndex: Int): Set<String> {
    val all = LinkedHashSet(ctx.templates.namesByTag("ego_gifts"))
    all -= preferredEgoGifts(ctx, cfgType, cfgIndex)
    return all
}

/**
 * 模糊匹配 OCR 文本到已知名单，对应上游的
 * `difflib.get_close_matches(text, names, cutoff=0.8)` 取第一个。
 *
 * OCR 认不准饰品名是常态（上游对此有大量 warning 分支），所以匹配必须容错；
 * cutoff 0.8 照抄上游 —— 调低会把不同饰品认成同一个。
 */
internal fun closestName(text: String, candidates: Collection<String>, cutoff: Double = 0.8): String? {
    var best: String? = null
    var bestScore = cutoff
    for (c in candidates) {
        val score = similarity(text, c)
        if (score >= bestScore) {
            bestScore = score
            best = c
        }
    }
    return best
}

internal fun localizedName(config: LimbusConfig, name: String): String {
    val translated = config.str("language", name, name)
    // 上游罪人资源名是 RyoShu，前端队伍配置使用 Ryoshu。
    return if (translated == name && name.equals("Ryoshu", ignoreCase = true))
        config.str("language", "RyoShu", name) else translated
}

/** 在显示语言中比较 OCR，返回原始资源标识，保持流派和黑白名单比较不变。 */
internal fun closestLocalizedName(text: String, candidates: Collection<String>, config: LimbusConfig): String? {
    val localized = candidates.associateBy { localizedName(config, it) }
    val hit = closestName(text, localized.keys) ?: return null
    return localized[hit]
}

/**
 * Python `difflib.SequenceMatcher(None, a, b).ratio()` 的等价实现：
 * `2 * M / T`，M 为匹配字符总数，T 为两串长度和。
 *
 * 不用编辑距离代替 —— 两者给出的比值不同，会让 0.8 这个阈值失去上游的含义。
 */
internal fun similarity(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    val total = a.length + b.length
    if (total == 0) return 0.0
    return 2.0 * matchingBlocks(a, b) / total
}

/** SequenceMatcher 的递归最长公共子串分解，与 CPython difflib 同构 */
private fun matchingBlocks(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    var bestI = 0
    var bestJ = 0
    var bestSize = 0
    // 逐行 DP：只保留上一行，长度受 OCR 文本约束（几十字符），足够快
    var prev = IntArray(b.length + 1)
    for (i in a.indices) {
        val cur = IntArray(b.length + 1)
        for (j in b.indices) {
            if (a[i] == b[j]) {
                val len = prev[j] + 1
                cur[j + 1] = len
                if (len > bestSize) {
                    bestSize = len
                    bestI = i - len + 1
                    bestJ = j - len + 1
                }
            }
        }
        prev = cur
    }
    if (bestSize == 0) return 0
    return bestSize +
            matchingBlocks(a.substring(0, bestI), b.substring(0, bestJ)) +
            matchingBlocks(a.substring(bestI + bestSize), b.substring(bestJ + bestSize))
}
