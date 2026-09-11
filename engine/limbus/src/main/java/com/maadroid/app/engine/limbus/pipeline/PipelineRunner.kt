package com.maadroid.app.engine.limbus.pipeline

import com.maadroid.app.engine.limbus.action.ActionContext
import com.maadroid.app.engine.limbus.action.ActionOutcome
import com.maadroid.app.engine.limbus.action.ActionRegistry
import com.maadroid.app.engine.limbus.recognize.Match
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
    private val observer: PipelineObserver? = null,
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
    /** 每个节点「经 interrupt 回到自身且 next 仍全灭」的连续轮数；next 一命中即清零 */
    private val interruptLoops = HashMap<String, Int>()
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
        interruptLoops.clear()
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
        observer?.onNodeEntered(step.name)
        if (!running) return
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
            ActionOutcome.Return -> {
                // Every Action has its own Route directly underneath, including retries
                // and Goto targets. Drop only that continuation, never the caller's Route.
                check(stack.lastOrNull() == Step.Route(step.name, step.node)) {
                    "节点 ${step.name} 缺少待返回的路由"
                }
                stack.removeLast()
                onLog("节点 ${step.name} 完成子分支，返回调用方")
            }
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
            val count = ctx.counterOf(step.name)
            observer?.onCheckEvaluated(step.name, step.node.str("disable_node"), count, target)
            if (count < target) {
                val origin = step.node.str("origin") ?: error("检查节点 ${step.name} 缺少 origin")
                stack.addLast(Step.Route(origin, registry.require(origin)))
                stack.addLast(Step.Action(origin, registry.require(origin)))
                rateLimit(step.node, started)
                return
            }
            step.node.str("disable_node")?.let { disabled += it }
        }

        // next 按声明顺序取第一个命中的，不是取分数最高的
        val next = probe(step.node.next)
        if (next.hit != null) {
            // 命中的若是无条件 direct 节点、而它前面的真候选全落空，那这不是"识别到了什么"，
            // 而是"识别全败后落到了兜底分支"。上游把这种兜底写成 report_error（例如
            // exp_can_not_skip_battle 报"该跳过未解锁"），于是识别失败被伪装成业务结论，
            // 排查方向被带偏。这里必须把真实原因和每个候选差多少讲清楚。
            if (registry[next.hit]?.recognition == PipelineNode.RECOGNITION_DIRECT && next.misses.isNotEmpty()) {
                onLog(
                    "节点 ${step.name} 的真候选全部未命中，落到兜底节点 ${next.hit}；" +
                        "各候选差距: ${next.misses.joinToString("; ")}",
                )
            }
            // next 命中即有进展，清掉打转计数
            interruptLoops.remove(step.name)
            stack.addLast(Step.Route(next.hit, registry.require(next.hit)))
            stack.addLast(Step.Action(next.hit, registry.require(next.hit)))
            rateLimit(step.node, started)
            return
        }

        // next 全不命中才试 interrupt
        val interrupt = probe(registry.interruptsOf(step.name))
        if (interrupt.hit != null) {
            val node = registry.require(interrupt.hit)
            // 「next 全灭 + interrupt 接住」是识别失败最常见的走法：上游给几乎每个节点都
            // 默认挂了 error_handler 作为 interrupt。而 interrupt 处理完要回到本节点重新
            // 路由，于是 next 一直不中就会原地打转 —— 真机上表现为 error_handler 每两秒
            // 刷一行、画面不动，且**过去这里一条诊断都不打**，看不出 next 各候选差多少。
            val loops = interruptLoops.merge(step.name, 1, Int::plus) ?: 1
            if (interrupt.misses.isEmpty() && next.misses.isNotEmpty() &&
                (loops == 1 || loops % LOOP_LOG_EVERY == 0)
            ) {
                onLog(
                    "节点 ${step.name} 的 next 全部未命中，由中断 ${interrupt.hit} 接管" +
                        "（第 $loops 轮）；各候选差距: ${next.misses.joinToString("; ")}",
                )
            }
            if (loops >= MAX_INTERRUPT_LOOPS) {
                // MAX_STEPS 只防「永远不停」，不防「停不下来的原地打转」：按实测 2.2 秒
                // 一轮算，8000 步要转约 5 小时才报死循环。这里带着证据尽早失败。
                failure = "节点 ${step.name} 经中断 ${interrupt.hit} 反复回到自身 $loops 轮仍无进展" +
                    (if (next.misses.isEmpty()) "" else "；各候选差距: ${next.misses.joinToString("; ")}")
                onLog("节点 ${step.name} 原地打转 $loops 轮，终止流水线")
                stack.clear()
                running = false
                return
            }
            // 中断处理完要回到本节点继续路由 —— 先压自身的 Route，它会最后执行
            stack.addLast(Step.Route(step.name, step.node))
            stack.addLast(Step.Route(interrupt.hit, node))
            stack.addLast(Step.Action(interrupt.hit, node))
            rateLimit(step.node, started)
            return
        }

        val misses = next.misses + interrupt.misses
        onLog(
            "节点 ${step.name} 的 next 与 interrupt 均未命中，该分支结束" +
                if (misses.isEmpty()) "" else "；各候选差距: ${misses.joinToString("; ")}",
        )
        rateLimit(step.node, started)
    }

    /** 一轮候选识别的结果：命中者（可能为 null）与命中之前所有落空候选的量化差距 */
    private class Probe(val hit: String?, val misses: List<String>)

    /**
     * 返回第一个识别命中的节点名，并记下它的命中坐标供该节点的动作取用。
     *
     * 按声明顺序取**第一个**命中的，不是取分数最高的 —— 上游如此，
     * 流水线的 next 顺序本身就是优先级。
     *
     * 同时收集落空候选的 [TemplateMiss]。**这里只收集不输出**：路由本来就靠
     * 「没命中就试下一个」，未命中是常态，逐次打日志会把日志淹掉。
     * 由调用方在真正的决策点（落到兜底分支 / 全都不中）才汇总输出。
     */
    private suspend fun probe(candidates: List<String>): Probe {
        val misses = ArrayList<String>()
        for (name in candidates) {
            val node = registry[name] ?: continue
            // enable 在 recognizeGate 里也判了；这里先挡一次省掉一次截图识别
            if (!node.enable || name in disabled) continue
            val outcome = recognizeGate(node)
            if (outcome.hit) {
                lastRecognition[name] = outcome.matches
                return Probe(name, misses)
            }
            outcome.miss?.let { misses += "$name($it)" }
        }
        return Probe(null, misses)
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

    internal companion object {
        /**
         * 步数上限。上游没有这道保险，实际用起来遇到识别持续不中时会无限空转；
         * 这里给一个明确的失败而不是让用户干等。按 rateLimit 1 秒估算约两小时。
         */
        const val MAX_STEPS = 8000

        /** 同一节点经中断反复回到自身、next 始终全灭的容忍轮数 */
        const val MAX_INTERRUPT_LOOPS = 10

        /** 打转期间每隔几轮复述一次差距，避免每两秒刷一行 */
        const val LOOP_LOG_EVERY = 5
    }
}
