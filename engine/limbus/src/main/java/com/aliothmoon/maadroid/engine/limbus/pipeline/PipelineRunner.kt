package com.aliothmoon.maadroid.engine.limbus.pipeline

import com.aliothmoon.maadroid.engine.limbus.action.ActionContext
import com.aliothmoon.maadroid.engine.limbus.action.ActionOutcome
import com.aliothmoon.maadroid.engine.limbus.action.ActionRegistry
import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 流水线执行器。
 *
 * 上游用「函数栈」实现调度（`workflow/task_pipeline.py`）：栈里压的是节点的
 * `do_action` 与 `get_next`，弹一个执行一个，动作可以往栈里压新的延续。
 * 这里改写为显式的**步骤栈**，语义等价但可读、可测：
 *
 * ```
 * 弹出 Action(节点) → 跑动作 → 按 ActionOutcome 决定压什么
 * 弹出 Route(节点)  → 截图识别 next；命中则压 Action+Route(命中节点)
 *                     全不命中则试 interrupt；命中则压 Action+Route(中断节点)+Route(自身)
 *                     仍不命中 → 该分支结束
 * ```
 *
 * 关键语义（都来自上游，改动会让流水线行为漂移）：
 * - **interrupt 执行完要回到原节点继续路由**，所以压栈时多压一个 `Route(自身)`
 * - next 按声明顺序取**第一个**识别命中的，不是取分数最高的
 * - `inverse` 节点是「识别不中才算命中」
 * - `rateLimit` 是单次路由的最小耗时，用于限速避免空转烧 CPU
 * - `enable=false` 的节点在路由时直接跳过（上游 check 节点会把目标节点置 false 来"用完即弃"）
 */
