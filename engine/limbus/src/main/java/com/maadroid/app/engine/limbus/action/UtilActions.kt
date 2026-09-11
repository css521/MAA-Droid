package com.maadroid.app.engine.limbus.action

import com.maadroid.app.engine.limbus.action.InputHelper.click
import com.maadroid.app.engine.limbus.action.InputHelper.keyPress
import com.maadroid.app.engine.limbus.recognize.Crop

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
    /**
     * 一次识别拿到所有奖励位置，再逐个点击。
     *
     * 不改成「每次点完重新识别」：那样每项都要付一次模板匹配，几十项奖励会慢到不可用。
     * 领奖界面的布局在领取过程中不重排，一次识别的坐标是可靠的。
     *
     * 真正缺的是**点击间隔**：上游点完只等 waitConnectingDisappear（网络请求完成），
     * 不等领取动画播完。安卓上动画更长，下一次点击落在动画中途会被吞掉，表现为漏领。
     */
    private suspend fun claimAll(ctx: ActionContext, label: String) {
        ctx.log(label)
        val coins = ctx.recognize.templateMatch("reward_coin")
        for (pos in coins) {
            ctx.ensureActive()
            click(ctx.input, pos.x, pos.y)
            waitConnectingDisappear(ctx)
            // 上游点完只等 waitConnectingDisappear（网络请求），不等领取动画播完，
            // 安卓上动画更长，下一次点击落在动画中途会被吞掉 → 漏领。
            ctx.delay(REWARD_SETTLE)
        }
        ctx.log("$label：点击 ${coins.size} 项")
    }

    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        claimAll(ctx, "收集日常奖励")
        click(ctx.input, 270, 400)
        ctx.delay(REWARD_SETTLE)
        claimAll(ctx, "收集周常奖励")
        return ActionOutcome.Continue
    }
}

/** 领奖后等动画与列表重排；上游没有这段等待，安卓上会漏领 */
private const val REWARD_SETTLE = 1.5


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

        ctx.ensureActive()
        ctx.recognize.titleScreenStart()?.let { start ->
            val attempt = ctx.incrementCounter("android_title_login")
            if (attempt > 5) {
                return ActionOutcome.Finish(false, "点击开始后仍停留在标题页，请放大游戏画面检查登录状态后重试")
            }
            ctx.log("检测到游戏标题页，点击开始登录（第 $attempt 次）")
            ctx.input.touchDown(start.x, start.y)
            try {
                ctx.delay(0.12)
            } finally {
                ctx.input.touchUp(start.x, start.y)
            }
            ctx.incrementCounter("android_home_recovery_epoch")
            ctx.delay(3.0)
            return ActionOutcome.Continue
        }

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
            val epoch = ctx.counterOf("android_home_recovery_epoch")
            val attempt = ctx.incrementCounter("android_unknown_home_$epoch")
            if (attempt > 20) {
                return ActionOutcome.Finish(false, "持续无法识别登录或主页，请检查游戏语言设置，放大画面处理弹窗后重试，并导出日志")
            }
            val connecting = ctx.recognize.templateMatch("connecting").isNotEmpty()
            // 上游恢复动作在普通页面立即按 Esc；此前 Android 将所有无 Drive 图标页面
            // 都先当加载等三轮。选关页会折叠导航，用现有 LALC 标题确认后立即走原有返回。
            val inLuxcavation = !connecting && (
                ctx.recognize.templateMatch("luxcavation").any { it.x in 20..280 && it.y in 120..420 } ||
                    ctx.recognize.pyramidTemplateMatch("luxcavation", .85, Crop(20, 120, 260, 300))
                        .any { it.x in 20..280 && it.y in 120..420 }
                )
            // 冷启动、网络连接与转场没有可识别按钮时，先留出加载时间。
            if (connecting || (attempt <= 3 && !inLuxcavation)) {
                ctx.log("等待游戏加载或登录（$attempt/20）")
                ctx.delay(3.0)
                return ActionOutcome.Continue
            }
            ctx.log(if (inLuxcavation) "已识别采光副本选关页，按返回键回到主页" else "未检测到特殊情况，按 esc 尝试")
            keyPress(ctx.input, "esc")
            ctx.delay(1.2)
            if (ctx.recognize.templateMatch("quit_game").isNotEmpty()) {
                keyPress(ctx.input, "esc")
            }
            ctx.delay(1.0)
            return ActionOutcome.Continue
        }
        ctx.incrementCounter("android_home_recovery_epoch")
        ctx.delay(1.0)
        return ActionOutcome.Continue
    }
}
