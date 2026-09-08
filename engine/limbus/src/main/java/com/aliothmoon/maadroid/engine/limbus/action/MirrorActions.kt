package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.click
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.keyPress
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.swipe
import com.aliothmoon.maadroid.engine.limbus.recognize.Crop
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 镜牢（Mirror Dungeon）全部 14 个动作。
 *
 * 对应上游 task_action/mirror.py，是体量最大的一组动作。
 * 每个动作的坐标、识别、流程顺序都严格照抄上游，改动会让流水线行为漂移。
 */
object MirrorActions {

    fun registerAll() {
        ActionRegistry.registerAll(
            "mirror_select_event_effect" to SelectEventEffectAction,
            "mirror_defeat" to DefeatAction,
            "mirror_victory" to VictoryAction,
            "mirror_select_floor_ego_gift" to SelectFloorEgoGiftAction,
            "mirror_select_encounter_reward_card" to SelectEncounterRewardCardAction,
            "mirror_shop_enhance_ego_gifts" to ShopEnhanceEgoGiftsAction,
            "mirror_shop_replace_skill_and_purchase_ego_gifts" to ShopReplacePurchaseAction,
            "mirror_shop_fuse_ego_gifts" to ShopFuseEgoGiftsAction,
            "mirror_shop_heal_sinner" to ShopHealSinnerAction,
            "mirror_select_next_node" to SelectNextNodeAction,
            "mirror_select_theme_pack" to SelectThemePackAction,
            "mirror_gift_search" to GiftSearchAction,
            "mirror_select_initial_ego_gift" to SelectInitialEgoGiftAction,
            "mirror_choose_star" to ChooseStarAction,
        )
    }
}

private val KEYWORD_REFRESH_MAP = mapOf(
    "Burn" to (330 to 290), "Bleed" to (480 to 290), "Tremor" to (630 to 290),
    "Rupture" to (780 to 290), "Sinking" to (930 to 290),
    "Poise" to (330 to 430), "Charge" to (480 to 430), "Slash" to (630 to 430),
    "Pierce" to (780 to 430), "Blunt" to (930 to 430),
)

private val INITIAL_EGO_STYLES = mapOf(
    "Burn" to (200 to 250), "Bleed" to (350 to 250), "Tremor" to (500 to 250),
    "Rupture" to (650 to 250), "Sinking" to (200 to 450),
    "Poise" to (350 to 450), "Charge" to (500 to 450), "Slash" to (650 to 450),
    "Pierce" to (200 to 450), "Blunt" to (350 to 450),
)

private val STAR_POSITIONS = listOf(
    200 to 190, 400 to 190, 600 to 190, 800 to 190, 1000 to 190,
    200 to 410, 400 to 410, 600 to 410, 800 to 410, 1000 to 410,
)

private val REPLACE_SKILL_MAP = mapOf(
    1 to (300 to 330), 2 to (630 to 330), 3 to (960 to 330),
)

/** 连续两次因缺钱失败就停手，照抄上游 —— 再试下去只是白点 */
private const val MAX_MONEY_FAILURES = 2

/** 罪人名在头像下方，要往上偏才点到头像本身 */
private const val REPLACE_NAME_TO_PORTRAIT_DY = 80

/** OCR 认不准的罪人名，只比对前缀 */
private const val OCR_UNSTABLE_SINNER = "Ryoshu"
private const val OCR_UNSTABLE_SINNER_PREFIX = "Ry"

private val SHOP_PURCHASE_PLACES = listOf(
    620 to 270, 780 to 270, 940 to 270, 1100 to 270,
    620 to 420, 780 to 420, 940 to 420, 1100 to 420,
)

private object SelectEventEffectAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("尝试选取事件 buff")
        val choices = ctx.recognize.templateMatch("select_event_effect_choice")
        if (choices.isNotEmpty()) {
            click(ctx.input, choices[0].x, choices[0].y)
            ctx.delay(0.5)
            click(ctx.input, 640, 520)
        }
        return ActionOutcome.Continue
    }
}

private object DefeatAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("镜牢失败结算")
        click(ctx.input, 770, 450)
        ctx.delay(0.5)
        click(ctx.input, 650, 525)

        var guard = 0
        while (guard++ < 30) {
            ctx.ensureActive()
            if (ctx.recognize.templateMatch("defeat").isNotEmpty()) break
            ctx.delay(0.5)
        }

        for (i in 0 until 4) {
            keyPress(ctx.input, "enter")
            ctx.delay(1.0)
        }
        waitConnectingDisappear(ctx)
        for (i in 0 until 3) {
            keyPress(ctx.input, "enter")
            ctx.delay(1.0)
        }

        if (ctx.recognize.templateMatch("exploration_reward").isNotEmpty()) {
            ctx.log("检测到结算失败，启动放弃奖励")
            click(ctx.input, 395, 550)
            ctx.delay(1.0)
            keyPress(ctx.input, "enter")
        }
        return ActionOutcome.Continue
    }
}

