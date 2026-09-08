package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.click
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.keyPress
import com.aliothmoon.maadroid.engine.limbus.recognize.Crop

/**
 * 通用工具动作：confirm_all_coins / get_enkephalin_module / recharge_enkephalin /
 * error_cannot_operate_the_game / back_to_init_page。
 *
 * 对应上游 task_action/utils.py 与 base.py 的尾部。
 */
object UtilActions {

    fun registerAll() {
        ActionRegistry.registerAll(
            "confirm_all_coins" to ConfirmAllCoinsAction,
            "get_enkephalin_module" to GetEnkephalinModuleAction,
            "recharge_enkephalin" to RechargeEnkephalinAction,
            "error_cannot_operate_the_game" to ErrorCannotOperateAction,
            "back_to_init_page" to BackToInitPageAction,
        )
    }
}

private object ConfirmAllCoinsAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("收集日常奖励")
        val coins = ctx.recognize.templateMatch("reward_coin")
        for (pos in coins) {
            click(ctx.input, pos.x, pos.y)
            waitConnectingDisappear(ctx)
        }
        click(ctx.input, 270, 400)
        ctx.delay(1.0)
        ctx.log("收集周常奖励")
        val weeklyCoins = ctx.recognize.templateMatch("reward_coin")
        for (pos in weeklyCoins) {
            click(ctx.input, pos.x, pos.y)
            waitConnectingDisappear(ctx)
        }
        return ActionOutcome.Continue
    }
}

private object GetEnkephalinModuleAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("获取脑啡肽模块")
        click(ctx.input, 500, 230)
        ctx.delay(1.0)
        click(ctx.input, 800, 330)
        ctx.delay(1.0)
        keyPress(ctx.input, "enter")
        waitConnectingDisappear(ctx)
        keyPress(ctx.input, "esc")
        return ActionOutcome.Continue
    }
}

private object RechargeEnkephalinAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val cfg = ctx.config
        val target = cfg.int("other_task", "lunary_purchase_target", 0)
        if (target == 0) {
            ctx.log("不充值脑啡肽")
            return ActionOutcome.Continue
        }

        ctx.log("充值脑啡肽")
        click(ctx.input, 630, 230)
        ctx.delay(1.0)

        val ocrResult = ctx.recognize.detectText(Crop(680, 300, 120, 45))
        var purchased = parseSlashCount(ocrResult.firstOrNull()?.text) ?: 10

        while (purchased < target) {
            ctx.ensureActive()
            keyPress(ctx.input, "enter")
            ctx.delay(1.0)
            waitConnectingDisappear(ctx)
            purchased++
            ctx.log("购买了一次狂气, 还剩${target - purchased}")
        }
        return ActionOutcome.Continue
    }
}

private object ErrorCannotOperateAction : ActionBackend {
    /**
     * 上游在这里关掉再重开 Limbus 窗口（Win32 窗口句柄操作）。
     *
     * Android 侧重启游戏进程要走 DeviceControl.stopApp/startApp，那是宿主的权限
     * （提权进程），动作层拿不到也不该拿。故此处只报告失败，由宿主决定是否重启后重跑
     * —— 假装处理了却什么都没做，会让流水线在同一个卡死界面上无限空转。
     */
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("游戏无法操作，需宿主重启游戏进程")
        return ActionOutcome.Finish(success = false, message = "游戏无法操作，请重启游戏后重试")
    }
}

private object BackToInitPageAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("尝试返回主页")

        if (ctx.recognize.templateMatch("rewards_acquired_confirm").isNotEmpty()) {
            ctx.log("检测到领取奖励的确认")
            val pos = ctx.recognize.templateMatch("rewards_acquired_confirm")
            if (pos.isNotEmpty()) click(ctx.input, pos[0].x, pos[0].y)
        } else if (ctx.recognize.templateMatch("daily_login_close").isNotEmpty()) {
            ctx.log("检测到日常登录关闭按钮")
            val pos = ctx.recognize.templateMatch("daily_login_close")
            if (pos.isNotEmpty()) click(ctx.input, pos[0].x, pos[0].y)
        } else if (ctx.recognize.templateMatch("left_top_arrow").isNotEmpty()) {
            ctx.log("尝试点击左上角返回按钮")
            val pos = ctx.recognize.templateMatch("left_top_arrow")
            if (pos.isNotEmpty()) click(ctx.input, pos[0].x, pos[0].y)
        } else if (ctx.recognize.templateMatch("win_rate").isNotEmpty()) {
            ctx.log("检测到处于战斗中，尝试退出")
            click(ctx.input, 1230, 45)
            ctx.delay(2.0)
            click(ctx.input, 640, 400)
            click(ctx.input, 640, 430)
        } else if (ctx.recognize.templateMatch("defeat").isNotEmpty() ||
            ctx.recognize.templateMatch("victory").isNotEmpty()
        ) {
            keyPress(ctx.input, "enter")
            ctx.delay(1.0)
        } else if (ctx.recognize.templateMatch("right_top_setting").isNotEmpty() ||
            ctx.recognize.templateMatch("pack_search").isNotEmpty()
        ) {
            ctx.log("检测到可能处于镜牢，从设置返回")
            click(ctx.input, 1230, 45)
            ctx.delay(2.0)
            click(ctx.input, 730, 435)
            ctx.delay(2.0)
            keyPress(ctx.input, "enter")
        } else if (ctx.recognize.templateMatch("exploration_complete").isNotEmpty()) {
            for (i in 0 until 6) {
                keyPress(ctx.input, "enter")
                ctx.delay(1.0)
            }
            return ActionOutcome.Goto("mirror_defeat")
        } else {
            ctx.log("未检测到特殊情况，按 esc 尝试")
            keyPress(ctx.input, "esc")
            ctx.delay(1.2)
            if (ctx.recognize.templateMatch("quit_game").isNotEmpty()) {
                keyPress(ctx.input, "esc")
            }
        }
        ctx.delay(1.0)
        return ActionOutcome.Continue
    }
}
