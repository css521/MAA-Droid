package com.maadroid.app.engine.limbus.action

import com.maadroid.app.engine.limbus.action.InputHelper.click
import com.maadroid.app.engine.limbus.recognize.Match

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
        if (ctx.recognize.observeMailbox()?.empty != true && matches(ctx, "no_mail_in_storage").isEmpty()) return null
        ctx.log("邮箱暂无可领取邮件")
        return ActionOutcome.Goto("exit_mailbox")
    }

    suspend fun closeMailbox(ctx: ActionContext): ActionOutcome {
        repeat(5) {
            ctx.ensureActive()
            val close = ctx.recognize.observeMailbox()?.close
            if (close != null) {
                ctx.ensureActive()
                ctx.log("点击邮箱关闭按钮，位置=${close.x},${close.y}")
                click(ctx.input, close.x, close.y)
                return awaitMailboxClosed(ctx)
            }
            ctx.delay(0.5)
        }
        return ActionOutcome.Finish(false, "无法确认邮箱关闭按钮，请放大游戏画面检查邮箱后重试")
    }

    private suspend fun awaitMailboxClosed(ctx: ActionContext): ActionOutcome {
        repeat(10) {
            ctx.delay(0.5)
            ctx.ensureActive()
            if (ctx.recognize.observeMailbox() == null) {
                val window = ctx.recognize.templateMatch("main_window_no_text").firstOrNull()
                val drive = ctx.recognize.templateMatch("main_drive_no_text").firstOrNull()
                // Null alone includes loading/blank frames. Both positive home anchors are
                // required; recheck the mailbox so its background navigation cannot suffice.
                if (window != null && drive != null && ctx.recognize.observeMailbox() == null) {
                    ctx.log("已关闭邮箱并返回主页")
                    return ActionOutcome.Continue
                }
            }
        }
        return ActionOutcome.Finish(false, "邮箱仍未关闭或主页尚未就绪，请检查游戏画面后重试")
    }

    suspend fun confirmReward(ctx: ActionContext): ActionOutcome {
        repeat(30) {
            ctx.ensureActive()
            // Empty mailboxes must leave before any generic Confirm template is considered.
            exitIfEmpty(ctx)?.let { return it }
            val confirm = matches(ctx, "rewards_acquired_confirm").firstOrNull()
            if (confirm != null) {
                ctx.ensureActive()
                click(ctx.input, confirm.x, confirm.y)
                ctx.delay(0.5)
                return ActionOutcome.Continue
            }
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
