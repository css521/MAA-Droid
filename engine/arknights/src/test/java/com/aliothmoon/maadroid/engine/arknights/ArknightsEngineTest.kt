package com.aliothmoon.maadroid.engine.arknights

import android.os.IBinder
import com.aliothmoon.maadroid.engine.AutomationEngine
import com.aliothmoon.maadroid.engine.ConnectionState
import com.aliothmoon.maadroid.engine.DeviceControl
import com.aliothmoon.maadroid.engine.DeviceHandle
import com.aliothmoon.maadroid.engine.DisplaySpec
import com.aliothmoon.maadroid.engine.EngineEvent
import com.aliothmoon.maadroid.engine.EngineResources
import com.aliothmoon.maadroid.engine.FrameSource
import com.aliothmoon.maadroid.engine.GameProfile
import com.aliothmoon.maadroid.engine.InputSink
import com.aliothmoon.maadroid.engine.RemoteEngineDevice
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.engine.TaskPhase
import com.aliothmoon.maadroid.engine.arknights.core.AsstMsg
import com.aliothmoon.maadroid.engine.arknights.core.MaaCoreClient
import com.aliothmoon.maadroid.engine.arknights.core.MaaCoreSession
import com.aliothmoon.maadroid.engine.arknights.core.MaaInstanceOptions
import com.aliothmoon.maadroid.engine.arknights.resource.MaaResourcePack
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArknightsEngineTest {
    private class FakeCore : MaaCoreClient {
        var instance = false
        var running = false
        var createAccepted = true
        var optionAccepted = true
        var startAccepted = true
        var stopAccepted = true
        var finishOnStop = true
        var paramsAccepted = true
        var autoConnect = true
        var callback: (Int, String?) -> Unit = { _, _ -> }
        var onConnect: () -> Unit = {}
        var onAppend: () -> Unit = {}
        var onStart: () -> Unit = {}
        var onStop: () -> Unit = {}
        var onRunning: () -> Unit = {}
        var onSetParams: () -> Unit = {}
        var creates = 0
        var starts = 0
        var stops = 0
        var appends = 0
        var runningReads = 0
        var versionReads = 0
        val configs = mutableListOf<String>()
        val options = mutableListOf<Pair<Int, String>>()
        val returnedIds = ArrayDeque<Int>()
        val appended = mutableListOf<Pair<String, String>>()
        val queue = mutableListOf<Int>()
        val parameterUpdates = mutableListOf<Pair<Int, String>>()

        override fun hasInstance() = instance
        override fun createInstance(callback: (Int, String?) -> Unit): Boolean {
            creates++
            this.callback = callback
            instance = createAccepted
            queue.clear()
            return createAccepted
        }
        override fun setInstanceOption(key: Int, value: String): Boolean {
            options += key to value
            return optionAccepted
        }
        override fun asyncConnect(config: String): Int {
            configs += config
            onConnect()
            if (autoConnect) connectionResult()
            return configs.size
        }
        override fun appendTask(type: String, params: String): Int {
            appends++
            appended += type to params
            val id = returnedIds.removeFirstOrNull() ?: appends
            if (id > 0) queue += id
            onAppend()
            return id
        }
        override fun setTaskParams(taskId: Int, params: String): Boolean {
            parameterUpdates += taskId to params
            onSetParams()
            return paramsAccepted && taskId in queue
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
                if (finishOnStop) running = false
            }
            return stopAccepted
        }
        override fun running(): Boolean {
            runningReads++
            onRunning()
            return running
        }
        override fun version(): String {
            versionReads++
            return "v6.17.2"
        }
        fun connectionResult(success: Boolean = true) = emit(
            AsstMsg.AsyncCallInfo,
            """{"what":"Connect","async_call_id":${configs.size},"details":{"ret":$success}}""",
        )
        fun emit(msg: AsstMsg, json: String? = "{}") = callback(msg.value, json)
        fun task(msg: AsstMsg, id: Int) = emit(msg, """{"taskid":$id,"taskchain":"Fight"}""")
    }

    private class FakeDevice(
        override val displaySpec: DisplaySpec = DisplaySpec(1920, 1080, 240),
        override val displayId: Int = 73,
    ) : RemoteEngineDevice {
        val lookups = mutableListOf<String>()
        override val frames: FrameSource get() = error("Remote MaaCore must not request host frames")
        override val input: InputSink get() = error("Remote MaaCore must not use host input")
        override val control: DeviceControl get() = error("The host owns the display")
        override fun engineService(engineId: String): IBinder? {
            lookups += engineId
            return null
        }
    }

    private fun TestScope.newEngine(
        core: FakeCore,
        resources: MaaResourcePreparation = MaaResourcePreparation { _, _ -> Result.success(Unit) },
        options: () -> MaaRunOptions = { MaaRunOptions("Official", false) },
        onRawEvent: (Int, String?) -> Unit = { _, _ -> },
        factory: (RemoteEngineDevice) -> MaaCoreClient = { core },
    ) = ArknightsEngine(resources, options, onRawEvent, factory, backgroundScope, 500, 1_000)

    private fun resourcePaths(directory: File = File("resources")) = ArknightsEngine.resourcePaths(directory)

    private suspend fun ArknightsEngine.ready(device: DeviceHandle = FakeDevice()) {
        assertTrue(prepare(resourcePaths()).isSuccess)
        assertTrue(connect(device).isSuccess)
    }

    private fun TestScope.observe(engine: ArknightsEngine): MutableList<EngineEvent> {
        val received = mutableListOf<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { received += it }
        }
        return received
    }

    private fun assertOwnershipRetained(engine: ArknightsEngine) {
        assertThrows(IllegalStateException::class.java) { engine.release() }
    }

    private fun assertFailureReported(events: List<EngineEvent>, failure: Exception) {
        assertTrue(events.filterIsInstance<EngineEvent.Failure>().any {
            it.cause?.javaClass == failure.javaClass && it.cause?.message == failure.message
        })
    }

    @Test fun publicConstructionAndInspectionAreLazyAndInstancesAreIndependent() {
        val resources = MaaResourcePreparation { _, _ -> error("prepare was not requested") }
        val options = { error("options were read before prepare") }
        val first = ArknightsEngine(resources, options)
        val second = ArknightsEngine(resources, options)
        assertSame(ArknightsProfile, first.profile)
        assertNotSame(first, second)
        assertNotSame(first.events, second.events)
        assertFalse(first.isRunning)
        assertEquals("", first.version)
        first.release()
        first.release()
        assertFalse(second.isRunning)
        second.release()
    }

    @Test fun prepareCapturesOptionsBeforeSuspendingAndConnectUsesOwnedDisplay() = runTest {
        val core = FakeCore()
        val entered = CompletableDeferred<Unit>()
        val prepared = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        var settings = MaaRunOptions("YoStarJP", true)
        var reads = 0
        val device = FakeDevice()
        val directory = File("selected-resources")
        val engine = newEngine(core, MaaResourcePreparation { actual, snapshot ->
            assertEquals(directory, actual)
            assertEquals(MaaRunOptions("YoStarJP", true), snapshot)
            calls += "prepare"
            entered.complete(Unit)
            prepared.await()
            calls += "prepared"
            Result.success(Unit)
        }, options = { reads++; settings }, factory = {
            assertSame(device, it)
            calls += "client"
            core
        })
        val events = observe(engine)
        assertTrue(engine.connect(device).isFailure)
        assertTrue(calls.isEmpty())
        val preparation = async { engine.prepare(resourcePaths(directory)) }
        entered.await()
        settings = MaaRunOptions("Bilibili", false)
        val connection = async { engine.connect(device) }
        runCurrent()
        assertEquals(0, core.creates)
        assertFalse(connection.isCompleted)
        prepared.complete(Unit)
        assertTrue(preparation.await().isSuccess)
        assertTrue(connection.await().isSuccess)
        assertEquals(1, reads)
        assertEquals(listOf("prepare", "prepared", "client"), calls)
        assertEquals(listOf(
            MaaInstanceOptions.TOUCH_MODE to MaaInstanceOptions.ANDROID,
            MaaInstanceOptions.DEPLOYMENT_WITH_PAUSE to "1",
        ), core.options)
        val config = Json.parseToJsonElement(core.configs.single()).jsonObject
        assertEquals("libbridge.so", config.getValue("library_path").jsonPrimitive.content)
        assertEquals("73", config.getValue("display_id").jsonPrimitive.content)
        assertEquals("true", config.getValue("force_stop").jsonPrimitive.content)
        assertEquals("1920", config.getValue("screen_resolution").jsonObject.getValue("width").jsonPrimitive.content)
        assertEquals("1080", config.getValue("screen_resolution").jsonObject.getValue("height").jsonPrimitive.content)
        assertEquals("v6.17.2", engine.version)
        runCurrent()
        assertEquals(listOf(ConnectionState.Connecting, ConnectionState.Connected),
            events.filterIsInstance<EngineEvent.Connection>().takeLast(2).map { it.state })
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun missingResourcePackNeverReachesHostPreparation() = runTest {
        assertResourcePathsRejected(EngineResources(ArknightsProfile, emptyMap()))
    }

    @Test fun anotherGamesResourcePackWithTheSameIdNeverReachesHostPreparation() = runTest {
        val foreignPack = object : ResourcePackSpec by MaaResourcePack {
            override val engineId = "other-game"
        }
        val foreignProfile = object : GameProfile by ArknightsProfile {
            override val id = foreignPack.engineId
            override val resourcePacks = listOf(foreignPack)
        }
        assertResourcePathsRejected(EngineResources(
            foreignProfile, mapOf(foreignPack.packId to File("other-game-resources")),
        ))
    }

    private suspend fun TestScope.assertResourcePathsRejected(invalidPaths: EngineResources) {
        val core = FakeCore()
        var preparations = 0
        var optionReads = 0
        var factories = 0
        val engine = newEngine(core, MaaResourcePreparation { _, _ ->
            preparations++
            Result.success(Unit)
        }, options = { optionReads++; MaaRunOptions("Official", false) }, factory = { factories++; core })
        try {
            assertTrue(engine.prepare(invalidPaths).isFailure)
            assertEquals(0, preparations)
            assertEquals(0, optionReads)
            assertTrue(engine.connect(FakeDevice()).isFailure)
            assertEquals(0, factories)

            assertTrue(engine.prepare(resourcePaths()).isSuccess)
            assertTrue(engine.prepare(invalidPaths).isFailure)
            assertEquals(1, preparations)
            assertEquals(1, optionReads)
            assertTrue(engine.connect(FakeDevice()).isFailure)
            assertEquals(0, factories)
        } finally {
            engine.release()
        }
    }

    @Test fun failedAndCancelledPreparationsCannotConnectUsingAnEarlierSnapshot() = runTest {
        val core = FakeCore()
        val failure = IOException("resource delivery failed")
        val suspended = CompletableDeferred<Unit>()
        var attempt = 0
        var factories = 0
        val engine = newEngine(core, MaaResourcePreparation { _, _ ->
            when (++attempt) {
                1 -> Result.success(Unit)
                2 -> Result.failure(failure)
                else -> { suspended.await(); Result.success(Unit) }
            }
        }, factory = { factories++; core })
        assertTrue(engine.prepare(resourcePaths(File("first"))).isSuccess)
        val actualFailure = engine.prepare(resourcePaths(File("failed"))).exceptionOrNull()
        assertEquals(failure.javaClass, actualFailure?.javaClass)
        assertEquals(failure.message, actualFailure?.message)
        assertTrue(engine.connect(FakeDevice()).isFailure)
        val cancelled = async { engine.prepare(resourcePaths(File("cancelled"))) }
        runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertTrue(engine.connect(FakeDevice()).isFailure)
        assertEquals(0, factories)
        engine.release()
    }

    @Test fun invalidClientAndResourceOptionFailuresNeverCreateNativeClients() = runTest {
        for (throws in listOf(false, true)) {
            val core = FakeCore()
            var preparations = 0
            val engine = newEngine(core, MaaResourcePreparation { _, _ ->
                preparations++; Result.success(Unit)
            }, options = {
                if (throws) throw IOException("settings unavailable")
                MaaRunOptions("unknown", false)
            }, factory = { error("No client may be created") })
            assertTrue(engine.prepare(resourcePaths()).isFailure)
            assertTrue(engine.connect(FakeDevice()).isFailure)
            assertEquals(0, preparations)
            engine.release()
        }
    }

    @Test fun publicConnectResolvesOnlyTheArknightsBinderAfterPreparingResources() = runTest {
        var prepared = false
        val engine = ArknightsEngine(MaaResourcePreparation { _, _ ->
            prepared = true
            Result.success(Unit)
        }, { MaaRunOptions("Official", false) })
        val device = FakeDevice()
        assertTrue(engine.connect(device).isFailure)
        assertTrue(device.lookups.isEmpty())
        assertTrue(engine.prepare(resourcePaths()).isSuccess)
        assertTrue(prepared)
        assertTrue(engine.connect(device).isFailure) // No engine binder in this device.
        assertEquals(listOf(ArknightsProfile.id), device.lookups)
        engine.release()
    }

    @Test fun unsupportedDeviceDoesNotCreateNativeClients() = runTest {
        val remote = FakeDevice()
        val local = object : DeviceHandle {
            override val frames: FrameSource get() = remote.frames
            override val input: InputSink get() = remote.input
            override val control: DeviceControl get() = remote.control
        }
        val engine = newEngine(FakeCore(), factory = { error("Device capability must be validated first") })
        assertTrue(engine.prepare(resourcePaths()).isSuccess)
        assertTrue(engine.connect(local).isFailure)
        engine.release()
    }

    @Test fun invalidDisplayIsRejectedAfterInitializationAndRequiresConfirmedStop() = runTest {
        val core = FakeCore()
        val engine = newEngine(core)
        val device = object : RemoteEngineDevice by FakeDevice(displayId = -1) {
            override val displaySpec: DisplaySpec
                get() {
                    assertEquals(1, core.creates)
                    assertEquals(listOf(MaaInstanceOptions.TOUCH_MODE to MaaInstanceOptions.ANDROID), core.options)
                    assertEquals(1, core.stops)
                    return DisplaySpec(1920, 1080, 240)
                }
        }
        assertTrue(engine.prepare(resourcePaths()).isSuccess)
        assertTrue(engine.connect(device).exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, core.creates)
        assertEquals(1, core.stops)
        assertTrue(core.configs.isEmpty())
        assertOwnershipRetained(engine)
        core.stopAccepted = false
        assertFalse(engine.stop())
        assertOwnershipRetained(engine)
        core.stopAccepted = true
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun busyNativeNeverReadsDisplayMetadataOrChangesTheExistingQueue() = runTest {
        val core = FakeCore().apply {
            instance = true
            running = true
            queue += 41
        }
        var metadataReads = 0
        val device = object : RemoteEngineDevice by FakeDevice() {
            override val displaySpec: DisplaySpec
                get() {
                    metadataReads++
                    error("BUSY must not prepare the display")
                }
            override val displayId: Int
                get() {
                    metadataReads++
                    error("BUSY must not inspect the display")
                }
        }
        val engine = newEngine(core)
        assertTrue(engine.prepare(resourcePaths()).isSuccess)
        val failure = engine.connect(device).exceptionOrNull()
        assertTrue(failure is MaaInitializationException)
        assertEquals(MaaCoreSession.Initialization.BUSY, (failure as MaaInitializationException).phase)
        assertEquals(0, metadataReads)
        assertEquals(0, core.creates)
        assertEquals(0, core.stops)
        assertEquals(listOf(41), core.queue)
        assertTrue(core.running)
        assertTrue(core.configs.isEmpty())
        // The original owner finishes before explicit cleanup of this candidate.
        core.running = false
        assertTrue(engine.stop())
        engine.release()
        assertEquals(0, metadataReads)
    }

    @Test fun initializationFailuresKeepTheirPhaseAndRequireStopBeforeRelease() = runTest {
        for (phase in listOf(MaaCoreSession.Initialization.BUSY,
            MaaCoreSession.Initialization.CREATE_FAILED, MaaCoreSession.Initialization.TOUCH_MODE_FAILED)) {
            val core = FakeCore().apply {
                if (phase == MaaCoreSession.Initialization.BUSY) { instance = true; running = true }
                if (phase == MaaCoreSession.Initialization.CREATE_FAILED) createAccepted = false
                if (phase == MaaCoreSession.Initialization.TOUCH_MODE_FAILED) optionAccepted = false
            }
            val engine = newEngine(core)
            assertTrue(engine.prepare(resourcePaths()).isSuccess)
            val failure = engine.connect(FakeDevice()).exceptionOrNull()
            assertTrue(failure is MaaInitializationException)
            assertEquals(phase, (failure as MaaInitializationException).phase)
            assertTrue(core.configs.isEmpty())
            assertEquals(0, core.stops)
            assertOwnershipRetained(engine)
            assertTrue(engine.stop())
            engine.release()
        }
    }

    @Test fun queueClearFailurePreventsConnectAndCannotReleaseOwnership() = runTest {
        val core = FakeCore().apply { stopAccepted = false }
        val engine = newEngine(core)
        assertTrue(engine.prepare(resourcePaths()).isSuccess)
        assertTrue(engine.connect(FakeDevice()).isFailure)
        assertTrue(core.configs.isEmpty())
        assertFalse(engine.stop())
        assertOwnershipRetained(engine)
        core.stopAccepted = true
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun timedOutConnectRetainsDeviceUntilNativeCompletionAndConfirmedStop() = runTest {
        val core = FakeCore().apply { autoConnect = false }
        val engine = newEngine(core)
        assertTrue(engine.prepare(resourcePaths()).isSuccess)
        assertTrue(engine.connect(FakeDevice()).isFailure)
        assertTrue(engine.isRunning)
        assertEquals(0, engine.appendTask("Fight", "{}"))
        assertFalse(engine.start())
        assertFalse(engine.stop())
        assertEquals(1, core.stops) // Only the clear before Connect, never Stop over pending Connect.
        assertOwnershipRetained(engine)
        assertTrue(engine.connect(FakeDevice()).isFailure)
        assertEquals(1, core.configs.size)
        core.connectionResult(success = false)
        assertTrue(engine.stop())
        assertEquals(2, core.stops)
        engine.release()
    }

    @Test fun cancelledConnectAndStopWaitersRetainThePendingNativeCall() = runTest {
        val core = FakeCore().apply { autoConnect = false }
        val engine = newEngine(core)
        assertTrue(engine.prepare(resourcePaths()).isSuccess)
        val connecting = async { engine.connect(FakeDevice()) }
        runCurrent()
        connecting.cancelAndJoin()
        assertTrue(connecting.isCancelled)
        assertTrue(engine.isRunning)
        assertOwnershipRetained(engine)
        val stopping = async { engine.stop() }
        runCurrent()
        stopping.cancelAndJoin()
        assertTrue(stopping.isCancelled)
        assertOwnershipRetained(engine)
        assertEquals(1, core.stops)
        core.connectionResult()
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun ambiguousConnectExceptionDoesNotAllowReleaseOrReconnect() = runTest {
        val core = FakeCore().apply { onConnect = { throw IOException("ambiguous transport failure") } }
        val engine = newEngine(core)
        assertTrue(engine.prepare(resourcePaths()).isSuccess)
        assertTrue(engine.connect(FakeDevice()).isFailure)
        assertTrue(engine.isRunning)
        assertFalse(engine.stop())
        assertOwnershipRetained(engine)
        assertTrue(engine.connect(FakeDevice()).isFailure)
        assertEquals(1, core.configs.size)
        engine.invalidate()
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun nativeIdsAndSynchronousBinderCallbacksCanRefreshParamsBeforeStartReturns() = runTest {
        val core = FakeCore().apply { returnedIds.addAll(listOf(41, 901)) }
        lateinit var engine: ArknightsEngine
        val updated = " {\"stage\":\"1-7\",\"times\":2} "
        val payload = """{"taskid":901,"taskchain":"Fight"}"""
        engine = newEngine(core, onRawEvent = { msg, json ->
            assertEquals(AsstMsg.TaskChainStart.value, msg)
            assertEquals(payload, json)
            val reads = core.runningReads
            assertTrue(engine.setTaskParams(901, updated))
            assertEquals(reads, core.runningReads)
        })
        engine.ready()
        val params = " {\"enable\":true} "
        assertEquals(41, engine.appendTask("Fight", params))
        assertEquals(901, engine.appendTask("Fight", params))
        assertFalse(engine.setTaskParams(1, "{}"))
        val callbacks = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fake-arknights-callback").apply { isDaemon = true }
        }
        try {
            core.onStart = {
                assertEquals(listOf(41, 901), core.queue)
                callbacks.submit { core.emit(AsstMsg.TaskChainStart, payload) }.get(2, TimeUnit.SECONDS)
                assertEquals(listOf(901 to updated), core.parameterUpdates)
            }
            assertTrue(engine.start())
            assertEquals(listOf("Fight" to params, "Fight" to params), core.appended)
            assertTrue(engine.isRunning)
            assertTrue(engine.stop())
            engine.release()
        } finally { callbacks.shutdownNow() }
    }

    @Test fun anyRejectedAppendPoisonsTheWholePlanAndNativeNeverStartsAPartialQueue() = runTest {
        for (rejection in listOf("zero", "negative", "duplicate", "json", "type", "exception")) {
            val core = FakeCore().apply { returnedIds += 41 }
            val engine = newEngine(core)
            engine.ready()
            assertEquals(41, engine.appendTask("Fight", "{}"))
            when (rejection) {
                "zero" -> core.returnedIds += 0
                "negative" -> core.returnedIds += -9
                "duplicate" -> core.returnedIds += 41
                "exception" -> core.onAppend = { throw IOException("append response lost") }
            }
            val type = if (rejection == "type") " " else "Recruit"
            val params = if (rejection == "json") "[]" else "{}"
            assertEquals(AutomationEngine.INVALID_TASK_ID, engine.appendTask(type, params))
            assertEquals(AutomationEngine.INVALID_TASK_ID, engine.appendTask("Infrast", "{}"))
            assertFalse(engine.start())
            assertEquals(0, core.starts)
            assertOwnershipRetained(engine)
            assertTrue(engine.stop())
            assertTrue(core.queue.isEmpty())
            engine.release()
        }
    }

    @Test fun startWaitsForAnInFlightAppendToRegisterItsNativeId() = runTest {
        val core = FakeCore().apply { returnedIds.addAll(listOf(41, 901)) }
        val engine = newEngine(core)
        engine.ready()
        assertEquals(41, engine.appendTask("Fight", "{}"))
        val entered = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val appends = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fake-arknights-append").apply { isDaemon = true }
        }
        try {
            core.onAppend = {
                entered.countDown()
                check(complete.await(2, TimeUnit.SECONDS)) { "append was never unblocked" }
            }
            val appended = appends.submit<Int> { engine.appendTask("Recruit", "{}") }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val starting = async { engine.start() }
            runCurrent()
            assertFalse(starting.isCompleted)
            assertEquals(0, core.starts)
            complete.countDown()
            assertEquals(901, appended.get(2, TimeUnit.SECONDS).toInt())
            assertTrue(starting.await())
            assertTrue(engine.setTaskParams(901, "{}"))
            assertTrue(engine.stop())
            engine.release()
        } finally {
            complete.countDown()
            appends.shutdownNow()
        }
    }

    @Test fun conflictingAppendCannotSilentlyLeaveAnAcceptedPartialPlan() = runTest {
        val core = FakeCore()
        val engine = newEngine(core)
        engine.ready()
        core.onAppend = { assertEquals(0, engine.appendTask("Recruit", "{}")) }
        assertTrue(engine.appendTask("Fight", "{}") > 0)
        assertFalse(engine.start())
        assertEquals(1, core.appends)
        assertEquals(0, core.starts)
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun rejectedAppendDuringTheBusyCheckAlsoPreventsStart() = runTest {
        val core = FakeCore()
        val engine = newEngine(core)
        engine.ready()
        assertTrue(engine.appendTask("Fight", "{}") > 0)
        core.onRunning = { assertEquals(0, engine.appendTask("Recruit", "{}")) }
        assertFalse(engine.start())
        assertEquals(0, core.starts)
        assertEquals(1, core.appends)
        core.onRunning = {}
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun parameterValidationAndNativeFailuresCannotChangeOtherTasks() = runTest {
        val core = FakeCore().apply { returnedIds += 901 }
        val engine = newEngine(core)
        val events = observe(engine)
        assertFalse(engine.setTaskParams(901, "{}"))
        engine.ready()
        assertEquals(901, engine.appendTask("Fight", "{}"))
        for (id in listOf(-1, 0, 1)) assertFalse(engine.setTaskParams(id, "{}"))
        for (params in listOf("", "[]", "null", "broken")) assertFalse(engine.setTaskParams(901, params))
        assertTrue(core.parameterUpdates.isEmpty())
        core.paramsAccepted = false
        assertFalse(engine.setTaskParams(901, "{}"))
        val failure = IOException("parameter response lost")
        core.onSetParams = { throw failure }
        assertFalse(engine.setTaskParams(901, "{}"))
        runCurrent()
        assertFailureReported(events, failure)
        core.onSetParams = {}
        core.paramsAccepted = true
        assertTrue(engine.setTaskParams(901, "{\"enable\":false}"))
        assertEquals(listOf(901), core.queue)
        assertTrue(engine.stop())
        val calls = listOf(core.parameterUpdates.size, core.runningReads, core.versionReads)
        assertFalse(engine.setTaskParams(901, "{}"))
        assertFalse(engine.isRunning)
        assertEquals("", engine.version)
        assertEquals(calls, listOf(core.parameterUpdates.size, core.runningReads, core.versionReads))
        engine.release()
    }

    @Test fun rejectedOrThrowingStartIsSingleUseAndStillRequiresConfirmedStop() = runTest {
        for (throws in listOf(false, true)) {
            val core = FakeCore()
            val engine = newEngine(core)
            val events = observe(engine)
            engine.ready()
            engine.appendTask("Fight", "{}")
            if (throws) core.onStart = { throw IOException("start response lost") }
            else core.startAccepted = false
            assertFalse(engine.start())
            assertFalse(engine.start())
            assertEquals(1, core.starts)
            assertOwnershipRetained(engine)
            runCurrent()
            assertEquals(listOf(EngineEvent.AllTasksFinished(false)), events.filterIsInstance<EngineEvent.AllTasksFinished>())
            assertTrue(engine.stop())
            engine.release()
        }
    }

    @Test fun runningQueryFailureIsReportedWithoutStartingOrReleasing() = runTest {
        val core = FakeCore()
        val engine = newEngine(core)
        val events = observe(engine)
        engine.ready()
        engine.appendTask("Fight", "{}")
        val failure = IOException("running unavailable")
        core.onRunning = { throw failure }
        assertTrue(engine.isRunning) // Conservative when the remote status is unknown.
        assertFalse(engine.start())
        assertEquals(0, core.starts)
        assertOwnershipRetained(engine)
        runCurrent()
        assertFailureReported(events, failure)
        core.onRunning = {}
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun cancellationAfterNativeStartDoesNotReleaseOrAllowAnotherStart() = runTest {
        val core = FakeCore()
        val engine = newEngine(core)
        engine.ready()
        val id = engine.appendTask("Fight", "{}")
        lateinit var starting: Deferred<Boolean>
        core.onStart = { starting.cancel() }
        starting = async { engine.start() }
        runCurrent()
        assertTrue(starting.isCancelled)
        assertTrue(engine.isRunning)
        assertOwnershipRetained(engine)
        assertFalse(engine.setTaskParams(id, "{}"))
        assertFalse(engine.start())
        assertEquals(1, core.starts)
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun stopFalseAndTransportExceptionsBlockQueueMutationAndRetainOwnership() = runTest {
        for (throws in listOf(false, true)) {
            val core = FakeCore()
            val engine = newEngine(core)
            engine.ready()
            val id = engine.appendTask("Fight", "{}")
            if (throws) core.onStop = { throw IOException("stop response lost") }
            else core.stopAccepted = false
            assertFalse(engine.stop())
            assertOwnershipRetained(engine)
            assertEquals(0, engine.appendTask("Recruit", "{}"))
            assertFalse(engine.setTaskParams(id, "{}"))
            assertFalse(engine.start())
            assertEquals(0, core.starts)
            core.onStop = {}
            core.stopAccepted = true
            assertTrue(engine.stop())
            val stops = core.stops
            assertTrue(engine.stop())
            assertEquals(stops, core.stops)
            engine.release()
        }
    }

    @Test fun terminalCallbackDuringRejectedStopDoesNotCountAsStopConfirmation() = runTest {
        val core = FakeCore()
        val engine = newEngine(core)
        val events = observe(engine)
        engine.ready()
        val id = engine.appendTask("Fight", "{}")
        assertTrue(engine.start())
        core.stopAccepted = false
        core.onStop = { core.task(AsstMsg.TaskChainStopped, id) }
        assertFalse(engine.stop())
        assertOwnershipRetained(engine)
        runCurrent()
        assertEquals(listOf(EngineEvent.AllTasksFinished(false)), events.filterIsInstance<EngineEvent.AllTasksFinished>())
        core.stopAccepted = true
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun stopTimeoutAndCancellationWhileNativeStillRunsAllowOnlyStopRetry() = runTest {
        for (cancel in listOf(false, true)) {
            val core = FakeCore().apply { finishOnStop = false }
            val engine = newEngine(core)
            engine.ready()
            engine.appendTask("Fight", "{}")
            assertTrue(engine.start())
            if (cancel) {
                val stopping = async { engine.stop() }
                runCurrent()
                stopping.cancelAndJoin()
                assertTrue(stopping.isCancelled)
            } else assertFalse(engine.stop())
            assertTrue(engine.isRunning)
            assertOwnershipRetained(engine)
            assertFalse(engine.start())
            core.finishOnStop = true
            assertTrue(engine.stop())
            engine.release()
        }
    }

    @Test fun cancellationDuringFinalStopCallCannotMarkOwnershipReleased() = runTest {
        val core = FakeCore()
        val engine = newEngine(core)
        engine.ready()
        engine.appendTask("Fight", "{}")
        assertTrue(engine.start())
        lateinit var stopping: Deferred<Boolean>
        core.onStop = { stopping.cancel() }
        stopping = async { engine.stop() }
        runCurrent()
        assertTrue(stopping.isCancelled)
        assertOwnershipRetained(engine)
        core.onStop = {}
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun nativeTaskPhasesAreMonotonicAndShortSynchronousRunsFinishOnlyOnce() = runTest {
        val core = FakeCore().apply { returnedIds += 901 }
        val engine = newEngine(core)
        val events = observe(engine)
        engine.ready()
        val id = engine.appendTask("Fight", "{}")
        core.onStart = {
            core.task(AsstMsg.TaskChainStart, id)
            core.task(AsstMsg.TaskChainStart, id)
            core.task(AsstMsg.TaskChainStopped, 12345) // A different native task cannot finish this run.
            core.task(AsstMsg.TaskChainCompleted, id)
            core.task(AsstMsg.TaskChainStart, id)
            core.running = false
            core.emit(AsstMsg.AllTasksCompleted, null)
            core.emit(AsstMsg.AllTasksCompleted)
        }
        assertTrue(engine.start())
        assertFalse(engine.isRunning)
        assertFalse(engine.start())
        assertFalse(engine.setTaskParams(id, "{}"))
        assertOwnershipRetained(engine)
        runCurrent()
        assertEquals(listOf(
            EngineEvent.Task(id, "Fight", TaskPhase.Started),
            EngineEvent.Task(id, "Fight", TaskPhase.Completed),
        ), events.filterIsInstance<EngineEvent.Task>())
        assertEquals(listOf(EngineEvent.AllTasksFinished(true)), events.filterIsInstance<EngineEvent.AllTasksFinished>())
        assertTrue(engine.stop())
        engine.release()
    }

    @Test fun businessCallbackAndTaskFailuresMakeTheFinalResultUnsuccessful() = runTest {
        for (callbackFailure in listOf(false, true)) {
            val core = FakeCore()
            val failure = IOException("business refresh failed")
            val engine = newEngine(core, onRawEvent = { msg, _ ->
                if (callbackFailure && msg == AsstMsg.TaskChainStart.value) throw failure
            })
            val events = observe(engine)
            engine.ready()
            val id = engine.appendTask("Fight", "{}")
            assertTrue(engine.start())
            core.task(AsstMsg.TaskChainStart, id)
            if (!callbackFailure) core.task(AsstMsg.TaskChainError, id)
            core.emit(AsstMsg.AllTasksCompleted, "not json")
            runCurrent()
            assertEquals(listOf(EngineEvent.AllTasksFinished(false)), events.filterIsInstance<EngineEvent.AllTasksFinished>())
            if (callbackFailure) assertFailureReported(events, failure)
            assertTrue(engine.stop())
            engine.release()
        }
    }

    @Test fun rawOverflowCannotLoseTerminalEvenWhenReleaseHappensBeforeRelayDrains() = runTest {
        val core = FakeCore()
        var callbacks = 0
        val engine = newEngine(core, onRawEvent = { _, _ -> callbacks++ })
        val received = mutableListOf<EngineEvent>()
        val blocked = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        backgroundScope.launch {
            engine.events.collect { event ->
                if (event is EngineEvent.Raw && !resume.isCompleted) {
                    blocked.complete(Unit)
                    resume.await()
                }
                received += event
            }
        }
        runCurrent()
        engine.ready()
        runCurrent()
        val id = engine.appendTask("Fight", "{}")
        assertTrue(engine.start())
        core.task(AsstMsg.TaskChainStart, id)
        runCurrent()
        assertTrue(blocked.isCompleted)
        repeat(5_000) { core.emit(AsstMsg.SubTaskExtraInfo, """{"detail":"$it"}""") }
        core.task(AsstMsg.TaskChainCompleted, id)
        core.emit(AsstMsg.AllTasksCompleted)
        assertEquals(5_003, callbacks)
        assertTrue(engine.stop())
        engine.release()
        runCurrent()
        assertTrue(received.filterIsInstance<EngineEvent.AllTasksFinished>().isEmpty())
        resume.complete(Unit)
        runCurrent()
        assertEquals(listOf(EngineEvent.AllTasksFinished(true)), received.filterIsInstance<EngineEvent.AllTasksFinished>())
        assertEquals(listOf(TaskPhase.Started, TaskPhase.Completed), received.filterIsInstance<EngineEvent.Task>().map { it.phase })
        assertTrue(received.filterIsInstance<EngineEvent.Raw>().size < 5_003)
    }

    @Test fun stoppedAndReleasedEnginesCannotDeliverOldCallbacksIntoAnotherRun() = runTest {
        val core = FakeCore().apply { returnedIds.addAll(listOf(41, 41)) }
        var oldRaw = 0
        var newRaw = 0
        val first = newEngine(core, onRawEvent = { _, _ -> oldRaw++ })
        val firstEvents = observe(first)
        first.ready()
        val oldCallback = core.callback
        // Even before Start, delayed task callbacks must not reach business code.
        oldCallback(AsstMsg.AllTasksCompleted.value, "{}")
        assertEquals(0, oldRaw)
        assertEquals(41, first.appendTask("Fight", "{}"))
        assertTrue(first.start())
        assertTrue(first.stop())
        runCurrent()
        val eventCount = firstEvents.size
        oldCallback(AsstMsg.TaskChainStart.value, """{"taskid":41}""")
        oldCallback(AsstMsg.AllTasksCompleted.value, "{}")
        assertEquals(0, oldRaw)
        first.release()
        val second = newEngine(core, onRawEvent = { _, _ -> newRaw++ })
        val secondEvents = observe(second)
        second.ready()
        assertEquals(41, second.appendTask("Fight", "{}"))
        assertTrue(second.start())
        oldCallback(AsstMsg.TaskChainStopped.value, """{"taskid":41}""")
        oldCallback(AsstMsg.Destroyed.value, "{}")
        first.invalidate()
        runCurrent()
        assertEquals(eventCount, firstEvents.size)
        assertTrue(secondEvents.filterIsInstance<EngineEvent.AllTasksFinished>().isEmpty())
        assertEquals(0, newRaw)
        core.task(AsstMsg.TaskChainStart, 41)
        assertEquals(1, newRaw)
        assertTrue(second.isRunning)
        assertEquals(2, core.creates)
        assertTrue(second.stop())
        second.release()
    }

    @Test fun destructionConfirmsTerminationWithoutFurtherCallsToDeadNativeInstance() = runTest {
        val core = FakeCore()
        val engine = newEngine(core)
        val events = observe(engine)
        engine.ready()
        engine.appendTask("Fight", "{}")
        assertTrue(engine.start())
        core.emit(AsstMsg.Destroyed)
        engine.invalidate()
        val calls = listOf(core.stops, core.runningReads, core.versionReads)
        assertFalse(engine.isRunning)
        assertEquals("", engine.version)
        assertTrue(engine.stop())
        assertEquals(0, engine.appendTask("Fight", "{}"))
        assertFalse(engine.setTaskParams(1, "{}"))
        core.emit(AsstMsg.AllTasksCompleted)
        runCurrent()
        assertEquals(1, events.filterIsInstance<EngineEvent.Connection>().count { it.state == ConnectionState.Disconnected })
        assertEquals(listOf(EngineEvent.AllTasksFinished(false)), events.filterIsInstance<EngineEvent.AllTasksFinished>())
        engine.release()
        assertEquals(calls, listOf(core.stops, core.runningReads, core.versionReads))
    }

    @Test fun synchronousInvalidationCannotPublishAStaleRawEventOrReportStarted() = runTest {
        val core = FakeCore()
        lateinit var engine: ArknightsEngine
        engine = newEngine(core, onRawEvent = { _, _ -> engine.invalidate() })
        val events = observe(engine)
        engine.ready()
        val id = engine.appendTask("Fight", "{}")
        core.onStart = { core.task(AsstMsg.TaskChainStart, id) }
        assertFalse(engine.start())
        runCurrent()
        assertTrue(events.filterIsInstance<EngineEvent.Raw>().isEmpty())
        assertEquals(listOf(EngineEvent.AllTasksFinished(false)), events.filterIsInstance<EngineEvent.AllTasksFinished>())
        engine.release()
    }
}
