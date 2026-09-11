package com.maadroid.app.engine.limbus.action

import com.maadroid.app.engine.limbus.recognize.GameLanguageObservation
import com.maadroid.app.engine.limbus.recognize.Recognizer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GameLanguageActionTest {
    @Before fun install() {
        ActionRegistry.clearForTest()
        LimbusActions.resetForTest()
        LimbusActions.install()
    }

    private fun context(vararg frames: GameLanguageObservation): TestActionContext {
        val pending = ArrayDeque(frames.toList())
        val recognizer = object : Recognizer by FakeRecognizer() {
            override suspend fun observeGameLanguage(): GameLanguageObservation =
                if (pending.isEmpty()) GameLanguageObservation.Uncertain else pending.removeFirst()
        }
        return TestActionContext(
            node = nodeWith("""{"template":"main_drive_with_text","error_msg":"Wrong language"}""", "report_error").copy(inverse = true),
            nodeName = "game_language_confirm", recognize = recognizer,
        )
    }

    @Test fun transitionFollowedByCorrectLanguageReturnsFromErrorBranch() = runTest {
        val ctx = context(GameLanguageObservation.Uncertain, GameLanguageObservation.Confirmed)
        assertEquals(ActionOutcome.Return, ActionRegistry["report_error"]!!.execute(ctx))
        assertEquals(listOf(.5), ctx.slept)
        assertFalse(ctx.logs.any { it.contains("Wrong language") })
    }

    @Test fun confirmationOnLastAttemptStillReturnsToCaller() = runTest {
        val ctx = context(GameLanguageObservation.Uncertain, GameLanguageObservation.Uncertain, GameLanguageObservation.Confirmed)
        assertEquals(ActionOutcome.Return, ActionRegistry["report_error"]!!.execute(ctx))
        assertEquals(listOf(.5, .5), ctx.slept)
    }

    @Test fun explicitMismatchIdentifiesBothLanguagesAndCorrectSettingsLocation() = runTest {
        val ctx = context(GameLanguageObservation.Mismatch("zh", "en"))
        val result = ActionRegistry["report_error"]!!.execute(ctx) as ActionOutcome.Finish
        assertFalse(result.success)
        assertTrue(result.message!!.contains("游戏画面为英文"))
        assertTrue(result.message!!.contains("任务配置为中文"))
        assertTrue(result.message!!.contains("更多"))
        assertTrue(ctx.slept.isEmpty())
    }

    @Test fun unclearScreenStopsAfterBoundedRetriesWithoutClaimingMismatch() = runTest {
        val ctx = context()
        val result = ActionRegistry["report_error"]!!.execute(ctx) as ActionOutcome.Finish
        assertFalse(result.success)
        assertTrue(result.message!!.startsWith("暂时无法识别主页导航"))
        assertEquals(listOf(.5, .5), ctx.slept)
    }

    @Test fun userStopPreventsFurtherRecognitionOrRetry() = runTest {
        val ctx = context(GameLanguageObservation.Confirmed).apply { cancelled = true }
        assertTrue(runCatching { ActionRegistry["report_error"]!!.execute(ctx) }.isFailure)
        assertTrue(ctx.slept.isEmpty())
    }
}