private object VictoryAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("处理镜牢胜利结算")
        val acceptReward = ctx.config.bool("mirror", "accept_reward", true)

        if (acceptReward) {
            for (i in 0 until 4) {
                keyPress(ctx.input, "enter")
                ctx.delay(1.0)
            }
            waitConnectingDisappear(ctx)
            for (i in 0 until 3) {
                keyPress(ctx.input, "enter")
                ctx.delay(1.0)
            }
        } else {
            for (i in 0 until 2) {
                keyPress(ctx.input, "enter")
                ctx.delay(1.0)
            }
            click(ctx.input, 395, 550)
            ctx.delay(1.0)
            keyPress(ctx.input, "enter")
        }
        return ActionOutcome.Continue
    }
}

private object SelectFloorEgoGiftAction : ActionBackend {
    /**
     * 跨层选 EGO 饰品。上游这段最长也最讲究，选取优先级不可改：
     * 倾向且未持有 → 可获取且未持有 → 可获取但已持有。
     *
     * 「未持有」的判定是：该文字左侧 200px 内没有 "Owned" —— 边狱把已持有标记
     * 画在饰品名左边，不看这个会重复选已有的饰品。
     *
     * 点击顺序是**倒序**（上游 `filtered_select_orders[::-1]`）：从右往左点，
     * 否则左侧选中后布局会移位，右侧坐标失效。
     */
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("选择楼层 EGO 饰品")
        val cfgType = ctx.node.str("cfg_type") ?: "mirror"
        val cfgIndex = resolveCfgIndex(ctx, cfgType)
        val preferGifts = preferredEgoGifts(ctx, cfgType, cfgIndex)
        val allGiftNames = ctx.templates.namesByTag("ego_gifts")

        val curGifts = ctx.recognize.detectText(Crop(90, 180, 1090, 40))
        val acquireAndOwned = ctx.recognize.detectText(Crop(110, 120, 1090, 60))

        val ownedPositions = acquireAndOwned.filter { it.text == OWNED_MARK }
        fun hasOwnedOnLeft(x: Int) =
            ownedPositions.any { it.x < x && x - it.x <= OWNED_LEFT_RANGE }

        val acquireGifts = acquireAndOwned.filter { ACQUIRE_MARK in it.text }
        val acquireWithoutOwned = acquireGifts.filterNot { hasOwnedOnLeft(it.x) }
        val acquireWithOwned = acquireGifts.filter { hasOwnedOnLeft(it.x) }

        // OCR 出的饰品名先模糊匹配回已知名单，再看是否在倾向名单里
        val preferWithoutOwned = curGifts.mapNotNull { gift ->
            val matched = closestName(gift.text, allGiftNames)
            if (matched == null) {
                ctx.log("识别不出饰品文字: ${gift.text}")
                null
            } else if (matched in preferGifts && !hasOwnedOnLeft(gift.x)) {
                gift
            } else null
        }

        var selectOrders = preferWithoutOwned + acquireWithoutOwned + acquireWithOwned

        if (selectOrders.isEmpty()) {
            // 全都没认出来时退到模板匹配兜底，仍认不出就放弃本次选择
            val fallback = ctx.recognize.templateMatch("acquire_ego_gift")
            if (fallback.isEmpty()) {
                ctx.log("跨层选 EGO 识别异常：连 Acquire E.G.O Gift 模板都认不出")
            } else {
                click(ctx.input, fallback[0].x, fallback[0].y + FLOOR_GIFT_CLICK_DY)
                ctx.delay(0.5)
            }
            click(ctx.input, 1130, 580)
            waitConnectingDisappear(ctx)
            return ActionOutcome.Continue
        }

        for (gift in dedupeByX(selectOrders, threshold = GIFT_COLUMN_WIDTH) { it.x }.reversed()) {
            click(ctx.input, gift.x, gift.y + FLOOR_GIFT_CLICK_DY)
            ctx.delay(0.5)
        }

        click(ctx.input, 1130, 580)
        waitConnectingDisappear(ctx)
        return ActionOutcome.Continue
    }
}

private const val OWNED_MARK = "Owned"
private const val ACQUIRE_MARK = "Acquire E.G.O Gift"

/** 「已持有」标记画在饰品名左侧的距离上限，照抄上游 */
private const val OWNED_LEFT_RANGE = 200

