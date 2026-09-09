package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import com.aliothmoon.maadroid.engine.limbus.recognize.TextMatch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TitleScreenRecoveryTest {
    @Before fun installActions() {
        ActionRegistry.clearForTest()
        LimbusActions.resetForTest()
        LimbusActions.install()
    }

    private suspend fun recover(ctx: TestActionContext) = ActionRegistry["back_to_init_page"]!!.execute(ctx)

    @Test fun titleIsTappedWithoutSendingBackOrClearingCaches() = runTest {
        val ctx = TestActionContext()
        ctx.fakeRecognizer.onTemplate("clear_all_caches", Match(256, 656, 0.9))
        assertEquals(ActionOutcome.Continue, recover(ctx))
        assertEquals(listOf(640 to 540), ctx.fakeInput.clicks())
        assertTrue(ctx.fakeInput.keyPresses().isEmpty())
        assertEquals(listOf(0.12, 3.0), ctx.slept)
        assertTrue(ctx.logs.any { "点击开始登录" in it })
    }

    @Test fun titleUsesOcrWhenSelectedLanguageTemplateDoesNotMatch() = runTest {
        val ctx = TestActionContext()
        ctx.fakeRecognizer.textHits = listOf(TextMatch("TOUCH TO START", 640, 550, 0.92))
        recover(ctx)
        assertEquals(listOf(640 to 550), ctx.fakeInput.clicks())
        assertTrue(ctx.fakeInput.keyPresses().isEmpty())
    }

    @Test fun titleRetriesAreBoundedAndReportedAsFailure() = runTest {
        val ctx = TestActionContext()
        ctx.fakeRecognizer.onTemplate("clear_all_caches", Match(256, 656, 0.9))
        repeat(5) { assertEquals(ActionOutcome.Continue, recover(ctx)) }
        val result = recover(ctx) as ActionOutcome.Finish
        assertFalse(result.success)
        assertTrue(result.message!!.contains("标题页"))
        assertEquals(5, ctx.fakeInput.clicks().size)
    }

    @Test fun coldLoadingWaitsBeforeBackAndUnrecognizedScreenEventuallyFails() = runTest {
        val ctx = TestActionContext()
        repeat(3) { assertEquals(ActionOutcome.Continue, recover(ctx)) }
        assertTrue(ctx.fakeInput.events.isEmpty())
        repeat(17) { assertEquals(ActionOutcome.Continue, recover(ctx)) }
        val result = recover(ctx) as ActionOutcome.Finish
        assertFalse(result.success)
        assertTrue(result.message!!.contains("游戏语言"))
    }

    @Test fun connectingNeverReceivesBackAndKnownDialogResetsUnknownAttempts() = runTest {
        val ctx = TestActionContext()
        ctx.fakeRecognizer.onTemplate("connecting", Match(640, 360, 0.95))
        repeat(20) { assertEquals(ActionOutcome.Continue, recover(ctx)) }
        assertTrue(ctx.fakeInput.events.isEmpty())
        ctx.fakeRecognizer.onTemplate("daily_login_close", Match(1200, 50, 0.96))
        recover(ctx)
        ctx.fakeRecognizer.onTemplate("daily_login_close")
        assertEquals(ActionOutcome.Continue, recover(ctx))
    }
}
