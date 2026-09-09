package com.aliothmoon.maadroid.engine.arknights.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MaaCoreSessionTest {
    private class FakeCore : MaaCoreClient {
        var instance = false
        var running = false
        var createAccepted = true
        var optionAccepted = true
        var stopAccepted = true
        var finishOnStop = true
        var startAccepted = true
        var callback: (Int, String?) -> Unit = { _, _ -> }
        var onConnect: (Int) -> Unit = {}
        var onStart: () -> Unit = {}
        var callId = 0
        var returnedCallId: Int? = null
        var creates = 0
        var starts = 0
        var stops = 0
        var appends = 0
        var rejectTaskNumber = -1
        val options = mutableListOf<Pair<Int, String>>()
        val queue = mutableListOf<String>()

        override fun hasInstance() = instance
        override fun createInstance(callback: (Int, String?) -> Unit): Boolean {
            creates++
            this.callback = callback
            instance = createAccepted
            return createAccepted
        }
        override fun setInstanceOption(key: Int, value: String): Boolean {
            options += key to value
            return optionAccepted
        }
        override fun asyncConnect(config: String): Int {
            callId++
            onConnect(callId)
            return returnedCallId ?: callId
        }
        override fun appendTask(type: String, params: String): Int {
            appends++
            if (appends == rejectTaskNumber) return 0
            queue += type
            return appends
        }
        override fun start(): Boolean {
            starts++
            onStart()
            running = startAccepted
            return startAccepted
        }
        override fun stop(): Boolean {
            stops++
            if (stopAccepted) {
                queue.clear()
                if (finishOnStop) running = false
            }
            return stopAccepted
        }
        override fun running() = running
        override fun version() = "v6.17.2"
        fun result(id: Int = callId, success: Boolean = true, what: String = "Connect") = callback(
            AsstMsg.AsyncCallInfo.value,
            """{"what":"$what","async_call_id":$id,"details":{"ret":$success}}""",
        )
    }

    private fun session(core: FakeCore, onEvent: (Int, String?) -> Unit = { _, _ -> }) =
        MaaCoreSession(core, onEvent, connectTimeoutMillis = 500, stopTimeoutMillis = 1_000)
            .also { assertEquals(MaaCoreSession.Initialization.READY, it.initialize()) }

    @Test fun initializationReplacesIdleCallbackButReusesItsOwnInstance() {
        val core = FakeCore().apply { instance = true }
        val runtime = session(core)
        assertEquals(1, core.creates)
        assertEquals(listOf(MaaInstanceOptions.TOUCH_MODE to MaaInstanceOptions.ANDROID), core.options)
        assertEquals(MaaCoreSession.Initialization.READY, runtime.initialize())
        assertEquals(1, core.creates)
    }

    @Test fun initializationNeverReplacesRunningInstance() {
        val core = FakeCore().apply { instance = true; running = true }
        val runtime = MaaCoreSession(core, { _, _ -> })
        assertEquals(MaaCoreSession.Initialization.BUSY, runtime.initialize())
        assertEquals(0, core.creates)
        assertEquals(0, core.stops)
    }

    @Test fun initializationFailuresCanBeRetriedWithoutSkippingTouchMode() {
        val core = FakeCore().apply { createAccepted = false }
        val runtime = MaaCoreSession(core, { _, _ -> })
        assertEquals(MaaCoreSession.Initialization.CREATE_FAILED, runtime.initialize())
        core.createAccepted = true
        core.optionAccepted = false
        assertEquals(MaaCoreSession.Initialization.TOUCH_MODE_FAILED, runtime.initialize())
        core.optionAccepted = true
        assertEquals(MaaCoreSession.Initialization.READY, runtime.initialize())
        assertEquals(3, core.creates)
    }

    @Test fun matchingCallbackCanArriveBeforeAsyncConnectReturns() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        core.onConnect = { id ->
            core.result(id + 12, success = false)
            core.result(id)
        }
        assertTrue(runtime.connect("{}", true))
        assertEquals(MaaInstanceOptions.DEPLOYMENT_WITH_PAUSE to "1", core.options.last())
        assertTrue(runtime.connect("{}", false))
        assertEquals(MaaInstanceOptions.DEPLOYMENT_WITH_PAUSE to "0", core.options.last())
    }

    @Test fun unrelatedOperationsIdsAndMalformedCallbacksDoNotCompleteConnection() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        val connection = async { runtime.connect("{}", false) }
        runCurrent()
        core.result(what = "Click")
        core.result(what = "Screencap")
        core.result(id = core.callId + 1)
        core.callback(AsstMsg.AsyncCallInfo.value, "not json")
        core.callback(AsstMsg.AsyncCallInfo.value, "[]")
        core.callback(AsstMsg.AsyncCallInfo.value, """{"what":"Connect","async_call_id":1,"details":true}""")
        core.callback(AsstMsg.AsyncCallInfo.value, """{"what":"Connect","async_call_id":1,"details":{}}""")
        runCurrent()
        assertFalse(connection.isCompleted)
        core.result(success = false)
        assertFalse(connection.await())
    }

    @Test fun timeoutRequiresNativeCompletionBeforeRetryingConnect() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        assertFalse(runtime.connect("{}", false))
        val timedOutId = core.callId
        assertFalse(runtime.connect("{}", false))
        assertEquals(timedOutId, core.callId)
        core.result(id = timedOutId)
        val retry = async { runtime.connect("{}", false) }
        runCurrent()
        core.result(id = timedOutId)
        runCurrent()
        assertFalse(retry.isCompleted)
        core.result()
        assertTrue(retry.await())
    }

    @Test fun cancelledWaiterCannotRetryBeforeNativeConnectFinishes() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        val abandoned = launch { runtime.connect("{}", false) }
        runCurrent()
        val abandonedId = core.callId
        abandoned.cancelAndJoin()
        assertFalse(runtime.connect("{}", false))
        assertEquals(abandonedId, core.callId)
        core.result(id = abandonedId)
        val retry = async { runtime.connect("{}", false) }
        runCurrent()
        core.result(id = abandonedId)
        runCurrent()
        assertFalse(retry.isCompleted)
        core.result()
        assertTrue(retry.await())
    }

    @Test fun invalidAsyncIdFailsEvenWithAnEarlySuccessfulCallback() = runTest {
        val core = FakeCore().apply { returnedCallId = 0 }
        val runtime = session(core)
        core.onConnect = { core.result(it) }
        assertFalse(runtime.connect("{}", false))
    }

    @Test fun ambiguousBinderFailureRequiresInstanceInvalidationBeforeRetry() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        core.onConnect = { throw IllegalStateException("binder died") }
        try {
            runtime.connect("{}", false)
            fail("Expected Binder failure")
        } catch (expected: IllegalStateException) {
            assertEquals("binder died", expected.message)
        }
        assertFalse(runtime.connect("{}", false))
        runtime.invalidate()
        assertEquals(MaaCoreSession.Initialization.READY, runtime.initialize())
        core.onConnect = { core.result(it) }
        assertTrue(runtime.connect("{}", false))
    }

    @Test fun pauseOptionFailureDoesNotAttemptConnect() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        core.optionAccepted = false
        assertFalse(runtime.connect("{}", false))
        assertEquals(0, core.callId)
    }

    @Test fun invalidationReleasesWaiterAndIgnoresOldCallbacksAfterReinitialization() = runTest {
        val core = FakeCore()
        val events = mutableListOf<Int>()
        val runtime = session(core) { msg, _ -> events += msg }
        val oldCallback = core.callback
        val pending = async { runtime.connect("{}", false) }
        runCurrent()
        runtime.invalidate()
        assertFalse(pending.await())
        assertEquals(MaaCoreSession.Initialization.READY, runtime.initialize())
        oldCallback(AsstMsg.AllTasksCompleted.value, "{}")
        assertTrue(events.isEmpty())
        core.callback(AsstMsg.TaskChainStart.value, "{}")
        assertEquals(listOf(AsstMsg.TaskChainStart.value), events)
    }

    @Test fun taskIdsAreRegisteredBeforeNativeStartAndCallbacksRemainSynchronous() {
        val core = FakeCore()
        val calls = mutableListOf<String>()
        val runtime = session(core) { _, _ -> calls += "callback" }
        core.onStart = {
            assertEquals(listOf("0:1", "1:2"), calls)
            core.callback(AsstMsg.TaskChainStart.value, "{}")
            assertEquals("callback", calls.last())
        }
        assertEquals(MaaCoreSession.StartResult.Started, runtime.startTasks(
            listOf(MaaCoreSession.Task("Fight", "{}"), MaaCoreSession.Task("Recruit", "{}")),
        ) { index, id -> calls += "$index:$id" })
        assertTrue(runtime.isRunning)
    }

    @Test fun rejectedTaskNeverStartsAPartialQueueAndNextRunIsClean() {
        val core = FakeCore().apply { rejectTaskNumber = 2 }
        val runtime = session(core)
        val tasks = listOf("Fight", "Recruit", "Infrast").map { MaaCoreSession.Task(it, "{}") }
        assertEquals(MaaCoreSession.StartResult.RejectedTask(1), runtime.startTasks(tasks) { _, _ -> })
        assertEquals(0, core.starts)
        assertEquals(2, core.appends)
        assertTrue(core.queue.isEmpty())
        core.rejectTaskNumber = -1
        assertEquals(MaaCoreSession.StartResult.Started, runtime.startTasks(tasks) { _, _ -> })
        assertEquals(listOf("Fight", "Recruit", "Infrast"), core.queue)
    }

    @Test fun registrationFailureAlsoClearsQueuedTasks() {
        val core = FakeCore()
        val runtime = session(core)
        try {
            runtime.startTasks(listOf(MaaCoreSession.Task("Fight", "{}"))) { _, _ -> error("registration") }
            fail("Expected registration failure")
        } catch (expected: IllegalStateException) {
            assertEquals("registration", expected.message)
        }
        assertTrue(core.queue.isEmpty())
        assertEquals(0, core.starts)
    }

    @Test fun nativeStartFailureClearsQueue() {
        val core = FakeCore().apply { startAccepted = false }
        val runtime = session(core)
        assertEquals(MaaCoreSession.StartResult.Failed,
            runtime.startTasks(listOf(MaaCoreSession.Task("Fight", "{}"))) { _, _ -> })
        assertTrue(core.queue.isEmpty())
    }

    @Test fun busyInstanceIsNotStoppedOrAppendedTo() {
        val core = FakeCore()
        val runtime = session(core)
        core.running = true
        assertEquals(MaaCoreSession.StartResult.Busy,
            runtime.startTasks(listOf(MaaCoreSession.Task("Fight", "{}"))) { _, _ -> })
        assertEquals(0, core.stops)
        assertEquals(0, core.appends)
    }

    @Test fun stopFailureCanBeRetriedWithoutPretendingTheCoreIsIdle() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        core.running = true
        core.stopAccepted = false
        assertFalse(runtime.stop())
        assertTrue(runtime.isRunning)
        core.stopAccepted = true
        assertTrue(runtime.stop())
        assertFalse(runtime.isRunning)
    }

    @Test fun stopWaitsForActualIdleAndTimeoutStillReportsFailure() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        core.running = true
        core.finishOnStop = false
        val stopped = async { runtime.stop() }
        runCurrent()
        advanceTimeBy(200)
        assertFalse(stopped.isCompleted)
        core.running = false
        assertTrue(stopped.await())
        core.running = true
        assertFalse(runtime.stop())
        assertTrue(runtime.isRunning)
    }

    @Test fun stopClearsUnstartedQueueAndMissingInstanceNeedsNoNativeCall() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        core.queue += "Fight"
        assertTrue(runtime.stop())
        assertTrue(core.queue.isEmpty())
        core.instance = false
        core.stops = 0
        assertTrue(runtime.stop())
        assertEquals(0, core.stops)
    }

    @Test fun cancelledConnectKeepsStopPendingEvenWhenNativeRunningIsFalse() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        val connection = launch { runtime.connect("{}", false) }
        runCurrent()
        connection.cancelAndJoin()
        assertFalse(core.running)
        val stopping = async { runtime.stop() }
        runCurrent()
        assertFalse(stopping.isCompleted)
        assertEquals(0, core.stops)
        core.result()
        assertTrue(stopping.await())
        assertEquals(1, core.stops)
    }

    @Test fun unconfirmedAsyncConnectTimesOutStopAndCanBeRetriedAfterItsCallback() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        assertFalse(runtime.connect("{}", false))
        assertFalse(runtime.stop())
        assertEquals(0, core.stops)
        core.result(success = false)
        assertTrue(runtime.stop())
    }

    @Test fun destroyedCallbackReleasesPendingStopWithoutCallingTheDestructingInstance() = runTest {
        val core = FakeCore()
        val runtime = session(core)
        assertFalse(runtime.connect("{}", false))
        val stopping = async { runtime.stop() }
        runCurrent()
        // ServiceImpl 尚在 AsstDestroy 内，hasInstance 直到返回后才会变为 false。
        core.callback(AsstMsg.Destroyed.value, "{}")
        assertTrue(stopping.await())
        assertEquals(0, core.stops)
    }
}