/** 饰品栏列宽，用于同列去重 */
private const val GIFT_COLUMN_WIDTH = 100

/** 文字在饰品框上沿，点击要往下偏到框体内 */
private const val FLOOR_GIFT_CLICK_DY = 20

private object SelectEncounterRewardCardAction : ActionBackend {
    private val rewardCards = listOf(
        "cost_card", "starlight_card", "cost_ego_gift_card",
        "ego_gift_card", "ego_resource_card"
    )

    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("选择奖励卡")
        for (card in rewardCards) {
            val res = ctx.recognize.templateMatch(card)
            if (res.isNotEmpty()) {
                click(ctx.input, res[0].x, res[0].y)
                ctx.log("选择了 $card")
                break
            }
        }
        keyPress(ctx.input, "enter")
        waitConnectingDisappear(ctx)
        return ActionOutcome.Continue
    }
}

private object ShopEnhanceEgoGiftsAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val enableEnhance = ctx.config.bool(
            ctx.node.str("cfg_type") ?: "mirror", "enable_enhance_ego_gifts", true
        )
        if (!enableEnhance) {
            ctx.log("根据配置跳过 EGO 饰品升级")
            click(ctx.input, 1120, 670)
            return ActionOutcome.Continue
        }

        click(ctx.input, 160, 390)
        ctx.delay(1.0)
        if (ctx.recognize.templateMatch("enhance_ego_gift").isEmpty()) {
            ctx.log("无法进入升级区域，放弃饰品升级")
            return ActionOutcome.Continue
        }

        ctx.log("强化 EGO 饰品")
        val cfgType = ctx.node.str("cfg_type") ?: "mirror"
        val cfgIndex = resolveCfgIndex(ctx, cfgType)
        val preferGifts = preferredEgoGifts(ctx, cfgType, cfgIndex)
        val allGiftNames = ctx.templates.namesByTag("ego_gifts")
        var needMoreMoney = 0

        val detectPlaces = intArrayOf(590, 180, 560, 350)
        var firstPage = true

        while (true) {
            ctx.ensureActive()
            enhanceCurrentGift(ctx, preferGifts, allGiftNames) { needMoreMoney++ }

            val corners = ctx.recognize.preciseTemplateMatch(
                "mirror_shop_ego_gift_corner_unselect",
                crop = Crop(detectPlaces[0], detectPlaces[1], detectPlaces[2], detectPlaces[3])
            )
            for (m in corners) {
                click(ctx.input, m.x + 20, m.y - 20)
                enhanceCurrentGift(ctx, preferGifts, allGiftNames) { needMoreMoney++ }
                if (needMoreMoney >= MAX_MONEY_FAILURES) break
            }

            if (needMoreMoney >= MAX_MONEY_FAILURES) break

            val slider = ctx.recognize.templateMatch("slider", crop = Crop(1080, 190, 80, 360))
            if (slider.isNotEmpty() && slider[0].y < 440) {
                swipe(ctx.input, 815, 395, 815, 290)
                ctx.delay(1.0)
                if (firstPage) {
                    firstPage = false
                    detectPlaces[1] += 210
                    detectPlaces[3] -= 210
                }
            } else break
        }

        click(ctx.input, 500, 590)
        ctx.delay(1.0)
        click(ctx.input, 1120, 670)
        return ActionOutcome.Continue
    }

    private suspend fun enhanceCurrentGift(
        ctx: ActionContext,
        preferGifts: Set<String>,
        allGiftNames: List<String>,
        onNeedMoney: () -> Unit,
    ) {
        val curGift = ctx.recognize.detectText(Crop(280, 150, 300, 130))
        if (curGift.isEmpty()) {
            ctx.log("饰品升级区域识别不出文字")
            return
        }
        val matched = closestName(curGift[0].text, allGiftNames) ?: return
        if (matched !in preferGifts) return

        keyPress(ctx.input, "enter")
        ctx.delay(1.0)
        if (ctx.recognize.templateMatch("more_cost_to_enhance_this_ego_gift").isNotEmpty()) {
            ctx.log("缺钱不能升级")
            click(ctx.input, 500, 590)
            onNeedMoney()
            return
        }
        if (ctx.recognize.templateMatch("cost_to_enhance_this_ego_gift").isEmpty()) return

        keyPress(ctx.input, "enter")
        waitConnectingDisappear(ctx)
        ctx.delay(0.5)

        keyPress(ctx.input, "enter")
        ctx.delay(1.0)
        if (ctx.recognize.templateMatch("more_cost_to_enhance_this_ego_gift").isNotEmpty()) {
            click(ctx.input, 500, 590)
            onNeedMoney()
            return
        }
        if (ctx.recognize.templateMatch("cost_to_enhance_this_ego_gift").isEmpty()) return

        keyPress(ctx.input, "enter")
        waitConnectingDisappear(ctx)
        ctx.delay(0.5)
    }
}

