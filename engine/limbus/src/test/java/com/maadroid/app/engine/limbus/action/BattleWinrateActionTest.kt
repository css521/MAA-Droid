package com.maadroid.app.engine.limbus.action

import android.view.KeyEvent
import com.maadroid.app.engine.limbus.recognize.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BattleWinrateActionTest {
    private val closed = BattleEgoPanel(emptyList(), emptyList(), true)
    private fun open(vararg safeX: Int) = BattleEgoPanel(
        listOf(Match(400, 150, .95), Match(650, 150, .95)),
        safeX.map { Match(it, 160, .95) }, true,
    )

    private class Vision : Recognizer by FakeRecognizer() {
        var skills = listOf(BattleSkillIcon("hopeless", 150, 550))
        var avatars = listOf(BattleSinnerAvatar(100, 690, listOf(110)))
        val panels = mutableListOf<BattleEgoPanel?>()
        var skillCalls = 0
        var avatarCalls = 0
        override suspend fun battleSkillIcons(): List<BattleSkillIcon> { skillCalls++; return skills }
        override suspend fun battleSinnerAvatars(): List<BattleSinnerAvatar> { avatarCalls++; return avatars }
        override suspend fun battleEgoPanel(): BattleEgoPanel? = if (panels.isEmpty()) null else panels.removeAt(0)
    }

    private fun context(v: Vision) = TestActionContext(recognize = v).also {
        it.fakeConfig.put("other_task", "ego_enable", true)
    }
    private suspend fun execute(ctx: ActionContext) = ActionRegistry["battle_winrate"]!!.execute(ctx)

    @Before fun register() { BattleActions.registerAll() }

    @Test fun disabledDoesNotReadBattlePerception() = runTest {
        val v = Vision()
        val ctx = context(v)
        ctx.fakeConfig.put("other_task", "ego_enable", false)
        assertEquals(ActionOutcome.Continue, execute(ctx))
        assertEquals(0, v.skillCalls)
        assertEquals(listOf(KeyEvent.KEYCODE_P), ctx.fakeInput.keyPresses())
        assertTrue(ctx.fakeInput.clicks().isEmpty())
    }

    @Test fun allUnselectedRetriesAndPressesPAgainOnNextExecution() = runTest {
        val v = Vision().apply { skills = listOf(BattleSkillIcon("unselected", 150, 550)) }
        val ctx = context(v)
        assertEquals(ActionOutcome.RetrySelf, execute(ctx))
        assertEquals(listOf(10 to 719), ctx.fakeInput.clicks())
        v.skills = listOf(BattleSkillIcon("dominating", 150, 550))
        assertEquals(ActionOutcome.Continue, execute(ctx))
        assertEquals(2, ctx.fakeInput.keyPresses().size)
        assertEquals(0, v.avatarCalls)
    }

    @Test fun emptyClassificationIsNotAllUnselected() = runTest {
        val ctx = context(Vision().apply { skills = emptyList() })
        assertEquals(ActionOutcome.Continue, execute(ctx))
        assertTrue(ctx.fakeInput.clicks().isEmpty())
    }

    @Test fun mixedUnselectedDoesNotHideDangerAndSelectsOnlySafeCard() = runTest {
        val v = Vision().apply {
            skills = skills + BattleSkillIcon("unselected", 300, 550)
            // 650 卡只有侵蚀风险，300 的 0% 属于 400 卡。
            panels += listOf(open(300), open(300), closed)
        }
        val ctx = context(v)
        assertEquals(ActionOutcome.Continue, execute(ctx))
        assertEquals(listOf(100 to 690, 380 to 250, 380 to 250, 30 to 700), ctx.fakeInput.clicks())
        assertEquals(List(4) { KeyEvent.KEYCODE_P }, ctx.fakeInput.keyPresses())
    }

    @Test fun alreadyClosedAfterFirstClickDoesNotClickThroughToBattle() = runTest {
        val v = Vision().apply { panels += listOf(open(300, 550), closed) }
        val ctx = context(v)
        execute(ctx)
        assertEquals(listOf(100 to 690, 630 to 250, 30 to 700), ctx.fakeInput.clicks())
    }

    @Test fun unavailableAvatarDoesNotBorrowPreviousAvatarsEgo() = runTest {
        val v = Vision().apply {
            skills = listOf(BattleSkillIcon("neutral", 250, 550))
            avatars = listOf(BattleSinnerAvatar(100, 690, listOf(150)), BattleSinnerAvatar(200, 690, emptyList()))
        }
        val ctx = context(v)
        execute(ctx)
        assertTrue(ctx.fakeInput.clicks().isEmpty())
        assertEquals(1, ctx.fakeInput.keyPresses().size)
    }

    @Test fun unsafeCardsAreDismissedWithoutSelectionOrExtraP() = runTest {
        val v = Vision().apply { panels += listOf(open(), closed) }
        val ctx = context(v)
        execute(ctx)
        assertEquals(listOf(100 to 690, 100 to 690), ctx.fakeInput.clicks())
        assertEquals(1, ctx.fakeInput.keyPresses().size)
    }

    @Test fun secondSinnerDoesNotInheritFirstSinnersSuccess() = runTest {
        val v = Vision().apply {
            skills = skills + BattleSkillIcon("struggling", 250, 550)
            avatars = avatars + BattleSinnerAvatar(200, 690, listOf(120))
            panels += listOf(open(300), closed, open(), closed)
        }
        val ctx = context(v)
        execute(ctx)
        assertEquals(listOf(100 to 690, 380 to 250, 30 to 700, 200 to 690, 200 to 690), ctx.fakeInput.clicks())
        assertEquals(4, ctx.fakeInput.keyPresses().size)
    }

    @Test fun lostFrameIsNotReportedAsSuccessfulSelection() = runTest {
        val v = Vision().apply { panels += listOf(open(300), null, null) }
        val ctx = context(v)
        val result = execute(ctx)
        assertTrue(result is ActionOutcome.Finish && !result.success)
        assertEquals(1, ctx.fakeInput.keyPresses().size)
        assertFalse(ctx.logs.any { "已确认 EGO" in it })
        assertEquals(listOf(100 to 690, 380 to 250, 100 to 690), ctx.fakeInput.clicks())
    }

    @Test fun cancellationDuringLongPressReleasesTouchAndStopsAllFurtherInput() = runTest {
        val base = context(Vision())
        val ctx = object : ActionContext by base {
            override suspend fun delay(seconds: Double) {
                if (seconds == 3.0) throw CancellationException("stop")
                base.delay(seconds)
            }
        }
        try { execute(ctx); fail("cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(listOf("keyDown(44)", "keyUp(44)", "down(100,690)", "up(100,690)"), base.fakeInput.events)
    }

    @Test fun cancellationAfterFirstSelectionClickDoesNotSendSecondClickOrP() = runTest {
        val base = context(Vision().apply { panels += open(300) })
        val ctx = object : ActionContext by base {
            override suspend fun delay(seconds: Double) {
                if (seconds == .3) throw CancellationException("stop")
                base.delay(seconds)
            }
        }
        try { execute(ctx); fail("cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(listOf(100 to 690, 380 to 250), base.fakeInput.clicks())
        assertEquals(1, base.fakeInput.keyPresses().size)
    }
}
