package com.aliothmoon.maadroid.engine.limbus

import com.aliothmoon.maadroid.engine.AutomationEngine
import com.aliothmoon.maadroid.engine.ConnectionState
import com.aliothmoon.maadroid.engine.DeviceHandle
import com.aliothmoon.maadroid.engine.EngineEvent
import com.aliothmoon.maadroid.engine.GameProfile
import com.aliothmoon.maadroid.engine.LogLevel
import com.aliothmoon.maadroid.engine.TaskPhase
import com.aliothmoon.maadroid.engine.limbus.action.LimbusActions
import com.aliothmoon.maadroid.engine.limbus.config.JsonLimbusConfig
import com.aliothmoon.maadroid.engine.limbus.pipeline.NodeRecognizer
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineRegistry
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineRunner
import com.aliothmoon.maadroid.engine.limbus.recognize.LimbusRecognizer
import com.aliothmoon.maadroid.engine.limbus.recognize.ResourcePackTemplateIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 边狱自动化引擎，跑在 **App 进程**。
 *
 * 与方舟引擎的根本差别：MaaCore 是 native 且必须在提权进程里访问帧缓冲，而这里是
 * Kotlin + OpenCV/ONNX，它们的 Java 绑定在 `app_process` 里加载不可靠。所以本引擎
 * 留在普通 App 进程，靠 [DeviceHandle] 经共享内存取帧、经 AIDL 注入输入。
 *
 * 任务的「业务流程」不在这个类里 —— 它住在资源包的流水线 JSON 中（133 个节点），
 * 本类只负责装载、把节点喂给 [PipelineRunner]、把执行过程翻成 [EngineEvent]。
 * 这正是选 LALC 而非 AALC 移植的理由：上游改流程能靠热更直接生效，不必发 APK。
 */
