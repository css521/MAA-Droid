package com.maadroid.app.engine.limbus.action

import com.maadroid.app.engine.limbus.action.InputHelper.click
import com.maadroid.app.engine.limbus.action.InputHelper.keyPress
import com.maadroid.app.engine.limbus.recognize.Crop

/**
 * 事件相关动作：event_pass_check / event_make_choice。
 *
 * 对应上游 task_action/event.py。
 */
object EventActions {

    fun registerAll() {
        ActionRegistry.registerAll(
            "event_pass_check" to EventPassCheckAction,
            "event_make_choice" to EventMakeChoiceAction,
        )
    }
}

private object EventPassCheckAction : ActionBackend {
    private val passLevels = listOf("very_high", "high", "normal", "low", "very_low")

    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("事件判定检查")
        val crop = Crop(20, 590, 950, 60)
        var recognized = false
        for (level in passLevels) {
            val res = ctx.recognize.templateMatch("event_pass_$level", crop = crop)
            if (res.isNotEmpty()) {
                click(ctx.input, res[0].x, res[0].y)
                recognized = true
                break
            }
        }
        if (!recognized) {
            ctx.log("事件判定检测失败，启用小唐")
            click(ctx.input, 210, 650)
        }
        ctx.delay(1.0)
        click(ctx.input, 1120, 650)
        return ActionOutcome.Continue
    }
}

private object EventMakeChoiceAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val scrollStrip = ctx.recognize.templateMatch("event_scroll_strip")
        if (scrollStrip.isNotEmpty()) {
            click(ctx.input, scrollStrip[0].x, scrollStrip[0].y)
        }
        ctx.delay(1.0)
        ctx.log("事件选项处理")

        val specialPhrases = listOf(
            "Select to gain", "Pass to level up", "Pass to gain",
            "check to gain", "depending on"
        ).map { localizedName(ctx.config, it) }

        val results = ctx.recognize.detectText(Crop(670, 140, 620, 500))
        var specialCase = false
        for (res in results) {
            if (specialPhrases.any { it in res.text }) {
                ctx.log("找到优先选项: ${res.text}")
                click(ctx.input, res.x, res.y)
                specialCase = true
            }
        }

        if (!specialCase) {
            for ((x, y) in listOf(950 to 200, 950 to 290, 950 to 380, 950 to 450)) {
                click(ctx.input, x, y)
            }
        }
        return ActionOutcome.Continue
    }
}
