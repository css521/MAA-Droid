package com.maadroid.app.engine.limbus

import com.maadroid.app.engine.InputSink
import com.maadroid.app.engine.limbus.action.ActionContext
import com.maadroid.app.engine.limbus.action.LimbusConfig
import com.maadroid.app.engine.limbus.pipeline.PipelineNode
import com.maadroid.app.engine.limbus.recognize.Match
import com.maadroid.app.engine.limbus.recognize.Recognizer
import com.maadroid.app.engine.limbus.recognize.TemplateIndex
import java.util.concurrent.ConcurrentHashMap

/**
 * 动作执行上下文的实现。
 *
 * 计数器（[counterOf] / [incrementCounter]）刻意由**引擎**持有而不是每个节点自己存：
 * 上游把 `execute_count` 写在节点 params 里原地自增，而本项目的 [PipelineNode]
 * 不可变（要能被资源包整体替换），所以计数必须活在节点之外、且跨节点共享 ——
 * 队伍轮换正是「在 A 节点读 B_check 节点的计数」。
 */
internal class LimbusActionContext(
    override val node: PipelineNode,
    override val nodeName: String,
    override val input: InputSink,
    override val recognize: Recognizer,
    override val templates: TemplateIndex,
    override val config: LimbusConfig,
    override val recognizeResult: List<Match>,
    private val counters: ConcurrentHashMap<String, Int>,
    private val cancelled: () -> Boolean,
    private val logger: (String) -> Unit,
) : ActionContext {

    override fun log(message: String) = logger(message)

    override fun ensureActive() {
        if (cancelled()) throw StoppedException()
    }

    override suspend fun delay(seconds: Double) {
        if (seconds <= 0) return
        kotlinx.coroutines.delay((seconds * 1000).toLong())
    }

    override fun counterOf(nodeName: String): Int = counters[nodeName] ?: 0

    override fun incrementCounter(nodeName: String): Int =
        counters.merge(nodeName, 1, Int::plus) ?: 1
}

/**
 * 用户主动停止。
 *
 * 单独一个类型而不复用 CancellationException：后者会被协程框架当作正常取消
 * 静默吞掉，而这里需要让引擎明确区分「用户停的」与「出错了」，从而给出不同的事件。
 */
internal class StoppedException : RuntimeException("已停止")
