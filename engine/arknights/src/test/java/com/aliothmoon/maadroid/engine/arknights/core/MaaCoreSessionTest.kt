package com.aliothmoon.maadroid.engine.arknights.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        var paramsAccepted = true
        var callback: (Int, String?) -> Unit = { _, _ -> }
        var onConnect: (Int) -> Unit = {}
        var onAppend: () -> Unit = {}
        var onSetParams: () -> Unit = {}
        var onStart: () -> Unit = {}
        var onStop: () -> Unit = {}
        var onRunning: () -> Unit = {}
        var callId = 0
        var returnedCallId: Int? = null
        var creates = 0
        var starts = 0
        var stops = 0
        var appends = 0
        var runningReads = 0
        var rejectTaskNumber = -1
        val returnedTaskIds = ArrayDeque<Int>()
        val options = mutableListOf<Pair<Int, String>>()
        val queue = mutableListOf<String>()
        val appendedTasks = mutableListOf<Pair<String, String>>()
        val parameterUpdates = mutableListOf<Pair<Int, String>>()
        val taskParams = mutableMapOf<Int, String>()

        override fun hasInstance() = instance
        override fun createInstance(callback: (Int, String?) -> Unit): Boolean {
            creates++
            this.callback = callback
            instance = createAccepted
            queue.clear()
            taskParams.clear()
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
            appendedTasks += type to params
            onAppend()
            if (appends == rejectTaskNumber) return 0
            val taskId = returnedTaskIds.removeFirstOrNull() ?: appends
            if (taskId <= 0) return taskId
            queue += type
            taskParams[taskId] = params
            return taskId
        }
        override fun setTaskParams(taskId: Int, params: String): Boolean {
            parameterUpdates += taskId to params
            onSetParams()
            if (!paramsAccepted || taskId !in taskParams) return false
            taskParams[taskId] = params
            return true
        }
        override fun start(): Boolean {
            starts++
            running = startAccepted
            onStart()
            return startAccepted
        }
        override fun stop(): Boolean {
            stops++
            onStop()
            if (stopAccepted) {
                queue.clear()
                taskParams.clear()
                if (finishOnStop) running = false
            }
            return stopAccepted
        }
        override fun running(): Boolean {
            runningReads++
            onRunning()
            return running
        }
        override fun version() = "v6.17.2"
        fun result(id: Int = callId, success: Boolean = true, what: String = "Connect") = callback(
            AsstMsg.AsyncCallInfo.value,
            """{"what":"$what","async_call_id":$id,"details":{"ret":$success}}""",
        )
    }

    private fun session(core: FakeCore, onEvent: (Int, String?) -> Unit = { _, _ -> }) =
        MaaCoreSession(core, onEvent, connectTimeoutMillis = 500, stopTimeoutMillis = 1_000)
            .also { assertEquals(MaaCoreSession.Initialization.READY, it.initialize()) }

    private val tasks = listOf(MaaCoreSession.Task("Fight", "{}"))

    private fun assertQueueCallsRequireInitialization(runtime: MaaCoreSession, core: FakeCore) {
        val before = listOf(core.stops, core.appends, core.starts, core.parameterUpdates.size, core.runningReads)
        val calls = listOf<() -> Any>(
            { runtime.clearTasks() },
            { runtime.appendTask("Fight", "{}") },
            { runtime.setTaskParams(41, "{}") },
            { runtime.startQueuedTasks() },
            { runtime.startTasks(tasks) { _, _ -> fail("Uninitialized task was registered") } },
        )
        calls.forEach { call ->
            val failure = assertThrows(IllegalStateException::class.java) { call() }
            assertEquals("MaaCore session has not been initialized", failure.message)
        }
        assertEquals(before, listOf(core.stops, core.appends, core.starts, core.parameterUpdates.size, core.runningReads))
    }

    private fun assertQueueChangesAreBlocked(runtime: MaaCoreSession, core: FakeCore) {
        val before = listOf(core.stops, core.appends, core.starts)
        assertFalse(runtime.clearTasks())
        assertEquals(0, runtime.appendTask("Fight", "{}"))
        assertFalse(runtime.startQueuedTasks())
        assertEquals(MaaCoreSession.StartResult.Busy,
            runtime.startTasks(tasks) { _, _ -> fail("Busy task was registered") })
        assertEquals(before, listOf(core.stops, core.appends, core.starts))
    }

    private fun assertAllTaskCallsAreBlockedByConnect(runtime: MaaCoreSession, core: FakeCore) {
        assertTrue(runtime.isBusy)
        assertQueueChangesAreBlocked(runtime, core)
        val before = core.parameterUpdates.size
        assertFalse(runtime.setTaskParams(41, "{}"))
        assertEquals(before, core.parameterUpdates.size)
    }

    @Test fun taskEntrypointsRequireInitializationBeforeTouchingNative() {
        val core = FakeCore()
        val runtime = MaaCoreSession(core, { _, _ -> })
        assertQueueCallsRequireInitialization(runtime, core)
        core.createAccepted = false
        assertEquals(MaaCoreSession.Initialization.CREATE_FAILED, runtime.initialize())
        assertQueueCallsRequireInitialization(runtime, core)
        core.createAccepted = true
        core.optionAccepted = false
        assertEquals(MaaCoreSession.Initialization.TOUCH_MODE_FAILED, runtime.initialize())
        assertQueueCallsRequireInitialization(runtime, core)
    }

    @Test fun invalidationAndDestructionBlockTaskCallsUntilReinitialized() {
        val core = FakeCore()
        val runtime = session(core)
        runtime.invalidate()
        assertQueueCallsRequireInitialization(runtime, core)
        assertEquals(MaaCoreSession.Initialization.READY, runtime.initialize())
        core.callback(AsstMsg.Destroyed.value, "{}")
        assertQueueCallsRequireInitialization(runtime, core)
        assertEquals(MaaCoreSession.Initialization.READY, runtime.initialize())
        assertTrue(runtime.clearTasks())
        val taskId = runtime.appendTask("Fight", "{}")
        assertTrue(taskId > 0)
        assertTrue(runtime.setTaskParams(taskId, "{\"enable\":false}"))
        assertTrue(runtime.startQueuedTasks())
    }

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

    @Test fun taskCallsWaitForAsyncConnectToReturnEvenAfterAnEarlyCompletion() = runTest {
        for (success in listOf(true, false)) {
            val core = FakeCore()
            val runtime = session(core)
            core.onConnect = { id ->
                assertAllTaskCallsAreBlockedByConnect(runtime, core)
                core.result(id, success)
                // AsyncConnect 尚未返回 id，早到回调还不能解除 pending 状态。
                assertAllTaskCallsAreBlockedByConnect(runtime, core)
            }
            assertEquals(success, runtime.connect("{}", false))
            assertFalse(runtime.isBusy)
            assertTrue(runtime.clearTasks())
            core.returnedTaskIds += 41
            assertEquals(41, runtime.appendTask("Fight", "{}"))
            assertTrue(runtime.setTaskParams(41, "{\"enable\":false}"))
            assertTrue(runtime.startQueuedTasks())
        }
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
        assertAllTaskCallsAreBlockedByConnect(runtime, core)
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
        assertAllTaskCallsAreBlockedByConnect(runtime, core)
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
        assertAllTaskCallsAreBlockedByConnect(runtime, core)
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
        for (incremental in listOf(true, false)) {
            val core = FakeCore().apply { returnedTaskIds.addAll(listOf(41, 901)) }
            val registered = mutableListOf<Pair<Int, Int>>()
            val params = "{\"stage\":\"1-7\",\"times\":2}"
            val updated = "{\"stage\":\"1-7\",\"times\":1}"
            val payload = """{"taskid":901,"taskchain":"Fight"}"""
            val tasks = listOf(MaaCoreSession.Task("Fight", params), MaaCoreSession.Task("Fight", params))
            lateinit var runtime: MaaCoreSession
            runtime = session(core) { msg, json ->
                assertEquals(AsstMsg.TaskChainStart.value, msg)
                assertEquals(payload, json)
                assertTrue(runtime.setTaskParams(registered.last().second, updated))
            }
            // Binder 回调来自另一线程，并须在 Start 返回前完成参数回写。
            val callbacks = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "fake-maa-callback").apply { isDaemon = true }
            }
            try {
                core.onStart = {
                    assertTrue(core.running)
                    assertEquals(listOf(0 to 41, 1 to 901), registered)
                    callbacks.submit { core.callback(AsstMsg.TaskChainStart.value, payload) }
                        .get(2, TimeUnit.SECONDS)
                    assertEquals(listOf(901 to updated), core.parameterUpdates)
                    assertEquals(updated, core.taskParams[901])
                }
                if (incremental) {
                    assertTrue(runtime.clearTasks())
                    tasks.forEachIndexed { index, task ->
                        registered += index to runtime.appendTask(task.type, task.params)
                    }
                    assertTrue(runtime.startQueuedTasks())
                } else {
                    assertEquals(MaaCoreSession.StartResult.Started,
                        runtime.startTasks(tasks) { index, id -> registered += index to id })
                }
                assertEquals(tasks.map { it.type to it.params }, core.appendedTasks)
                assertTrue(runtime.isRunning)
            } finally {
                callbacks.shutdownNow()
            }
        }
    }

    @Test fun incrementalRejectionsPreserveNativeResultsAndLeaveCleanupToTheCaller() {
        val core = FakeCore().apply { returnedTaskIds.addAll(listOf(41, 0, -7)) }
        val runtime = session(core)
        assertTrue(runtime.clearTasks())
        val params = " {\"enable\":true} "
        assertEquals(41, runtime.appendTask("Fight", params))
        assertEquals(params, core.taskParams[41])
        assertEquals(0, runtime.appendTask("Recruit", "{}"))
        assertEquals(-7, runtime.appendTask("Infrast", "{}"))
        assertEquals(listOf("Fight"), core.queue)

        core.paramsAccepted = false
        assertFalse(runtime.setTaskParams(41, "{\"enable\":false}"))
        assertEquals(params, core.taskParams[41])
        core.startAccepted = false
        assertFalse(runtime.startQueuedTasks())
        assertEquals(listOf("Fight"), core.queue)
        assertEquals(1, core.stops)
        core.stopAccepted = false
        assertFalse(runtime.clearTasks())
        assertEquals(listOf("Fight"), core.queue)
        core.stopAccepted = true
        assertTrue(runtime.clearTasks())
        assertTrue(core.queue.isEmpty())
        assertTrue(core.taskParams.isEmpty())
    }

    @Test fun parameterRefreshUsesNativeIdsAndDoesNotQueryRunning() {
        val core = FakeCore().apply { returnedTaskIds += 901 }
        val runtime = session(core)
        assertEquals(901, runtime.appendTask("Fight", "{}"))
        core.running = true
        val before = core.runningReads
        assertFalse(runtime.setTaskParams(0, "{}"))
        assertFalse(runtime.setTaskParams(-1, "{}"))
        assertTrue(core.parameterUpdates.isEmpty())
        // 正 ID 是否还存在由 native 决定，不能把队列下标或本地计数当作 taskId。
        assertFalse(runtime.setTaskParams(1, "{}"))
        val params = " {\"drops\":{\"30011\":5}} "
        assertTrue(runtime.setTaskParams(901, params))
        assertEquals(listOf(1 to "{}", 901 to params), core.parameterUpdates)
        assertEquals(params, core.taskParams[901])
        assertEquals(before, core.runningReads)
    }

    @Test fun incrementalNativeExceptionsArePropagatedWithoutImplicitCleanup() {
        for (operation in listOf("clear", "append", "params", "start")) {
            val core = FakeCore()
            val runtime = session(core)
            val taskId = runtime.appendTask("Fight", "{}")
            val failure = IllegalStateException("$operation binder failure")
            val call: () -> Any = when (operation) {
                "clear" -> { core.onStop = { throw failure }; { runtime.clearTasks() } }
                "append" -> { core.onAppend = { throw failure }; { runtime.appendTask("Recruit", "{}") } }
                "params" -> { core.onSetParams = { throw failure }; { runtime.setTaskParams(taskId, "{}") } }
                else -> { core.onStart = { throw failure }; { runtime.startQueuedTasks() } }
            }
            assertSame(failure, assertThrows(IllegalStateException::class.java) { call() })
            assertEquals(listOf("Fight"), core.queue)
            assertEquals(if (operation == "clear") 1 else 0, core.stops)
            assertTrue(failure.suppressed.isEmpty())
        }
    }

    @Test fun failedRunningQueryNeverPermitsQueueMutation() {
        val core = FakeCore()
        val runtime = session(core)
        val failure = IllegalStateException("running binder failure")
        core.onRunning = { throw failure }
        val calls = listOf<() -> Any>(
            { runtime.clearTasks() },
            { runtime.appendTask("Fight", "{}") },
            { runtime.startQueuedTasks() },
            { runtime.startTasks(tasks) { _, _ -> fail("Task was registered after a failed busy check") } },
        )
        calls.forEach { call ->
            assertSame(failure, assertThrows(IllegalStateException::class.java) { call() })
        }
        assertEquals(0, core.stops)
        assertEquals(0, core.appends)
        assertEquals(0, core.starts)
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

    @Test fun legacyStartRejectsEveryNonPositiveTaskIdAndRegistersOnlyAcceptedTasks() {
        for (rejectedId in listOf(0, -7)) {
            val core = FakeCore().apply { returnedTaskIds.addAll(listOf(901, rejectedId, 42)) }
            val runtime = session(core)
            val registered = mutableListOf<Pair<Int, Int>>()
            assertEquals(MaaCoreSession.StartResult.RejectedTask(1),
                runtime.startTasks(tasks + tasks + tasks) { index, id -> registered += index to id })
            assertEquals(listOf(0 to 901), registered)
            assertEquals(2, core.appends)
            assertEquals(0, core.starts)
            assertEquals(2, core.stops)
            assertTrue(core.queue.isEmpty())
        }
    }

    @Test fun legacyStartStillClearsEmptyPlansAndHonorsInitialStopFailure() {
        val core = FakeCore()
        val runtime = session(core)
        assertEquals(MaaCoreSession.StartResult.Failed,
            runtime.startTasks(emptyList()) { _, _ -> fail("Empty plan registered a task") })
        assertEquals(1, core.stops)
        core.stopAccepted = false
        assertEquals(MaaCoreSession.StartResult.Failed,
            runtime.startTasks(tasks) { _, _ -> fail("Rejected stop registered a task") })
        assertEquals(2, core.stops)
        val failure = IllegalStateException("initial stop failed")
        core.onStop = { throw failure }
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            runtime.startTasks(tasks) { _, _ -> fail("Failed stop registered a task") }
        })
        assertEquals(3, core.stops)
        assertEquals(0, core.appends)
        assertEquals(0, core.starts)
    }

    @Test fun legacyStartPreservesOriginalExceptionsAndDistinctCleanupFailures() {
        for (operation in listOf("append", "registration", "start")) {
            for (cleanup in listOf("success", "distinct", "same")) {
                val core = FakeCore()
                val runtime = session(core)
                val failure = IllegalStateException("$operation failed")
                val cleanupFailure = when (cleanup) {
                    "distinct" -> IllegalStateException("cleanup failed")
                    "same" -> failure
                    else -> null
                }
                if (operation == "append") core.onAppend = { if (core.appends == 2) throw failure }
                if (operation == "start") core.onStart = { throw failure }
                core.onStop = { if (core.stops > 1 && cleanupFailure != null) throw cleanupFailure }
                assertSame(failure, assertThrows(IllegalStateException::class.java) {
                    runtime.startTasks(tasks + tasks) { _, _ ->
                        if (operation == "registration") throw failure
                    }
                })
                assertEquals(2, core.stops)
                if (cleanup == "success") {
                    assertTrue(core.queue.isEmpty())
                    assertFalse(core.running)
                }
                assertEquals(if (cleanup == "distinct") listOf(cleanupFailure) else emptyList<Throwable>(),
                    failure.suppressed.toList())
            }
        }
    }

    @Test fun busyInstanceIsNotStoppedOrAppendedTo() {
        val core = FakeCore()
        val runtime = session(core)
        val taskId = runtime.appendTask("Fight", "{}")
        core.running = true
        assertQueueChangesAreBlocked(runtime, core)
        assertEquals(0, core.stops)
        assertEquals(1, core.appends)
        assertEquals(listOf("Fight"), core.queue)
        assertTrue(runtime.setTaskParams(taskId, "{\"enable\":false}"))
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