private object ShopReplacePurchaseAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val enableReplacePurchase = ctx.config.bool(
            ctx.node.str("cfg_type") ?: "mirror",
            "enable_replace_skill_purchase_ego_gifts", true,
        )
        if (!enableReplacePurchase) {
            ctx.log("根据配置跳过技能替换和 EGO 饰品购买")
            return ActionOutcome.Continue
        }

        ctx.log("替换技能并购买 EGO 饰品")
        val cfgType = ctx.node.str("cfg_type") ?: "mirror"
        val cfgIndex = resolveCfgIndex(ctx, cfgType)
        val preferGifts = preferredEgoGifts(ctx, cfgType, cfgIndex)
        val allGiftNames = ctx.templates.namesByTag("ego_gifts")
        val leftMoneyForEnhance = ctx.config.int(cfgType, "mirror_stop_purchase_gift_money", 0)
        val preferStyles = ctx.config.listAt(cfgType, "mirror_team_ego_gift_styles", cfgIndex)
        val teamStyle = ctx.config.strAt(cfgType, "mirror_team_styles", cfgIndex, "Burn")
        val replaceMap = ctx.config.rawAt(cfgType, "mirror_replace_skill", cfgIndex) as? JsonObject

        var loopCount = 0
        while (true) {
            ctx.ensureActive()

            val isPurchased = ctx.recognize.templateMatch(
                "shop_purchased", crop = Crop(550, 200, 140, 50)
            ).isNotEmpty()

            if (!isPurchased) {
                if (tryReplaceSkill(ctx, replaceMap)) continue
            }

            val canPurchase = purchaseEgoGifts(ctx, preferGifts, allGiftNames)

            val moneyOcr = ctx.recognize.detectText(Crop(568, 100, 100, 80))
            val curMoney = moneyOcr.firstOrNull()?.text?.trim()?.toIntOrNull() ?: 0

            if (!canPurchase || curMoney <= leftMoneyForEnhance) break

            click(ctx.input, 1140, 120)
            ctx.delay(1.0)
            // 有配倾向体系就按轮次轮换刷新关键词，没配就固定用队伍主体系
            val style = if (preferStyles.isNotEmpty()) {
                preferStyles[loopCount % preferStyles.size]
            } else teamStyle
            val refreshPos = KEYWORD_REFRESH_MAP[style]
            if (refreshPos != null) click(ctx.input, refreshPos.first, refreshPos.second)
            ctx.delay(0.5)
            click(ctx.input, 780, 570)
            waitConnectingDisappear(ctx)
            ctx.delay(1.0)
            loopCount++
        }
        return ActionOutcome.Continue
    }

    /**
     * 技能替换。
     *
     * 配置形状是「罪人名 → 技能槽顺序」的字典，例如 `{"Faust": [3,2,1]}`。
     * 两处上游细节不可改：
     * - 点击顺序要把配置**反序**（上游 `skill_order[::-1]`）—— 界面上后点的排在前面
     * - Ryoshu 只用前两个字母 "Ry" 比对：OCR 常把这个名字后半截认错
     */
    private suspend fun tryReplaceSkill(ctx: ActionContext, replaceMap: JsonObject?): Boolean {
        if (replaceMap.isNullOrEmpty()) return false

        val nameOcr = ctx.recognize.detectText(Crop(535, 320, 165, 50))
        if (nameOcr.isEmpty()) {
            ctx.log("替换技能的罪人名识别异常，跳过技能替换")
            return false
        }
        val detected = nameOcr[0].text

        for ((sinner, slotsJson) in replaceMap) {
            val probe = if (sinner == OCR_UNSTABLE_SINNER) OCR_UNSTABLE_SINNER_PREFIX else sinner
            if (probe !in detected) continue

            val slots = runCatching {
                slotsJson.jsonArray.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
            }.getOrDefault(emptyList())
            if (slots.isEmpty()) continue

            ctx.log("罪人 $sinner 可做技能替换，顺序 $slots")
            click(ctx.input, nameOcr[0].x, nameOcr[0].y - REPLACE_NAME_TO_PORTRAIT_DY)
            ctx.delay(1.0)

            for (slot in slots.reversed()) {
                REPLACE_SKILL_MAP[slot]?.let { click(ctx.input, it.first, it.second) }
            }
            ctx.delay(1.0)
            click(ctx.input, 790, 535)
            ctx.delay(1.0)
            click(ctx.input, 790, 535)
            waitConnectingDisappear(ctx)
            return true
        }
        return false
    }

    private suspend fun purchaseEgoGifts(
        ctx: ActionContext,
        preferGifts: Set<String>,
        allGiftNames: List<String>,
    ): Boolean {
        val gifts = ctx.recognize.detectText(Crop(535, 335, 650, 30))
            .sortedBy { it.x }
            .toMutableList()
        val otherLine = ctx.recognize.detectText(Crop(535, 490, 650, 30))
            .sortedBy { it.x }
        gifts.addAll(otherLine)

        val purchasedList = ctx.recognize.detectText(Crop(535, 220, 650, 20)).toMutableList()
        purchasedList.addAll(ctx.recognize.detectText(Crop(535, 375, 650, 20)))

        if (gifts.size == purchasedList.size) return false

        var purchasedThisTurn = 0
        for ((idx, gift) in gifts.withIndex()) {
            val matched = closestName(gift.text, allGiftNames) ?: continue
            if (matched in preferGifts) {
                val adjustedIdx = idx - purchasedThisTurn
                if (adjustedIdx < SHOP_PURCHASE_PLACES.size) {
                    click(ctx.input, SHOP_PURCHASE_PLACES[adjustedIdx].first, SHOP_PURCHASE_PLACES[adjustedIdx].second)
                    ctx.delay(1.0)
                    click(ctx.input, 740, 480)
                    ctx.delay(0.5)
                    waitConnectingDisappear(ctx)
                    ctx.delay(0.5)
                    click(ctx.input, 650, 535)
                    ctx.delay(1.0)
                    purchasedThisTurn++
                }
            }
        }
        return true
    }
}

private object ShopFuseEgoGiftsAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val cfgType = ctx.node.str("cfg_type") ?: "mirror"
        val enableFuse = ctx.config.bool(cfgType, "enable_fuse_ego_gifts", true)
        if (!enableFuse) {
            ctx.log("根据配置跳过 EGO 饰品融合")
            return ActionOutcome.Continue
        }

        ctx.log("融合 EGO 饰品")
        click(ctx.input, 280, 390)
        ctx.delay(1.0)
        if (ctx.recognize.templateMatch("fuse_ego_gift").isEmpty()) return ActionOutcome.Continue

        while (ctx.recognize.templateMatch("fusion_keyword_selection").isEmpty()) {
            ctx.ensureActive()
            click(ctx.input, 850, 350)
            ctx.delay(1.0)
        }

        val cfgIndex = resolveCfgIndex(ctx, cfgType)
        val style = ctx.config.strAt(cfgType, "mirror_team_styles", cfgIndex, "Burn")
        val fusePos = KEYWORD_REFRESH_MAP[style]
        if (fusePos != null) click(ctx.input, fusePos.first, fusePos.second)
        ctx.delay(0.5)
        click(ctx.input, 780, 570)
        ctx.delay(1.0)

        val uselessGifts = uselessEgoGifts(ctx, cfgType, cfgIndex)
        val detectPlaces = intArrayOf(590, 180, 560, 350)
        var firstPage = true

        while (true) {
            ctx.ensureActive()
            var canFuse = false

            for (giftName in uselessGifts) {
                val res = ctx.recognize.templateMatch(
                    giftName, threshold = 0.88,
                    crop = Crop(detectPlaces[0], detectPlaces[1], detectPlaces[2], detectPlaces[3])
                )
                if (res.isNotEmpty()) {
                    click(ctx.input, res[0].x, res[0].y)
                    ctx.delay(1.0)
                    if (ctx.recognize.templateMatch(
                            "empty_fuse_gift_place", crop = Crop(140, 270, 150, 150)
                        ).isEmpty()
                    ) {
                        canFuse = true
                        break
                    }
                }
            }

            if (canFuse) {
                click(ctx.input, 785, 590)
                ctx.delay(1.0)
                click(ctx.input, 785, 590)
                ctx.delay(0.5)
                waitConnectingDisappear(ctx)
                ctx.delay(1.0)
                keyPress(ctx.input, "enter")
                continue
            }

            val slider = ctx.recognize.templateMatch("slider", crop = Crop(1080, 190, 80, 360))
            if (slider.isNotEmpty() && slider[0].y < 440) {
                swipe(ctx.input, 815, 395, 815, 290)
                ctx.delay(1.0)
                if (firstPage) {
                    firstPage = false
                    detectPlaces[1] += 210
                    detectPlaces[3] -= 210
                }
            } else break
        }

        click(ctx.input, 500, 590)
        return ActionOutcome.Continue
    }
}

