package com.maadroid.app.engine.limbus.action

import com.maadroid.app.engine.limbus.action.InputHelper.click
import com.maadroid.app.engine.limbus.action.InputHelper.clickRepeat
import com.maadroid.app.engine.limbus.action.InputHelper.keyPress
import com.maadroid.app.engine.limbus.action.InputHelper.keyRepeat
import com.maadroid.app.engine.limbus.action.InputHelper.swipe
import com.maadroid.app.engine.limbus.recognize.GameLanguageObservation
import kotlin.math.max

/**
 * 基础动作：click / key / swipe / empty / wait_appear / wait_disappear /
 * report_error / check_out_update / init_limbus_window。
 *
 * 对应上游 task_action/base.py，逻辑最轻、最先实现。
 */
object BaseActions {

    fun registerAll() {
        ActionRegistry.registerAll(
            "click" to ClickAction,
            "key" to KeyAction,
            "swipe" to SwipeAction,
            "empty" to EmptyAction,
            "wait_appear" to WaitAppearAction,
            "wait_disappear" to WaitDisappearAction,
            "report_error" to ReportErrorAction,
            "check_out_update" to CheckOutUpdateAction,
            "init_limbus_window" to InitLimbusWindowAction,
        )
    }
}

private object ClickAction : ActionBackend {
    /**
     * 点击。
     *
     * 目标有两种形态，**缺省即第二种**（这点极易看漏）：
     * - `target` 是坐标数组 `[x, y]` → 直接点它（实测上游 13 个节点）
     * - `target` 是字符串**或根本没配** → 点本节点路由识别命中的位置
     *   （实测上游 10 个节点，如 error_server_error_retry_confirm 只配了 template）
     *
     * 之所以「没配」也走识别结果：上游 `node.get_param("target", "")` 的缺省值是
     * 空**字符串**，于是落进 `isinstance(target, str)` 分支去读 `recognize_result[0]`。
     * 若把缺省当成「无目标、不点击」，这 10 个节点会静默失效 —— 流水线照常推进却
     * 什么都没点，表现为卡在某个界面而日志毫无异常。
     */
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val node = ctx.node
        if (node.str("android_mail_flow") == "true") {
            if (ctx.nodeName == "check_and_get_mails") return MailActions.openMailbox(ctx)
            if (ctx.nodeName == "claim_mail") MailActions.exitIfEmpty(ctx)?.let { return it }
        }
        val offset = node.ints("target_offset") ?: listOf(0, 0)
        val ox = offset.getOrElse(0) { 0 }
        val oy = offset.getOrElse(1) { 0 }

        val coords = node.ints("target")
        val (x, y) = if (coords != null && coords.size >= 2) {
            coords[0] + ox to coords[1] + oy
        } else {
            // 上游此处会因 recognize_result 为空而 IndexError 炸掉整条链；
            // 这里跳过并留日志 —— 识别不中本就是有定义的状态
            val hit = ctx.recognizeResult.firstOrNull()
            if (hit == null) {
                ctx.log("节点 ${ctx.nodeName} 没有可用的识别坐标，跳过点击")
                return ActionOutcome.Continue
            }
            hit.x + ox to hit.y + oy
        }

        val repeat = node.num("repeat")?.toInt() ?: 1
        // 上游 max(interval - 0.5, 0)：一次点击自身约耗 0.5 秒，不减会让节奏偏慢
        val interval = max((node.num("repeat_interval") ?: DEFAULT_CLICK_INTERVAL) - CLICK_COST_SEC, 0.0)

        clickRepeat(ctx.input, x, y, repeat, interval) { ctx.delay(it) }
        return ActionOutcome.Continue
    }
}

/** click 的默认重复间隔与单次点击的估算耗时，均取自上游 */
private const val DEFAULT_CLICK_INTERVAL = 0.2
private const val CLICK_COST_SEC = 0.5

/** swipe 的默认重复间隔与单次滑动的估算耗时 */
private const val DEFAULT_SWIPE_INTERVAL = 0.5
private const val SWIPE_COST_SEC = 0.5

private object KeyAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val keyName = ctx.node.str("key") ?: return ActionOutcome.Continue
        if (ctx.node.str("android_mail_flow") == "true" && keyName == "esc") {
            if (ctx.nodeName == "confirm_reward") return MailActions.confirmReward(ctx)
            if (ctx.nodeName == "exit_mailbox") return MailActions.closeMailbox(ctx)
        }
        val repeat = ctx.node.num("repeat")?.toInt() ?: 1
        val interval = ctx.node.num("repeat_interval") ?: 0.3

        keyRepeat(ctx.input, keyName, repeat, interval) { ctx.delay(it) }
        return ActionOutcome.Continue
    }
}

private object SwipeAction : ActionBackend {
    /**
     * 滑动。起点与 [ClickAction] 同规则（坐标或识别结果），终点必须是坐标。
     *
     * 实测上游只有一个 swipe 节点且起终点都是坐标；这里仍按同一套规则处理起点，
     * 以便上游改成按识别结果起滑时无需改动。
     */
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val node = ctx.node
        val end = node.ints("end")
        if (end == null || end.size < 2) {
            ctx.log("节点 ${ctx.nodeName} 的 swipe 缺少终点坐标，跳过")
            return ActionOutcome.Continue
        }
        val beginOffset = node.ints("begin_offset") ?: listOf(0, 0)
        val endOffset = node.ints("end_offset") ?: listOf(0, 0)

