package com.aliothmoon.maadroid.engine.limbus

import com.aliothmoon.maadroid.engine.EngineEvent
import com.aliothmoon.maadroid.engine.LogLevel
import com.aliothmoon.maadroid.engine.TaskPhase
import com.aliothmoon.maadroid.engine.limbus.action.ActionContext
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineNode
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineObserver
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineRegistry
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineRunner
import com.aliothmoon.maadroid.engine.limbus.pipeline.RecognizeOutcome
import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One shared upstream run, with a separate result for each selected task.
 * A task completes only when its check counter reaches the requested target. Exhausting
 * the pipeline stack (or an action returning Finish(true)) is not that evidence.
 */
internal class LimbusTaskRun(
    private val registry: PipelineRegistry,
    tasks: Map<Int, LimbusTask>,
    contextFactory: (String, PipelineNode, List<Match>) -> ActionContext,
    recognizeGate: suspend (PipelineNode) -> RecognizeOutcome,
    private val emit: (EngineEvent) -> Unit,
    private val isStopRequested: () -> Boolean = { false },
    onLog: (String) -> Unit = {},
) : PipelineObserver {
    private class Task(val id: Int, val type: LimbusTask) {
        var phase: TaskPhase? = null
    }

    private val tasks = tasks.map { (id, type) -> Task(id, type) }
    private val byEntry = this.tasks.associateBy { it.type.entryNodeName }
    private var lastNode: String? = null
    @Volatile private var stopped = false

    internal val pipeline = PipelineRunner(registry, contextFactory, recognizeGate, onLog, this)

    override fun onNodeEntered(name: String) {
        lastNode = name
        val node = registry.require(name)
        // Recovery can reach mirror_check from a retained settlement screen without
        // revisiting mirror_entry. That real check also starts accounting for the task.
        val task = byEntry[name] ?: node.takeIf { it.type == PipelineNode.TYPE_CHECK }
            ?.str("disable_node")?.let(byEntry::get)
        task?.let(::start)
    }

    override fun onCheckEvaluated(name: String, disableNode: String?, count: Int, target: Int) {
        val task = byEntry[disableNode] ?: return
        if (target > 0 && count >= target && task.phase != TaskPhase.Completed) {
            start(task)
            report(task, TaskPhase.Completed)
        }
    }

    fun stop() {
        stopped = true
        pipeline.stop()
    }

    suspend fun execute(entry: String = LimbusTask.ENTRY_NODE): Boolean {
        return try {
            currentCoroutineContext().ensureActive()
            if (shouldStop()) return finishStopped()
            validateSelection()
            val reason = pipeline.run(entry)
            when {
                shouldStop() -> finishStopped()
                reason != null -> finishFailed(reason)
                tasks.all { it.phase == TaskPhase.Completed } -> true
                else -> finishFailed(
                    "流水线已结束，但以下任务未达到完成条件：" +
                        tasks.filter { it.phase != TaskPhase.Completed }.joinToString { it.type.type },
                )
            }
        } catch (_: StoppedException) {
            finishStopped()
        } catch (cancelled: CancellationException) {
            finishStopped()
            throw cancelled
        } catch (failure: Throwable) {
            finishFailed("流水线异常终止: ${failure.message ?: failure.javaClass.simpleName}", failure)
        }
    }

    private fun validateSelection() {
        require(tasks.isNotEmpty()) { "任务队列为空" }
        require(tasks.all { it.id > 0 }) { "任务 ID 必须为正数" }
        require(byEntry.size == tasks.size) { "同类任务只能追加一次，请通过任务次数配置重复执行" }
        for (task in tasks) {
            val entry = task.type.entryNodeName
            require(registry[entry]?.enable == true) { "已选任务 ${task.type.type} 的入口未启用" }
            val checks = registry.names().map(registry::require).filter {
                it.type == PipelineNode.TYPE_CHECK && it.str("disable_node") == entry
            }
            require(checks.isNotEmpty() && checks.all { (it.num("target_count") ?: 0.0) >= 1.0 }) {
                "已选任务 ${task.type.type} 缺少有效的完成检查或次数小于 1"
            }
        }
    }

    private fun shouldStop() = stopped || isStopRequested()

    private fun start(task: Task) {
        if (task.phase == null) report(task, TaskPhase.Started)
    }

    private fun report(task: Task, phase: TaskPhase, message: String? = null) {
        task.phase = phase
        emit(EngineEvent.Task(task.id, task.type.type, phase, message))
    }

    private fun finishStopped(): Boolean {
        tasks.filter { it.phase != TaskPhase.Completed }.forEach { task ->
            report(task, TaskPhase.Stopped, if (task.phase == null) "未开始：本次运行已停止" else "任务已停止")
        }
        return false
    }

    private fun finishFailed(reason: String, cause: Throwable? = null): Boolean {
        val detail = lastNode?.let { "$reason（节点 $it）" } ?: reason
        tasks.filter { it.phase != TaskPhase.Completed }.forEach { task ->
            if (task.phase == TaskPhase.Started) report(task, TaskPhase.Failed, detail)
            else report(task, TaskPhase.Stopped, "未开始：$detail")
        }
        emit(EngineEvent.Log(LogLevel.Error, detail))
        emit(EngineEvent.Failure(detail, cause))
        return false
    }
}
