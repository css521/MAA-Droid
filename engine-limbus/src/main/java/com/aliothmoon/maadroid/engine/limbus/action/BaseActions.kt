package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.click
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.clickRepeat
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.keyPress
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.keyRepeat
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.swipe
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
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val node = ctx.node
        val targetOffset = node.ints("target_offset") ?: listOf(0, 0)
        val ox = targetOffset.getOrElse(0) { 0 }
        val oy = targetOffset.getOrElse(1) { 0 }

        val targetRaw = node.str("target")
        val coords = node.ints("target")

        val (x, y) = when {
            coords != null && coords.size >= 2 -> coords[0] + ox to coords[1] + oy
            targetRaw != null -> {
                val matches = ctx.recognize.templateMatch(targetRaw)
                if (matches.isEmpty()) return ActionOutcome.Continue
                matches[0].x + ox to matches[0].y + oy
            }
            else -> return ActionOutcome.Continue
        }

        val repeat = node.num("repeat")?.toInt() ?: 1
        val interval = max((node.num("repeat_interval") ?: 0.2) - 0.5, 0.0)

        clickRepeat(ctx.input, x, y, repeat, interval) { ctx.delay(it) }
        return ActionOutcome.Continue
    }
}

private object KeyAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val keyName = ctx.node.str("key") ?: return ActionOutcome.Continue
        val repeat = ctx.node.num("repeat")?.toInt() ?: 1
        val interval = ctx.node.num("repeat_interval") ?: 0.3

        keyRepeat(ctx.input, keyName, repeat, interval) { ctx.delay(it) }
        return ActionOutcome.Continue
    }
}

private object SwipeAction : ActionBackend {
    override suspend fun execute(ctx: ActionContext): ActionOutcome {
        val node = ctx.node
        val beginOffset = node.ints("begin_offset") ?: listOf(0, 0)
        val endOffset = node.ints("end_offset") ?: listOf(0, 0)
        val end = node.ints("end") ?: return ActionOutcome.Continue

        val beginRaw = node.str("begin")
        val beginCoords = node.ints("begin")

        val (bx, by) = when {
            beginCoords != null && beginCoords.size >= 2 ->
                beginCoords[0] + beginOffset.getOrElse(0) { 0 } to
                        beginCoords[1] + beginOffset.getOrElse(1) { 0 }
            beginRaw != null -> {
                val matches = ctx.recognize.templateMatch(beginRaw)
                if (matches.isEmpty()) return ActionOutcome.Continue
                matches[0].x + beginOffset.getOrElse(0) { 0 } to
                        matches[0].y + beginOffset.getOrElse(1) { 0 }
            }
            else -> return ActionOutcome.Continue
        }

        val ex = end[0] + endOffset.getOrElse(0) { 0 }
        val ey = end[1] + endOffset.getOrElse(1) { 0 }

        val repeat = node.num("repeat")?.toInt() ?: 1
        val interval = max((node.num("repeat_interval") ?: 0.5) - 0.5, 0.0)

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
        // 上游是 raise Exception 直接炸掉整条流水线；这里收敛成 Finish(false)，
        // 让宿主能把 error_msg 作为失败原因投给用户而不是抛一个栈到日志里。
        val msg = ctx.node.str("error_msg") ?: "未知错误"
        ctx.log("流水线报告错误: $msg")
        return ActionOutcome.Finish(success = false, message = msg)
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
