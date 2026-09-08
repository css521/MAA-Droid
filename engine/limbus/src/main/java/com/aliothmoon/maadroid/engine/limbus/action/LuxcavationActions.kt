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
        if (targetStage.isEmpty()) return ActionOutcome.Continue

        var pos = ctx.recognize.findText(targetStage, crop = Crop(250, 180, 1000, 50))
        var cnt = 0
        while (pos.isEmpty()) {
            ctx.ensureActive()
            swipe(ctx.input, 590, 310, 940, 310)
            ctx.delay(0.6)
            pos = ctx.recognize.findText(targetStage, crop = Crop(250, 180, 1000, 50))
            if (++cnt > 5) {
                ctx.log("选不到 Lv$targetStage 的经验副本关卡")
                return ActionOutcome.Finish(false, "找不到经验副本 $targetStage")
            }
        }

        val enterX = pos[0].x + 10
        val mode = ctx.config.str("exp", "luxcavation_mode", "enter")
        when (mode) {
            "enter" -> click(ctx.input, enterX, 480)
            "skip battle" -> click(ctx.input, enterX, 515)
            else -> ctx.log("未知的 exp mode: $mode")
        }
        return ActionOutcome.Continue
    }
}

private object ThreadSelectStageAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("选择 Thread 副本关卡")
        click(ctx.input, 140, 330)
        ctx.delay(1.0)

        val mode = ctx.config.str("thread", "luxcavation_mode", "enter")
        when (mode) {
            "enter" -> click(ctx.input, 370, 480)
            "skip battle" -> click(ctx.input, 370, 515)
            else -> ctx.log("未知的 thread mode: $mode")
        }
        ctx.delay(1.0)

        val targetStage = ctx.config.str("thread", "thread_stage", "")
        if (targetStage.isEmpty()) return ActionOutcome.Continue

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
        return ActionOutcome.Continue
    }
}