private object ShopHealSinnerAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("镜牢商店治疗罪人")
        val moneyOcr = ctx.recognize.detectText(Crop(568, 100, 100, 80))
        val curMoney = moneyOcr.firstOrNull()?.text?.trim()?.toIntOrNull() ?: 0
        if (curMoney <= 100) return ActionOutcome.Continue

        val cfgType = ctx.node.str("cfg_type") ?: "mirror"
        val shouldHeal = ctx.config.boolAt(
            cfgType, "mirror_shop_heal", resolveCfgIndex(ctx, cfgType), true
        )
        if (shouldHeal) {
            click(ctx.input, 200, 470)
            ctx.delay(1.0)
            click(ctx.input, 1020, 330)
            ctx.delay(0.5)
            waitConnectingDisappear(ctx)
            ctx.delay(0.5)
            click(ctx.input, 1120, 650)
            ctx.delay(1.0)
        } else {
            ctx.log("根据设置跳过镜牢商店治疗")
        }
        return ActionOutcome.Continue
    }
}

private object SelectNextNodeAction : ActionBackend {
    private val threePositions = listOf(710 to 110, 710 to 330, 710 to 540)

    /**
     * 选择镜牢的下一个节点，按上游的**带权寻路**择优。
     *
     * 两个分类器配合：`mirror_legend` 认出九宫格里六个节点各是什么类型，
     * `mirror_path` 认出三条路径分别连到哪些节点（9 个连接位的多标签）。
     * 连接名形如 `"01"`：首位是路径号 0/1/2，其后每位是该列选第几个节点。
     *
     * 打分照抄上游：沿一条连接把途经节点的权重累加，每条路径取其最高分的连接，
     * 再按分数从高到低依次尝试进入。权重来自用户配置的 `node_scores`
     * （事件 20 / 普通战斗 9 / 精英 2 / …），空节点固定 -100 —— 上游在读完配置后
     * **强制覆盖**这一项，防止用户把空节点配成正分而走进死路。
     *
     * 保留的上游语义（丢了会卡死）：
     * - 车头偏上时先下滑一次，否则九宫格上沿被裁掉
     * - 每次点击后等连接框消失，再看有没有 `node_enter` 才按回车
     * - 空节点直接跳过，不浪费一次点击
     * - 三条路都进不去就点车头自身；仍不行则回主页重开而非原地重试
     */
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("选择下一个镜牢节点")

        val trainHead = ctx.recognize.templateMatch("train_head")
        if (trainHead.isNotEmpty() && trainHead[0].y < TRAIN_HEAD_TOO_HIGH_Y) {
            swipe(ctx.input, 460, 270, 460, 340)
        }

        val nodeTypes = ctx.recognize.classify("mirror_legend", emptyList())
        val connections = ctx.recognize.classifyMultiLabel("mirror_path", emptyList())
            .firstOrNull()
            .orEmpty()

        val ordered = rankPaths(ctx, nodeTypes, connections)
        if (ordered.isEmpty()) {
            ctx.log("寻路识别不可用，按上到下依次尝试")
        }
        val candidates = ordered.ifEmpty { threePositions.indices.toList() }

        var entered = false
        for (pathId in candidates) {
            ctx.ensureActive()
            // 该路径首个节点是空的就没必要点
            if (nodeTypes.getOrNull(pathId) == NODE_EMPTY) {
                ctx.log("第 ${pathId + 1} 条路径为空，跳过")
                continue
            }
            val pos = threePositions.getOrNull(pathId) ?: continue
            click(ctx.input, pos.first, pos.second)
            waitConnectingDisappear(ctx)
            ctx.delay(1.0)
            if (ctx.recognize.templateMatch("node_enter").isNotEmpty()) {
                keyPress(ctx.input, "enter")
                entered = true
                break
            }
        }

        if (!entered) {
            // 三条路都没进去，可能当前就站在车头上
            val head = ctx.recognize.templateMatch("train_head")
            if (head.isNotEmpty()) {
                click(ctx.input, head[0].x, head[0].y)
                ctx.delay(1.0)
                if (ctx.recognize.templateMatch("node_enter").isNotEmpty()) {
                    keyPress(ctx.input, "enter")
                    entered = true
                }
            }
        }

