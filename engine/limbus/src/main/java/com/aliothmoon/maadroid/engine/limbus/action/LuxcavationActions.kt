package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.click
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.swipe
import com.aliothmoon.maadroid.engine.limbus.recognize.Crop

/**
 * 经验/纺锤副本：exp_select_stage / thread_select_stage。
 *
 * 对应上游 task_action/luxcavation.py。
 */
object LuxcavationActions {

    fun registerAll() {
        ActionRegistry.registerAll(
            "exp_select_stage" to ExpSelectStageAction,
            "thread_select_stage" to ThreadSelectStageAction,
        )
    }
}

private object ExpSelectStageAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("选择经验副本关卡")
        val targetStage = ctx.config.str("exp", "exp_stage", "")
        val mode = ctx.config.str("exp", "luxcavation_mode", "enter")
        validateSelection("经验", targetStage, mode)?.let { return it }

        var pos = ctx.recognize.findExpStage(targetStage)
        var cnt = 0
        while (pos.isEmpty()) {
            ctx.ensureActive()
            swipe(ctx.input, 590, 310, 940, 310)
            ctx.delay(0.6)
            pos = ctx.recognize.findExpStage(targetStage)
            if (++cnt > 5) {
                ctx.log("选不到 Lv$targetStage 的经验副本关卡")
                return ActionOutcome.Finish(false, "找不到经验副本 $targetStage")
            }
        }

        val enterX = pos[0].x + 10
        ctx.log("[exp_select_stage] 模式=$mode，点击 $enterX,${if (mode == "enter") 480 else 515}")
        when (mode) {
            "enter" -> click(ctx.input, enterX, 480)
            "skip battle" -> click(ctx.input, enterX, 515)
        }
        return awaitSelectionPage(ctx, "exp", mode)
    }
}

private object ThreadSelectStageAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("选择 Thread 副本关卡")
        val targetStage = ctx.config.str("thread", "thread_stage", "")
        val mode = ctx.config.str("thread", "luxcavation_mode", "enter")
        validateSelection("纺锤", targetStage, mode)?.let { return it }

        click(ctx.input, 140, 330)
        ctx.delay(1.0)

        when (mode) {
            "enter" -> click(ctx.input, 370, 480)
            "skip battle" -> click(ctx.input, 370, 515)
        }
        ctx.delay(1.0)

        var pos = ctx.recognize.findText(targetStage, crop = Crop(610, 170, 90, 400))
        var cnt = 0
        while (pos.isEmpty()) {
            ctx.ensureActive()
            swipe(ctx.input, 650, 325, 650, 430)
            ctx.delay(0.6)
            pos = ctx.recognize.findText(targetStage, crop = Crop(610, 170, 90, 400))
            if (++cnt > 5) {
                ctx.log("选不到 Lv$targetStage 的 Thread 副本关卡")
                return ActionOutcome.Finish(false, "找不到 Thread 副本 $targetStage")
            }
        }

        click(ctx.input, pos[0].x, pos[0].y)
        ctx.log("[thread_select_stage] 模式=$mode，关卡=$targetStage")
        return awaitSelectionPage(ctx, "thread", mode)
    }
}

/** 不重复点击入口；等上游 next 所需的页面出现，避免转场中直接落入无条件错误节点。 */
private suspend fun awaitSelectionPage(ctx: ActionContext, section: String, mode: String): ActionOutcome {
    // 仅适配已知的 LALC 选队/跳过分支；动作被单独使用或上游更换路线时保留其原语义。
    if ("${section}_choose_team" !in ctx.node.next || "${section}_skip_battle" !in ctx.node.next) {
        return ActionOutcome.Continue
    }
    val label = if (mode == "enter") "队伍选择页" else "跳过战斗确认页"
    repeat(10) { attempt ->
        ctx.ensureActive()
        val found = if (mode == "enter") {
            ctx.recognize.templateMatch("details").isNotEmpty() || ctx.recognize.observeTeamSelection() != null
        } else ctx.recognize.templateMatch("skip_battle").isNotEmpty()
        if (found) return ActionOutcome.Continue
        if (attempt == 0) ctx.log("等待$label")
        if (attempt < 9) ctx.delay(.5)
    }
    return ActionOutcome.Finish(false, "未能确认$label，请放大游戏画面检查转场或弹窗后重试")
}

/** 直接调用动作或导入旧配置也必须在触控前校验，不能依赖页面校验。 */
private fun validateSelection(label: String, stage: String, mode: String): ActionOutcome.Finish? {
    if (stage.isEmpty() || stage.any { it !in '0'..'9' }) {
        return ActionOutcome.Finish(false, "${label}副本关卡必须为非空数字，请检查任务配置")
    }
    if (mode != "enter" && mode != "skip battle") {
        return ActionOutcome.Finish(false, "未知的${label}副本模式：$mode，请检查任务配置")
    }
    return null
}
