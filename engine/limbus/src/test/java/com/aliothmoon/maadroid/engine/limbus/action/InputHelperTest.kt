package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.InputSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InputHelperTest {
    private data class Event(val action: String, val at: Long) {
        val isRelease get() = action.startsWith("up(") || action.startsWith("keyUp(")
    }

    private class TimedInput(private val clock: () -> Long) : InputSink {
        val events = mutableListOf<Event>()
        var onEvent: (Event) -> Unit = {}

        private fun record(action: String) {
            val event = Event(action, clock())
            events += event
            onEvent(event)
        }

        override fun touchDown(x: Int, y: Int, contact: Int) = record("down($x,$y,$contact)")
        override fun touchMove(x: Int, y: Int, contact: Int) = record("move($x,$y,$contact)")
        override fun touchUp(x: Int, y: Int, contact: Int) = record("up($x,$y,$contact)")
        override fun touchCancel() = record("cancel")
        override fun keyDown(keyCode: Int) = record("keyDown($keyCode)")
        override fun keyUp(keyCode: Int) = record("keyUp($keyCode)")
    }

    private data class Gesture(val name: String, val perform: suspend (InputSink) -> Unit)

    private val gestures = listOf(
        Gesture("click") { InputHelper.click(it, 11, 90) },
        Gesture("key") { InputHelper.keyPress(it, "enter") },
        Gesture("swipe") { InputHelper.swipe(it, 11, 90, 75, 10) },
        Gesture("longPress") { input ->
            InputHelper.longPress(input, 11, 90, 1.5) { delay((it * 1_000).toLong()) }
        },
    )

    @Test fun clickAndKeyHoldFor100MillisBeforeReleasing() = runTest {
        for (gesture in gestures.take(2)) {
            val input = TimedInput { currentTime }
            val start = currentTime
            val job = launch { gesture.perform(input) }
            runCurrent()
            val down = if (gesture.name == "click") "down(11,90,0)" else "keyDown(66)"
            val up = if (gesture.name == "click") "up(11,90,0)" else "keyUp(66)"
            assertEquals(listOf(Event(down, start)), input.events)
            advanceTimeBy(99)
            runCurrent()
            assertEquals(1, input.events.size)
            assertFalse(job.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(Event(down, start), Event(up, start + 100)), input.events)
            assertTrue(job.isCompleted)
        }
    }

    @Test fun defaultSwipePreservesCoordinatesAndSpacesEveryMoveAndRelease() = runTest {
        val input = TimedInput { currentTime }
        InputHelper.swipe(input, 11, 90, 75, 10)
        assertEquals(listOf(
            Event("down(11,90,0)", 0),
            Event("move(19,80,0)", 138),
            Event("move(27,70,0)", 176),
            Event("move(35,60,0)", 214),
            Event("move(43,50,0)", 252),
            Event("move(51,40,0)", 289),
            Event("move(59,30,0)", 326),
            Event("move(67,20,0)", 363),
            Event("move(75,10,0)", 400),
            Event("up(75,10,0)", 500),
        ), input.events)
    }

    @Test fun customSwipeStepsRetainInterpolationAndAtLeastAFrameBetweenMoves() = runTest {
        val input = TimedInput { currentTime }
        InputHelper.swipe(input, -11, 12, 7, -9, steps = 3)
        assertEquals(listOf(
            Event("down(-11,12,0)", 0),
            Event("move(-5,5,0)", 200),
            Event("move(1,-2,0)", 300),
            Event("move(7,-9,0)", 400),
            Event("up(7,-9,0)", 500),
        ), input.events)

        input.events.clear()
        InputHelper.swipe(input, 0, 0, 100, 100, steps = 20)
        val moves = input.events.filter { it.action.startsWith("move(") }
        assertEquals(20, moves.size)
        assertTrue(moves.zipWithNext().all { (a, b) -> b.at - a.at >= 16 })
        assertEquals(Event("move(100,100,0)", 920), moves.last())
        assertEquals(Event("up(100,100,0)", 1_020), input.events.last())
    }

    @Test fun invalidSwipeStepsAndUnknownKeysDoNotInjectAnything() = runTest {
        val input = TimedInput { currentTime }
        for (steps in listOf(0, -1)) {
            assertTrue(runCatching { InputHelper.swipe(input, 1, 2, 3, 4, steps) }
                .exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { InputHelper.keyPress(input, "unknown") }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(input.events.isEmpty())
        assertEquals(0L, currentTime)
    }

    @Test fun cancellationDuringInitialHoldReleasesEveryGestureImmediately() = runTest {
        for (gesture in gestures) {
            val input = TimedInput { currentTime }
            val start = currentTime
            val job = async { gesture.perform(input) }
            runCurrent()
            advanceTimeBy(50)
            val cancellation = CancellationException("stop ${gesture.name}")
            job.cancel(cancellation)
            runCurrent()
            assertCancellationPreserved(cancellation, runCatching { job.await() }.exceptionOrNull())
            assertEquals(listOf(start, start + 50), input.events.map { it.at })
            assertTrue(input.events.last().isRelease)
            val released = input.events.toList()
            advanceUntilIdle()
            assertEquals(released, input.events)
        }
    }

    @Test fun cancellationMidSwipeReleasesAtLastPositionWithoutJumpingToTheEnd() = runTest {
        val input = TimedInput { currentTime }
        val job = launch { InputHelper.swipe(input, 11, 90, 75, 10) }
        runCurrent()
        advanceTimeBy(200)
        job.cancel()
        runCurrent()
        assertEquals(listOf(
            Event("down(11,90,0)", 0),
            Event("move(19,80,0)", 138),
            Event("move(27,70,0)", 176),
            Event("up(27,70,0)", 200),
        ), input.events)
        advanceUntilIdle()
        assertEquals(4, input.events.size)
    }

    @Test fun alreadyCancelledCoroutineDoesNotSendDownOrUnpairedUp() = runTest {
        for (gesture in gestures) {
            val input = TimedInput { currentTime }
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                gesture.perform(input)
            }
            job.join()
            assertTrue(gesture.name, input.events.isEmpty())
        }
    }

    @Test fun downFailureStillAttemptsReleaseAndPreservesTheOriginalFailure() = runTest {
        for (gesture in gestures) {
            for (cleanupMode in listOf("success", "distinct", "same")) {
                val input = TimedInput { currentTime }
                val failure = IllegalStateException("down failed")
                val cleanup = when (cleanupMode) {
                    "distinct" -> IllegalArgumentException("up failed")
                    "same" -> failure
                    else -> null
                }
                input.onEvent = { event ->
                    if (!event.isRelease) throw failure
                    if (cleanup != null) throw cleanup
                }
                assertSame(gesture.name, failure, runCatching { gesture.perform(input) }.exceptionOrNull())
                assertEquals(2, input.events.size)
                assertTrue(input.events.last().isRelease)
                assertEquals(input.events.first().at, input.events.last().at)
                assertEquals(if (cleanupMode == "distinct") listOf(cleanup) else emptyList<Throwable>(),
                    failure.suppressed.toList())
            }
        }
    }

    @Test fun moveFailureReleasesAttemptedPositionAndSuppressesCleanupFailure() = runTest {
        val input = TimedInput { currentTime }
        val failure = IllegalStateException("move failed after injection")
        val cleanup = IllegalArgumentException("up failed")
        input.onEvent = { event ->
            if (event.action == "move(35,60,0)") throw failure
            if (event.isRelease) throw cleanup
        }
        assertSame(failure, runCatching { InputHelper.swipe(input, 11, 90, 75, 10) }.exceptionOrNull())
        assertEquals(listOf(Event("move(35,60,0)", 214), Event("up(35,60,0)", 214)), input.events.takeLast(2))
        assertEquals(5, input.events.size)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
    }

    @Test fun releaseFailureCannotReplaceCancellation() = runTest {
        for (gesture in gestures) {
            for (sameFailure in listOf(false, true)) {
                val input = TimedInput { currentTime }
                val cancellation = CancellationException("stop ${gesture.name}")
                val cleanup = if (sameFailure) cancellation else IllegalStateException("up failed")
                input.onEvent = { if (it.isRelease) throw cleanup }
                var gestureFailure: Throwable? = null
                val job = async {
                    try {
                        gesture.perform(input)
                    } catch (failure: Throwable) {
                        gestureFailure = failure
                        throw failure
                    }
                }
                runCurrent()
                advanceTimeBy(50)
                job.cancel(cancellation)
                runCurrent()
                assertCancellationPreserved(cancellation, runCatching { job.await() }.exceptionOrNull())
                assertCancellationPreserved(cancellation, gestureFailure)
                assertEquals(2, input.events.size)
                assertTrue(input.events.last().isRelease)
                val suppressed = requireNotNull(gestureFailure).suppressed.toList()
                if (sameFailure) {
                    assertTrue(suppressed.all { it === cancellation })
                } else {
                    assertEquals(listOf(cleanup), suppressed)
                }
            }
        }
    }

    @Test fun releaseFailureOnOtherwiseSuccessfulGestureIsReported() = runTest {
        for (gesture in gestures) {
            val input = TimedInput { currentTime }
            val failure = IllegalStateException("up failed")
            input.onEvent = { if (it.isRelease) throw failure }
            assertSame(gesture.name, failure, runCatching { gesture.perform(input) }.exceptionOrNull())
            assertEquals(1, input.events.count { it.isRelease })
        }
    }

    @Test fun clickAndKeyRepeatKeepCountsAndCallerSpecifiedIntervals() = runTest {
        for (key in listOf(false, true)) {
            val input = TimedInput { currentTime }
            val start = currentTime
            val intervals = mutableListOf<Double>()
            val wait: suspend (Double) -> Unit = {
                intervals += it
                delay((it * 1_000).toLong())
            }
            if (key) InputHelper.keyRepeat(input, "enter", 3, 0.25, wait)
            else InputHelper.clickRepeat(input, 11, 90, 3, 0.25, wait)
            assertEquals(listOf(0L, 100L, 350L, 450L, 700L, 800L), input.events.map { it.at - start })
            assertEquals(listOf(0.25, 0.25), intervals)
            assertEquals(3, input.events.count { it.isRelease })
        }
    }

    @Test fun nonPositiveRepeatStillPerformsTheInitialGestureAndZeroIntervalAddsNoWait() = runTest {
        for (key in listOf(false, true)) {
            for (count in listOf(-1, 0, 1, 3)) {
                val input = TimedInput { currentTime }
                val start = currentTime
                val wait: suspend (Double) -> Unit = { fail("Zero interval must not call delay") }
                if (key) InputHelper.keyRepeat(input, "enter", count, 0.0, wait)
                else InputHelper.clickRepeat(input, 11, 90, count, 0.0, wait)
                val expected = maxOf(count, 1)
                assertEquals(expected, input.events.count { it.isRelease })
                assertEquals(expected * 100L, currentTime - start)
            }
        }
    }

    @Test fun cancellationInRepeatIntervalOrNextHoldDoesNotStartLaterRepetitions() = runTest {
        for (key in listOf(false, true)) {
            for (cancelAt in listOf(200L, 400L)) {
                val input = TimedInput { currentTime }
                val start = currentTime
                val job = launch {
                    val wait: suspend (Double) -> Unit = { delay((it * 1_000).toLong()) }
                    if (key) InputHelper.keyRepeat(input, "enter", 3, 0.25, wait)
                    else InputHelper.clickRepeat(input, 11, 90, 3, 0.25, wait)
                }
                runCurrent()
                advanceTimeBy(cancelAt)
                job.cancel()
                runCurrent()
                advanceUntilIdle()
                val expected = if (cancelAt == 200L) listOf(0L, 100L) else listOf(0L, 100L, 350L, 400L)
                assertEquals(expected, input.events.map { it.at - start })
                assertTrue(input.events.last().isRelease)
            }
        }
    }

    @Test fun longPressKeepsItsRequestedDurationAndReleasesWhenDelayFails() = runTest {
        val input = TimedInput { currentTime }
        InputHelper.longPress(input, 11, 90, 0.37) {
            assertEquals(0.37, it, 0.0)
            delay((it * 1_000).toLong())
        }
        assertEquals(listOf(Event("down(11,90,0)", 0), Event("up(11,90,0)", 370)), input.events)
        input.events.clear()
        val failure = IllegalStateException("delay failed")
        assertSame(failure, runCatching {
            InputHelper.longPress(input, 11, 90, 1.5) { delay(50); throw failure }
        }.exceptionOrNull())
        assertEquals(listOf(Event("down(11,90,0)", 370), Event("up(11,90,0)", 420)), input.events)
    }

    private fun assertCancellationPreserved(expected: CancellationException, actual: Throwable?) {
        // Coroutine debug stack recovery may copy CancellationException at suspend/await
        // boundaries. Its cause must still be the original stop request, never UP's failure.
        assertTrue(actual is CancellationException)
        assertEquals(expected.message, actual?.message)
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }
}