        if (!entered) {
            ctx.log("镜牢寻路异常，返回主页重开")
            return ActionOutcome.Goto("back_to_init_page")
        }
        return ActionOutcome.Continue
    }

    /**
     * 按累计权重给三条路径排序，返回路径号（高分在前）。
     * 识别结果不可用时返回空表，由调用方退回依次尝试。
     */
    internal fun rankPaths(
        ctx: ActionContext,
        nodeTypes: List<String>,
        connections: List<String>,
    ): List<Int> {
        if (nodeTypes.isEmpty() || connections.isEmpty()) return emptyList()
        val scores = nodeScores(ctx)

        // 每条路径只保留其最高分的连接
        val best = HashMap<Int, Int>()
        for (conn in connections) {
            val pathId = conn.firstOrNull()?.digitToIntOrNull() ?: continue
            if (pathId !in threePositions.indices) continue
            var weight = 0
            for ((column, ch) in conn.withIndex()) {
                val pick = ch.digitToIntOrNull() ?: continue
                // 上游：node_type[column * 3 + pick]
                val nodeIndex = column * NODES_PER_COLUMN + pick
                val type = nodeTypes.getOrNull(nodeIndex) ?: continue
                weight += scores[type] ?: 0
            }
            val prev = best[pathId]
            if (prev == null || weight > prev) best[pathId] = weight
        }
        if (best.isEmpty()) return emptyList()
        ctx.log("寻路打分: $best（节点 $nodeTypes，连接 $connections）")
        return best.entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * 节点权重。用户可经 `node_scores` 覆盖，但空节点固定 -100 ——
     * 上游读完配置后强制覆盖该项，否则用户把它配成正分就会一直走进死路。
     */
    private fun nodeScores(ctx: ActionContext): Map<String, Int> {
        val defaults = mapOf(
            "node_event" to 20,
            "node_regular_encounter" to 9,
            "node_elite_encounter" to 2,
            "node_focused_encounter" to 1,
            "node_abnormality_encounter" to 0,
            "node_shop" to 0,
            "node_boss_encounter" to 0,
            "train_head" to 0,
        )
        val merged = defaults.mapValues { (k, v) -> ctx.config.int("mirror", "node_score_$k", v) }
        return merged + (NODE_EMPTY to EMPTY_NODE_SCORE)
    }
}

/** 空节点的权重固定值，用户配置不可覆盖 */
private const val EMPTY_NODE_SCORE = -100
private const val NODE_EMPTY = "node_empty"

/** 九宫格每列的节点数，连接名的每位在该列内选一个 */
private const val NODES_PER_COLUMN = 3

/** 车头 y 小于此值说明九宫格上沿被裁，要先下滑 */
private const val TRAIN_HEAD_TOO_HIGH_Y = 300

private object SelectThemePackAction : ActionBackend {
    /**
     * 选择镜牢主题卡包。
     *
     * 上游的取舍顺序照抄如下，改了会让用户配的权重失效：
     * 1. 模式不符先切换（配普通却开着困难，或反之），切完重新识别
     * 2. 有**未探索**的卡包就优先选它（首次探索有额外奖励）
     * 3. 按用户配置的权重挑最高的；权重 > 10 视为「够好了」立刻停手
     * 4. 一轮都没挑到高权重的，**刷新一次**再挑；刷新后无论如何都要定下来
     * 5. 全认不出来就退到 `theme_pack_detail` 随机选一个
     *
     * 选中的方式是**向下拖 400px**（上游 swipe），不是点击 —— 卡包要拖到下方槽位。
     */
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("选择镜牢主题包")

        val weights = themePackWeights(ctx)
        val mirrorMode = ctx.config.str("mirror", "mirror_mode", "normal")

        var bestName: String? = null
        var bestPos: com.aliothmoon.maadroid.engine.limbus.recognize.Match? = null
        var refreshed = false
        var guard = 0

        while (guard++ < THEME_PACK_MAX_ROUNDS) {
            ctx.ensureActive()

            // 1. 模式不符先切
            val wrongMode = if (mirrorMode == "normal") "hard_mode" else "normal_mode"
            if (ctx.recognize.templateMatch(wrongMode).isNotEmpty()) {
                click(ctx.input, 905, 50)
                ctx.log("镜牢模式与配置不符，正在切换")
                ctx.delay(5.0)
                continue
            }

            // 2. 未探索的卡包优先
            val newPack = ctx.recognize.templateMatch("mirror_theme_pack_new")
            if (newPack.isNotEmpty()) {
                ctx.log("检测到未探索的卡包，优先探索")
                bestPos = newPack[0]
                bestName = "未探索卡包"
                break
            }

            // 3. 按权重挑
            var stop = false
            for ((name, weight) in weights) {
                val hit = ctx.recognize.templateMatch(
                    name, maskTemplate = Crop(20, 20, 130, 210)
                )
                if (hit.isEmpty()) continue
                ctx.log("检测到卡包 $name，权重 $weight")
                val bestWeight = bestName?.let { weights[it] } ?: Int.MIN_VALUE
                if (bestPos == null || weight > bestWeight) {
                    bestPos = hit[0]
                    bestName = name
                    if (weight > THEME_PACK_GOOD_ENOUGH_WEIGHT) stop = true
                }
            }

            if (stop || refreshed) break

            // 4. 没挑到够好的，刷新一次
            ctx.log("没有权重高于 $THEME_PACK_GOOD_ENOUGH_WEIGHT 的卡包，刷新一次")
            refreshed = true
            bestName = null
            bestPos = null
            click(ctx.input, 1080, 50)
            ctx.delay(0.5)
            waitConnectingDisappear(ctx)
            ctx.delay(3.0)
        }