class PipelineRunner(
    private val registry: PipelineRegistry,
    private val contextFactory: (String, PipelineNode, List<Match>) -> ActionContext,
    private val recognizeGate: suspend (PipelineNode) -> RecognizeOutcome,
    private val onLog: (String) -> Unit = {},
) {

    private sealed interface Step {
        val node: PipelineNode
        val name: String

        /** 执行该节点的动作 */
        data class Action(override val name: String, override val node: PipelineNode) : Step

        /** 对该节点做路由：识别 next / interrupt 决定下一步 */
        data class Route(override val name: String, override val node: PipelineNode) : Step
    }

    /**
     * 每个节点最近一次路由识别的命中结果。
     *
     * 上游把它写在节点的 params 里；本项目节点不可变，故存在这里，
     * 由动作经 [ActionContext.recognizeResult] 读取。
     */
    private val lastRecognition = HashMap<String, List<Match>>()
    private val disabled = HashSet<String>()
    private var failure: String? = null

    /** 上游的 continue_run 事件；置 false 后主循环尽快退出 */
    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun stop() {
        running = false
    }

    /**
     * 从 [entry] 开始执行到栈空。
     *
     * @return 结束原因；正常跑完返回 null
     */
    suspend fun run(entry: String): String? {
        val start = registry[entry] ?: return "入口节点未注册: $entry"
        running = true
        val stack = ArrayDeque<Step>()
        // 与上游一致：先压 get_next 再压 do_action，故动作先执行、随后才路由
        lastRecognition.clear()
        disabled.clear()
        failure = null
        stack.addLast(Step.Route(entry, start))
        stack.addLast(Step.Action(entry, start))

        var steps = 0
        try { while (running && stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (++steps > MAX_STEPS) return "流水线步数超过 $MAX_STEPS，疑似死循环"
            when (val step = stack.removeLast()) {
                is Step.Action -> runAction(step, stack)
                is Step.Route -> route(step, stack)
            }
        }
        return failure ?: if (!running && stack.isNotEmpty()) "任务已停止" else null
        } finally { running = false }
    }

    private suspend fun runAction(step: Step.Action, stack: ArrayDeque<Step>) {
        val actionName = step.node.action
        val backend = ActionRegistry[actionName]
        if (backend == null) {
            // 没有原生 handler 的 action 是另一条子链，必须先识别再运行，之后回到父节点。
            val target = registry.require(actionName)
            if (actionName !in disabled && target.enable) {
                val outcome = recognizeGate(target)
                if (outcome.hit) {
                    lastRecognition[actionName] = outcome.matches
                    stack.addLast(Step.Route(actionName, target))
                    stack.addLast(Step.Action(actionName, target))
                }
            }
            return
        }
        val ctx = contextFactory(step.name, step.node, lastRecognition[step.name].orEmpty())
        onLog("节点 ${step.name} 执行动作 $actionName")
        // 上游仅对原生 handler 包裹 pre/post_delay，子链路由本身不额外等待。
        // touch_to_start 的 post_delay=3 尤其重要：不能点击后立即再发返回键。
        actionDelay(step.node, "pre_delay")
        currentCoroutineContext().ensureActive()
        if (!running) return
        val outcome = backend.execute(ctx)
        if (outcome !is ActionOutcome.Finish) actionDelay(step.node, "post_delay")
        when (outcome) {
            ActionOutcome.Continue -> Unit
            ActionOutcome.RetrySelf -> {
                // 上游用于「技能全未选中，点一下重开 p」这类重试
                stack.addLast(Step.Action(step.name, step.node))
            }
            is ActionOutcome.Goto -> {
                val target = registry[outcome.nodeName]
                if (target == null) {
                    error("节点 ${step.name} 要求跳转到未注册节点 ${outcome.nodeName}")
                } else {
                    stack.addLast(Step.Route(outcome.nodeName, target))
                    stack.addLast(Step.Action(outcome.nodeName, target))
                }
            }
            is ActionOutcome.Finish -> {
                if (!outcome.success) failure = outcome.message ?: "动作 ${step.name} 失败"
                onLog("节点 ${step.name} 结束流水线: ${outcome.message ?: ""}")
                stack.clear()
                running = false
            }
        }
    }

    private suspend fun route(step: Step.Route, stack: ArrayDeque<Step>) {
        val started = System.nanoTime()
        if (step.node.type == "check") {
            val ctx = contextFactory(step.name, step.node, lastRecognition[step.name].orEmpty())
            val target = step.node.num("target_count")?.toInt() ?: error("检查节点 ${step.name} 缺少 target_count")
            if (ctx.counterOf(step.name) < target) {
                val origin = step.node.str("origin") ?: error("检查节点 ${step.name} 缺少 origin")
                stack.addLast(Step.Route(origin, registry.require(origin)))
                stack.addLast(Step.Action(origin, registry.require(origin)))
                rateLimit(step.node, started)
                return
            }
            step.node.str("disable_node")?.let { disabled += it }
        }

        // next 按声明顺序取第一个命中的，不是取分数最高的
        val hitNext = firstHit(step.node.next)
        if (hitNext != null) {
            stack.addLast(Step.Route(hitNext, registry.require(hitNext)))
            stack.addLast(Step.Action(hitNext, registry.require(hitNext)))
            rateLimit(step.node, started)
            return
        }

        // next 全不命中才试 interrupt
        val hitInterrupt = firstHit(registry.interruptsOf(step.name))
        if (hitInterrupt != null) {
            val node = registry.require(hitInterrupt)
            // 中断处理完要回到本节点继续路由 —— 先压自身的 Route，它会最后执行
            stack.addLast(Step.Route(step.name, step.node))
            stack.addLast(Step.Route(hitInterrupt, node))
            stack.addLast(Step.Action(hitInterrupt, node))
            rateLimit(step.node, started)
            return
        }

        onLog("节点 ${step.name} 的 next 与 interrupt 均未命中，该分支结束")
        rateLimit(step.node, started)
    }

    /**
     * 返回第一个识别命中的节点名，并记下它的命中坐标供该节点的动作取用。
     *
     * 按声明顺序取**第一个**命中的，不是取分数最高的 —— 上游如此，
     * 流水线的 next 顺序本身就是优先级。
     */
    private suspend fun firstHit(candidates: List<String>): String? {
        for (name in candidates) {
            val node = registry[name] ?: continue
            // enable 在 recognizeGate 里也判了；这里先挡一次省掉一次截图识别
            if (!node.enable || name in disabled) continue
            val outcome = recognizeGate(node)
            if (outcome.hit) {
                lastRecognition[name] = outcome.matches
                return name
            }
        }
        return null
    }

    /** 补足 rateLimit 指定的最小耗时，避免识别不中时空转烧 CPU */
    private suspend fun rateLimit(node: PipelineNode, startedNanos: Long) {
        val elapsedSec = (System.nanoTime() - startedNanos) / 1_000_000_000.0
        val remain = node.rateLimit - elapsedSec
        if (remain > 0) delayer(remain)
    }

    private suspend fun actionDelay(node: PipelineNode, key: String) {
        val seconds = node.num(key) ?: 0.1
        require(seconds.isFinite() && seconds >= 0) { "$key 必须是非负有限秒数" }
        if (seconds > 0) delayer(seconds)
    }

    /** 可替换的睡眠，便于单测里瞬间跑完 */
    internal var delayer: suspend (Double) -> Unit = { seconds ->
        kotlinx.coroutines.delay((seconds * 1000).toLong())
    }

    private companion object {
        /**
         * 步数上限。上游没有这道保险，实际用起来遇到识别持续不中时会无限空转；
         * 这里给一个明确的失败而不是让用户干等。按 rateLimit 1 秒估算约两小时。
         */
        const val MAX_STEPS = 8000
    }
}
