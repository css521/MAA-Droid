package com.maadroid.app.engine.limbus

import com.maadroid.app.engine.AutomationEngine
import com.maadroid.app.engine.ConnectionState
import com.maadroid.app.engine.EngineDiagnosticSink
import com.maadroid.app.engine.EngineEvent
import com.maadroid.app.engine.LogLevel
import com.maadroid.app.engine.TaskPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LimbusEngineDiagnosticsTest {
    private class RecordingSink : EngineDiagnosticSink {
        val records = mutableListOf<Pair<String, String>>()
        override fun record(phase: String, detail: String) {
            records += phase to detail
        }
    }

    @Test
    fun taskLifecyclePersistsWithoutAnEventSubscriber() = runTest {
        val sink = RecordingSink()
        val engine = LimbusEngine(backgroundScope).apply { setDiagnosticSink(sink) }
        val phases = listOf(TaskPhase.Started, TaskPhase.Completed, TaskPhase.Failed, TaskPhase.Stopped)

        phases.forEach { phase ->
            engine.emitForTest(EngineEvent.Task(7, "mirror", phase, "task status"))
        }
        engine.emitForTest(EngineEvent.AllTasksFinished(true))
        engine.emitForTest(EngineEvent.AllTasksFinished(false))

        assertEquals(
            phases.map { "task.${it.name.lowercase()}" to "taskId=7 type=mirror message=task status" } +
                listOf("tasks.finished" to "success=true", "tasks.finished" to "success=false"),
            sink.records,
        )
    }

    @Test
    fun userLogsAndTaskEventsKeepTheirOriginalIdentityAndOrder() = runTest {
        val sink = RecordingSink()
        val engine = LimbusEngine(backgroundScope).apply { setDiagnosticSink(sink) }
        val received = mutableListOf<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { received += it }
        }
        val events = listOf(
            EngineEvent.Connection(ConnectionState.Connected),
            EngineEvent.Task(12, "exp", TaskPhase.Started),
            EngineEvent.Log(LogLevel.Info, "正在领取奖励"),
            EngineEvent.Log(LogLevel.Warn, "识别暂未命中"),
            EngineEvent.Log(LogLevel.Error, "任务失败"),
            EngineEvent.Task(12, "exp", TaskPhase.Failed, "任务失败"),
            EngineEvent.AllTasksFinished(false),
        )

        events.forEach { engine.emitForTest(it) }
        runCurrent()

        assertEquals(events, received)
        events.zip(received).forEach { (sent, delivered) -> assertSame(sent, delivered) }
        assertEquals(listOf("log.info", "log.warn", "log.error"),
            sink.records.map { it.first }.filter { it.startsWith("log.") })
        assertEquals("taskId=12 type=exp", sink.records.first().second)
    }

    @Test
    fun pipelineNodeBoundariesPersistButFrameNoiseAndRawPayloadsDoNot() = runTest {
        val sink = RecordingSink()
        val engine = LimbusEngine(backgroundScope).apply { setDiagnosticSink(sink) }
        val boundaries = listOf(
            "节点 main 执行动作 empty",
            "节点 touch_to_start 执行动作 click",
            "节点 touch_to_start 的 next 与 interrupt 均未命中，该分支结束",
            "节点 reward 结束流水线: done",
        )

        repeat(1_000) {
            engine.logForTest("debug", "frame=$it template score=0.8")
            engine.emitForTest(EngineEvent.Log(LogLevel.Trace, "frame=$it"))
        }
        engine.logForTest("debug", "节点 touch_to_start 匹配帧 score=0.8")
        engine.emitForTest(EngineEvent.Raw("screenshot", "raw screenshot bytes"))
        engine.emitForTest(EngineEvent.Raw("config", "{\"completeConfig\":\"not for diagnostics\"}"))
        boundaries.forEach { engine.logForTest("debug", it) }

        assertEquals(boundaries.map { "pipeline.node" to it }, sink.records)
    }

    @Test
    fun sinkFailuresDoNotAffectBreadcrumbsPublicWarningsOrFailureEvents() = runTest {
        var attempts = 0
        val engine = LimbusEngine(backgroundScope).apply {
            setDiagnosticSink { _, _ ->
                attempts++
                throw IllegalStateException("diagnostic storage unavailable")
            }
        }
        val received = mutableListOf<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { received += it }
        }
        val cause = IllegalArgumentException("action failed")

        engine.traceForTest("native.ocr.load", "")
        assertEquals(AutomationEngine.INVALID_TASK_ID, engine.appendTask("mirror", "{}"))
        engine.logForTest("info", "任务准备中")
        engine.logForTest("debug", "节点 main 执行动作 empty")
        engine.failForTest("流水线失败", cause)
        runCurrent()

        assertEquals(6, attempts)
        assertEquals(
            listOf(LogLevel.Warn, LogLevel.Info, LogLevel.Debug, LogLevel.Error),
            received.filterIsInstance<EngineEvent.Log>().map { it.level },
        )
        val failure = received.last() as EngineEvent.Failure
        assertEquals("流水线失败", failure.reason)
        assertSame(cause, failure.cause)
    }

    @Test
    fun diagnosticDetailIsBoundedWithoutTruncatingTheUiLog() = runTest {
        val sink = RecordingSink()
        val engine = LimbusEngine(backgroundScope).apply { setDiagnosticSink(sink) }
        val received = mutableListOf<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { received += it }
        }
        val message = "任务状态".repeat(2_000)

        engine.logForTest("info", message)
        runCurrent()

        assertEquals("log.info" to message.take(2048), sink.records.single())
        assertEquals(message, (received.single() as EngineEvent.Log).message)
    }

    @Test
    fun failureRecordsSelectedFactsWithoutSerializingTheThrowable() = runTest {
        val sink = RecordingSink()
        val engine = LimbusEngine(backgroundScope).apply { setDiagnosticSink(sink) }
        val cause = IllegalArgumentException("{\"completeConfig\":\"must not be logged\"}")
        cause.stackTrace = arrayOf(StackTraceElement("PrivateConfig", "dumpAll", "Config.kt", 1))

        engine.failForTest("动作未完成", cause)

        assertEquals(listOf(
            "log.error" to "动作未完成",
            "engine.failure" to "cause=java.lang.IllegalArgumentException reason=动作未完成",
        ), sink.records)
        assertFalse(sink.records.any { (_, detail) -> "completeConfig" in detail || "dumpAll" in detail })
    }

    @Test
    fun nativeBreadcrumbsRemainAvailableAndSinkCanBeRemoved() = runTest {
        val sink = RecordingSink()
        val engine = LimbusEngine(backgroundScope).apply { setDiagnosticSink(sink) }

        engine.traceForTest("native.classifier.prepare", "BattleWinrate.onnx")
        engine.logForTest("warn", "OCR 模型不可用")
        engine.setDiagnosticSink(null)
        engine.logForTest("info", "继续运行")
        engine.traceForTest("native.ocr.ready", "")

        assertEquals(listOf(
            "native.classifier.prepare" to "BattleWinrate.onnx",
            "log.warn" to "OCR 模型不可用",
        ), sink.records)
        assertTrue(engine.events.replayCache.isEmpty())
    }

    // Exercise the actual private event exits without connecting OpenCV/ONNX or a device.
    private fun LimbusEngine.emitForTest(event: EngineEvent) {
        LimbusEngine::class.java.getDeclaredMethod("emit", EngineEvent::class.java)
            .apply { isAccessible = true }.invoke(this, event)
    }

    private fun LimbusEngine.logForTest(method: String, message: String) {
        LimbusEngine::class.java.getDeclaredMethod(method, String::class.java)
            .apply { isAccessible = true }.invoke(this, message)
    }

    private fun LimbusEngine.traceForTest(phase: String, detail: String) {
        LimbusEngine::class.java.getDeclaredMethod("trace", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(this, phase, detail)
    }

    private fun LimbusEngine.failForTest(reason: String, cause: Throwable?) {
        LimbusEngine::class.java.getDeclaredMethod("fail", String::class.java, Throwable::class.java)
            .apply { isAccessible = true }.invoke(this, reason, cause)
    }
}