        // 5. 全认不出就随机
        if (bestPos == null) {
            ctx.log("没有检测到已知主题包，尝试随机选择")
            bestPos = ctx.recognize.templateMatch("theme_pack_detail").firstOrNull()
        }

        val target = bestPos
        if (target == null) {
            ctx.log("主题包检测异常，跳过选择")
            return ActionOutcome.Continue
        }

        ctx.log("最终选择卡包: ${bestName ?: "随机"}")
        swipe(ctx.input, target.x, target.y, target.x, target.y + THEME_PACK_DRAG_DY)
        return ActionOutcome.Continue
    }

    /** 用户配的卡包权重，按权重降序 —— 遍历顺序决定同权重时先选谁 */
    private fun themePackWeights(ctx: ActionContext): Map<String, Int> {
        val names = ctx.config.list("theme_pack", "names")
        return names
            .associateWith { ctx.config.int("theme_pack", "weight_$it", 0) }
            .toList()
            .sortedByDescending { it.second }
            .toMap()
    }
}

/** 权重高于此值就不再刷新，照抄上游的基准值 */
private const val THEME_PACK_GOOD_ENOUGH_WEIGHT = 10

/** 卡包要向下拖这么远才落进槽位 */
private const val THEME_PACK_DRAG_DY = 400

/** 模式切换与刷新都会 continue 重来，给个上限避免识别持续异常时空转 */
private const val THEME_PACK_MAX_ROUNDS = 12

private object GiftSearchAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("搜索镜牢礼物（暂时拒绝）")
        click(ctx.input, 900, 600)
        ctx.delay(1.0)
        keyPress(ctx.input, "enter")
        return ActionOutcome.Continue
    }
}

private object SelectInitialEgoGiftAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("选择初始 EGO 饰品")
        val cfgType = ctx.node.str("cfg_type") ?: "mirror"
        val cfgIndex = resolveCfgIndex(ctx, cfgType)
        val style = ctx.config.strAt(cfgType, "mirror_team_styles", cfgIndex, "Burn")
        val pos = INITIAL_EGO_STYLES[style] ?: INITIAL_EGO_STYLES.getValue("Burn")

        if (style == "Pierce" || style == "Blunt") {
            swipe(ctx.input, 430, 470, 430, 240)
            ctx.delay(0.5)
        }
        click(ctx.input, pos.first, pos.second)
        ctx.delay(0.5)

        val orders = ctx.config.listAt(cfgType, "mirror_team_initial_ego_orders", cfgIndex)
        val orderPositions = mapOf(1 to (830 to 270), 2 to (830 to 370), 3 to (830 to 470))
        for (order in orders) {
            val idx = order.toIntOrNull() ?: continue
            val clickPos = orderPositions[idx] ?: continue
            click(ctx.input, clickPos.first, clickPos.second)
            ctx.delay(0.5)
        }

        for (i in 0 until 4) {
            keyPress(ctx.input, "enter")
            ctx.delay(0.5)
            waitConnectingDisappear(ctx)
        }
        return ActionOutcome.Continue
    }
}

private object ChooseStarAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("选择镜牢星光")
        val cfgType = ctx.node.str("cfg_type") ?: "mirror"
        val stars = ctx.config.listAt(
            cfgType, "mirror_team_stars", resolveCfgIndex(ctx, cfgType)
        )

        for (starStr in stars) {
            if (starStr.isEmpty()) continue
            val starIndex = starStr[0].digitToIntOrNull() ?: continue
            if (starIndex >= STAR_POSITIONS.size) continue

            val origin = STAR_POSITIONS[starIndex]
            click(ctx.input, origin.first, origin.second)
            ctx.delay(0.5)

            when (starStr.length) {
                1 -> Unit
                2 -> {
                    click(ctx.input, origin.first, origin.second + 150)
                    ctx.delay(0.5)
                }
                3 -> {
                    click(ctx.input, origin.first + 80, origin.second + 150)
                    ctx.delay(0.5)
                }
            }
        }

        click(ctx.input, 1190, 670)
        ctx.delay(2.0)
        click(ctx.input, 735, 535)
        return ActionOutcome.Continue
    }
}
