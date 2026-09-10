package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.click
import com.aliothmoon.maadroid.engine.limbus.action.InputHelper.keyPress
import com.aliothmoon.maadroid.engine.limbus.recognize.Match

/** Android mailbox input safeguards; routing and one-shot completion still use mail.json. */
internal object MailActions {
    suspend fun openMailbox(ctx: ActionContext): ActionOutcome {
        ctx.ensureActive()
        // mail_enter_main_window has selected Window through main_window_confirm. Require
        // both home navigation anchors again before using the Android mailbox coordinate.
        val window = ctx.recognize.templateMatch("main_window_no_text").firstOrNull()
        val drive = ctx.recognize.templateMatch("main_drive_no_text").firstOrNull()
        if (window == null || drive == null) {
            return ActionOutcome.Finish(false, "无法确认邮件入口所在的主页，请放大游戏画面检查弹窗后重试")
        }
        ctx.ensureActive()
        ctx.log("打开邮箱检查并领取邮件")
        // The 1280x720 Android Window page has the envelope at (1120,125), between the
        // notices and settings buttons (phone home capture 2026-09-09 22:48).
        // A missing red notification dot is not evidence that the mailbox was checked.
        click(ctx.input, 1120, 125)
        ctx.delay(1.0)
        return ActionOutcome.Continue
    }

    suspend fun exitIfEmpty(ctx: ActionContext): ActionOutcome? {
        ctx.ensureActive()
        if (matches(ctx, "no_mail_in_storage").isEmpty()) return null
        ctx.log("邮箱暂无可领取邮件")
        return ActionOutcome.Goto("exit_mailbox")
    }

    suspend fun confirmReward(ctx: ActionContext): ActionOutcome {
        repeat(30) {
            ctx.ensureActive()
            if (matches(ctx, "rewards_acquired_confirm").isNotEmpty()) {
                keyPress(ctx.input, "esc")
                ctx.delay(0.5)
                return ActionOutcome.Continue
            }
            exitIfEmpty(ctx)?.let { return it }
            ctx.delay(1.0)
        }
        // Blind Escape could close the mailbox; the following inverse gate would then
        // interpret the home page as "non-empty mailbox" and keep clicking (950,270).
        return ActionOutcome.Finish(false, "未能确认邮件领取结果，请放大游戏画面检查邮箱后重试")
    }

    private suspend fun matches(ctx: ActionContext, template: String): List<Match> {
        val exact = ctx.recognize.templateMatch(template)
        if (exact.isNotEmpty()) return exact
        ctx.ensureActive()
        // Restrict mobile scale fallback to the two mailbox/result templates. Keep the
        // configured confidence and the shared recognizer's behavior for all other tasks.
        return ctx.recognize.pyramidTemplateMatch(template, threshold = 0.85)
    }
}