class LimbusEngine(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutomationEngine {

    override val profile: GameProfile = LimbusProfile

    private val _events = MutableSharedFlow<EngineEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
    )
    override val events: SharedFlow<EngineEvent> = _events

    @Volatile
    private var registry: PipelineRegistry? = null

    @Volatile
    private var resourceDir: File? = null

    @Volatile
    private var device: DeviceHandle? = null

    @Volatile
    private var recognizer: LimbusRecognizer? = null

    @Volatile
    private var templateIndex: ResourcePackTemplateIndex? = null

    @Volatile
    private var runJob: Job? = null

    @Volatile
    private var runner: PipelineRunner? = null

    @Volatile
    private var stopRequested = false

    private val taskIds = AtomicInteger(AutomationEngine.INVALID_TASK_ID)
    private val queue = ArrayList<QueuedTask>()

    /** check 节点的执行计数，跨节点共享 —— 队伍轮换靠它取模 */
    private val counters = ConcurrentHashMap<String, Int>()

    override val isRunning: Boolean get() = runJob?.isActive == true

    // ---------------------------------------------------------------- prepare

    /**
     * 装载资源包。
     *
     * 兼容门闸（`required_actions` / `min_engine_version`）由宿主在调用前完成，
     * 见 [LimbusResourcePack.checkCompatibility] —— 那道闸的意义就是**装载前**拒绝，
     * 而不是跑到一半崩在某个未实现的动作上。
     */
    override suspend fun prepare(resourceDir: File): Result<Unit> = runCatching {
        // 动作注册必须先于流水线装配：装配会校验每个 action 名有无实现体
        LimbusActions.install()

        val taskDir = File(resourceDir, PIPELINE_DIR)
        val files = taskDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.associate { it.name to it.readText() }
            ?: throw IllegalStateException("资源包缺少 $PIPELINE_DIR 目录")
        require(files.isNotEmpty()) { "$PIPELINE_DIR 下没有流水线 JSON" }

        val loaded = PipelineRegistry.load(files)

        val index = ResourcePackTemplateIndex.load(
            resourceDir = resourceDir,
            language = languageOf(resourceDir),
            onWarning = { warn(it) },
        )

        // 流水线引用的模板必须都在，否则那些节点永远识别不中 —— 与其运行时静默失效，
        // 不如在装载时就说清楚缺了什么
        val missing = loaded.referencedTemplates().filterNot { it in index }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "资源包缺少 ${missing.size} 个流水线引用的模板：" +
                    missing.take(5).joinToString("、") + if (missing.size > 5) "…" else ""
            )
        }

        registry = loaded
        templateIndex = index
        this.resourceDir = resourceDir
        info("已装载流水线 ${loaded.size} 个节点、素材 ${index.size} 张")
    }.onFailure { fail("装载资源失败: ${it.message}", it) }

    // ---------------------------------------------------------------- connect

    override suspend fun connect(device: DeviceHandle): Result<Unit> = runCatching {
        emit(EngineEvent.Connection(ConnectionState.Connecting))
        val index = templateIndex
            ?: throw IllegalStateException("请先 prepare 装载资源")

        this.device = device
        recognizer?.release()
        recognizer = LimbusRecognizer(
            frames = device.frames,
            index = index,
            templateFileOf = index::fileOf,
            onLog = { warn(it) },
        )

        // 强制显示规格：全部模板都按 1280x720 截取，分辨率不对就全都匹配不上
        val display = profile.display
        val ok = device.control.setDisplaySize(display.width, display.height, display.dpi)
        if (!ok) warn("设置显示规格 ${display.width}x${display.height} 失败，模板可能匹配不上")

        emit(EngineEvent.Connection(ConnectionState.Connected))
    }.onFailure {
        emit(EngineEvent.Connection(ConnectionState.Failed, it.message))
    }

    // ------------------------------------------------------------------ tasks

    /**
     * 追加任务。[type] 是流水线的入口节点名（如 `mirror` / `exp` / `thread`），
     * [paramsJson] 是该任务的配置分节内容，由任务面板产出。
     */
    override fun appendTask(type: String, paramsJson: String): Int {
        val reg = registry
        if (reg == null) {
            warn("尚未装载资源，无法追加任务 $type")
            return AutomationEngine.INVALID_TASK_ID
        }
        if (reg[type] == null) {
            warn("流水线里没有入口节点 $type")
            return AutomationEngine.INVALID_TASK_ID
        }
        val id = taskIds.incrementAndGet()
        synchronized(queue) { queue += QueuedTask(id, type, paramsJson) }
        return id
    }

    override fun setTaskParams(taskId: Int, paramsJson: String): Boolean =
        synchronized(queue) {
            val idx = queue.indexOfFirst { it.id == taskId }
            if (idx < 0) return false
            queue[idx] = queue[idx].copy(paramsJson = paramsJson)
            true
        }

    // ------------------------------------------------------------------- run

    override suspend fun start(): Boolean {
        if (isRunning) {
            warn("已在运行中")
            return false
        }
        val reg = registry ?: run { warn("尚未装载资源"); return false }
        val dev = device ?: run { warn("尚未连接设备"); return false }
        val rec = recognizer ?: run { warn("尚未连接设备"); return false }
        val index = templateIndex ?: run { warn("尚未装载资源"); return false }

        val tasks = synchronized(queue) { queue.toList().also { queue.clear() } }
        if (tasks.isEmpty()) {
            warn("任务队列为空")
            return false
        }

        stopRequested = false
        counters.clear()

        runJob = scope.launch {
            var allOk = true
            try {
                for (task in tasks) {
                    if (stopRequested) break
                    allOk = runOne(reg, dev, rec, index, task) && allOk
                }
            } finally {
                emit(EngineEvent.AllTasksFinished(success = allOk && !stopRequested))
            }
        }
        return true
    }

    private suspend fun runOne(
        reg: PipelineRegistry,
        dev: DeviceHandle,
        rec: LimbusRecognizer,
        index: ResourcePackTemplateIndex,
        task: QueuedTask,
    ): Boolean {
        emit(EngineEvent.Task(task.id, task.type, TaskPhase.Started))

        val config = JsonLimbusConfig.fromJsonStrings(mapOf(task.type to task.paramsJson))
        val nodeRecognizer = NodeRecognizer(rec) { warn(it) }

        val pipelineRunner = PipelineRunner(
            registry = reg,
            contextFactory = { name, node, matches ->
                LimbusActionContext(
                    node = node,
                    nodeName = name,
                    input = dev.input,
                    recognize = rec,
                    templates = index,
                    config = config,
                    recognizeResult = matches,
                    counters = counters,
                    cancelled = { stopRequested },
                    logger = { info(it) },
                )
            },
            recognizeGate = { nodeRecognizer.recognize(it) },
            onLog = { debug(it) },
        )
        runner = pipelineRunner

        return try {
            val reason = pipelineRunner.run(task.type)
            if (reason == null) {
                emit(EngineEvent.Task(task.id, task.type, TaskPhase.Completed))
                true
            } else {
                emit(EngineEvent.Task(task.id, task.type, TaskPhase.Failed, reason))
                false
            }
        } catch (e: StoppedException) {
            emit(EngineEvent.Task(task.id, task.type, TaskPhase.Stopped))
            false
        } catch (e: Throwable) {
            emit(EngineEvent.Task(task.id, task.type, TaskPhase.Failed, e.message))
            fail("任务 ${task.type} 异常终止: ${e.message}", e)
            false
        } finally {
            runner = null
        }
    }

    override suspend fun stop(): Boolean {
        stopRequested = true
        runner?.stop()
        runJob?.join()
        runJob = null
        return true
    }

    /** 释放模板缓存等原生资源。切换游戏或换语言时调用 */
    fun release() {
        recognizer?.release()
        recognizer = null
        device = null
    }

    // ----------------------------------------------------------------- 事件

    private fun emit(event: EngineEvent) {
        // tryEmit 而非 emit：事件投递绝不该阻塞识别循环。缓冲满了宁可丢日志
        _events.tryEmit(event)
    }

    private fun info(message: String) = emit(EngineEvent.Log(LogLevel.Info, message))
    private fun debug(message: String) = emit(EngineEvent.Log(LogLevel.Debug, message))
    private fun warn(message: String) = emit(EngineEvent.Log(LogLevel.Warn, message))

    private fun fail(reason: String, cause: Throwable? = null) {
        emit(EngineEvent.Log(LogLevel.Error, reason))
        emit(EngineEvent.Failure(reason, cause))
    }

    /**
     * 素材语言目录。
     *
     * 决定 `img/<语言>` 装哪一份 —— 边狱的按钮文字在不同语言下不同，选错会让
     * 带文字的模板全都匹配不上。取值来自资源包清单，缺失时用 zh。
     */
    private fun languageOf(resourceDir: File): String =
        File(resourceDir, LANGUAGE_MARKER).takeIf { it.isFile }?.readText()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_LANGUAGE

    private data class QueuedTask(val id: Int, val type: String, val paramsJson: String)

    private companion object {
        const val PIPELINE_DIR = "config/task"
        const val LANGUAGE_MARKER = "config/language/current"
        const val DEFAULT_LANGUAGE = "zh"

        /** 识别循环日志较密，给足缓冲免得 tryEmit 频繁丢事件 */
        const val EVENT_BUFFER = 256
    }
}