        val beginCoords = node.ints("begin")
        val (bx, by) = if (beginCoords != null && beginCoords.size >= 2) {
            beginCoords[0] + beginOffset.getOrElse(0) { 0 } to
                    beginCoords[1] + beginOffset.getOrElse(1) { 0 }
        } else {
            val hit = ctx.recognizeResult.firstOrNull()
            if (hit == null) {
                ctx.log("节点 ${ctx.nodeName} 没有可用的识别坐标，跳过滑动")
                return ActionOutcome.Continue
            }
            hit.x + beginOffset.getOrElse(0) { 0 } to hit.y + beginOffset.getOrElse(1) { 0 }
        }

        val ex = end[0] + endOffset.getOrElse(0) { 0 }
        val ey = end[1] + endOffset.getOrElse(1) { 0 }

        val repeat = node.num("repeat")?.toInt() ?: 1
        val interval = max((node.num("repeat_interval") ?: DEFAULT_SWIPE_INTERVAL) - SWIPE_COST_SEC, 0.0)

        swipe(ctx.input, bx, by, ex, ey)
        for (i in 1 until repeat) {
            if (interval > 0) ctx.delay(interval)
            swipe(ctx.input, bx, by, ex, ey)
        }
        return ActionOutcome.Continue
    }
}

private object EmptyAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome = ActionOutcome.Continue
}

private object WaitAppearAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val checkInterval = ctx.node.num("check_interval") ?: 1.0
        val maxWait = ctx.node.num("max_wait_time") ?: 5.0
        val template = ctx.node.str("template") ?: return ActionOutcome.Continue

        var waited = 0.0
        while (waited < maxWait) {
            ctx.ensureActive()
            val matches = ctx.recognize.templateMatch(template)
            if (matches.isNotEmpty()) break
            ctx.delay(checkInterval)
            waited += checkInterval
        }
        return ActionOutcome.Continue
    }
}

private object WaitDisappearAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val checkInterval = ctx.node.num("check_interval") ?: 1.0
        val template = ctx.node.str("template") ?: return ActionOutcome.Continue

        while (true) {
            ctx.ensureActive()
            val matches = ctx.recognize.templateMatch(template)
            if (matches.isEmpty()) break
            ctx.delay(checkInterval)
        }
        return ActionOutcome.Continue
    }
}

private object ReportErrorAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        if (ctx.nodeName == "exp_can_not_skip_battle" && ctx.node.recognition == "direct") {
            // 上游这是无条件兜底，没有“未解锁”的正向识别证据。
            return ActionOutcome.Finish(false, "未能确认经验副本的队伍或跳过确认页，请放大游戏画面检查后重试")
        }
        if (ctx.node.inverse && ctx.node.str("template") == "main_drive_with_text") {
            // The upstream inverse template gate treats any miss as a wrong language.
            // On Android, let a transition finish and require positive language evidence.
            repeat(3) { attempt ->
                ctx.ensureActive()
                when (val observation = ctx.recognize.observeGameLanguage()) {
                    GameLanguageObservation.Confirmed -> {
                        ctx.log("已确认游戏导航语言，继续任务")
                        // This inverse error branch has no next but inherits error_handler.
                        // Continuing its route would loop there instead of resuming the caller.
                        return ActionOutcome.Return
                    }
                    is GameLanguageObservation.Mismatch -> {
                        val selected = languageName(observation.configured)
                        val detected = languageName(observation.detected)
                        val message = "游戏画面为$detected，任务配置为$selected。请在边狱巴士「更多」中将游戏语言改为$detected。"
                        ctx.log(message)
                        return ActionOutcome.Finish(false, message)
                    }
                    GameLanguageObservation.Uncertain -> if (attempt < 2) {
                        ctx.log("正在重新确认游戏导航语言（${attempt + 1}/3）")
                        ctx.delay(0.5)
                    }
                }
            }
            val message = "暂时无法识别主页导航。请放大游戏画面检查转场或弹窗后重试；这不代表游戏语言设置错误。"
            ctx.log(message)
            return ActionOutcome.Finish(false, message)
        }
        // 上游是 raise Exception 直接炸掉整条流水线；这里收敛成 Finish(false)，
        // 让宿主能把 error_msg 作为失败原因投给用户而不是抛一个栈到日志里。
        val msg = ctx.node.str("error_msg") ?: "未知错误"
        ctx.log("流水线报告错误: $msg")
        return ActionOutcome.Finish(success = false, message = msg)
    }

    private fun languageName(language: String) = when (language) {
        "en" -> "英文"
        "zh" -> "中文"
        else -> language
    }
}

private object CheckOutUpdateAction : ActionBackend {
    /**
     * check 节点计数 +1。
     *
     * 上游把计数写在节点 `params.execute_count` 里原地自增；本项目的节点不可变
     * （要能被资源包整体替换），故计数走 [ActionContext.incrementCounter]。
     * 这个计数不是统计用途 —— 队伍轮换取它的模，丢了会导致每轮都用第一套队伍。
     */
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val name = ctx.node.str("counter_key") ?: ctx.nodeName
        val now = ctx.incrementCounter(name)
        ctx.log("节点 $name 计数 + 1，当前: $now")
        return ActionOutcome.Continue
    }
}

private object InitLimbusWindowAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        ctx.log("初始化 Limbus 窗口（Android 端由宿主管理虚拟显示器）")
        return ActionOutcome.Continue
    }
}
