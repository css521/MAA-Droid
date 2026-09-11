package com.maadroid.app.engine.limbus

import com.maadroid.app.engine.AutomationEngine
import com.maadroid.app.engine.ConnectionState
import com.maadroid.app.engine.DeviceHandle
import com.maadroid.app.engine.EngineEvent
import com.maadroid.app.engine.EngineDiagnosticSink
import com.maadroid.app.engine.EngineResources
import com.maadroid.app.engine.GameProfile
import com.maadroid.app.engine.LogLevel
import com.maadroid.app.engine.limbus.action.LimbusActions
import com.maadroid.app.engine.limbus.config.JsonLimbusConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.maadroid.app.engine.limbus.pipeline.NodeRecognizer
import com.maadroid.app.engine.limbus.pipeline.PipelineRegistry
import com.maadroid.app.engine.limbus.pipeline.PipelineRunner
import com.maadroid.app.engine.limbus.recognize.LimbusRecognizer
import com.maadroid.app.engine.limbus.resource.LimbusResourceManifest
import com.maadroid.app.engine.limbus.recognize.OnnxClassifier
import com.maadroid.app.engine.limbus.recognize.ocr.PpOcrEngine
import com.maadroid.app.engine.limbus.recognize.ResourcePackTemplateIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
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
    private var loadedLanguage = DEFAULT_LANGUAGE

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
    private var runner: LimbusTaskRun? = null

    @Volatile
    private var stopRequested = false

    private val taskIds = AtomicInteger(AutomationEngine.INVALID_TASK_ID)
    private val queue = ArrayList<QueuedTask>()

    /** check 节点的执行计数，跨节点共享 —— 队伍轮换靠它取模 */
    private val counters = ConcurrentHashMap<String, Int>()

    override val isRunning: Boolean get() = runJob?.isActive == true

    @Volatile private var diagnosticSink: EngineDiagnosticSink? = null
    override fun setDiagnosticSink(sink: EngineDiagnosticSink?) { diagnosticSink = sink }
    private fun trace(phase: String, detail: String = "") {
        try {
            // DiagnosticStore bounds/redacts persisted text; also cap work passed to any sink.
            diagnosticSink?.record(phase, detail.take(2048))
        } catch (_: Throwable) {
            // Diagnostics must not change engine execution or event delivery.
        }
    }

    // ---------------------------------------------------------------- prepare

    /**
     * 装载资源包。
     *
     * 兼容门闸（`required_actions` / `min_engine_version`）由宿主在调用前完成，
     * 见 [LimbusResourcePack.checkCompatibility] —— 那道闸的意义就是**装载前**拒绝，
     * 而不是跑到一半崩在某个未实现的动作上。
     */
    override suspend fun prepare(resources: EngineResources): Result<Unit> = runCatching {
        val resourceDir = resources.requireDirectory(LimbusResourcePack)
        // 动作注册必须先于流水线装配：装配会校验每个 action 名有无实现体
        LimbusActions.install()

        val taskDir = File(resourceDir, PIPELINE_DIR)
        val files = taskDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.associate { it.name to it.readText() }
            ?: throw IllegalStateException("资源包缺少 $PIPELINE_DIR 目录")
        require(files.isNotEmpty()) { "$PIPELINE_DIR 下没有流水线 JSON" }

        // 平台补丁：把部分文字判据从 template_match 改成 ocr。
        // **不写文件**——写文件会改变资源目录的 hash，verifyInstalledFiles 判定资源损坏，
        // 每次停止再启动都触发重新下载。直接在内存里解析内嵌常量。
        // 磁盘上的旧补丁文件（如有）忽略——EMBEDDED_PATCH 是唯一的真实来源。
        val patches = runCatching {
            Json.parseToJsonElement(EMBEDDED_PATCH).jsonObject
                .filterKeys { !it.startsWith("_") }
                .mapValues { it.value.jsonObject }
        }.onFailure { warn("内嵌补丁解析失败: ${it.message}") }.getOrDefault(emptyMap())
        if (patches.isNotEmpty()) info("已应用流水线补丁 ${patches.size} 个节点")
        // 不碰磁盘上的任何文件——无论是写入还是删除，都会破坏 verifyInstalledFiles 的 hash 校验。
        // 旧补丁文件（如有）在磁盘上无害：它不会被加载（EMBEDDED_PATCH 是唯一来源），
        // 也不会被清单校验拒绝（它已在清单的白名单里，见 LimbusResourceManifest.PIPELINE_PATCH）。

        val loaded = PipelineRegistry.load(files, patches)

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
        loadedLanguage = languageOf(resourceDir)
        info("已装载流水线 ${loaded.size} 个节点、素材 ${index.size} 张")
    }.onFailure { fail("装载资源失败: ${it.message}", it) }

    // ---------------------------------------------------------------- connect

    override suspend fun connect(device: DeviceHandle): Result<Unit> = runCatching {
        trace("native.opencv.load")
        check(OpenCVLoader.initLocal()) { "OpenCV 初始化失败，请重新安装完整 APK" }
        trace("native.opencv.ready")
        emit(EngineEvent.Connection(ConnectionState.Connecting))
        val index = templateIndex
            ?: throw IllegalStateException("请先 prepare 装载资源")

        this.device = device
        recognizer?.release()
        recognizer = null
        val dir = resourceDir ?: throw IllegalStateException("请先 prepare 装载资源")
        val classifier = OnnxClassifier(dir) { warn(it) }
        try {
            classifier.prepare { model -> trace("native.classifier.prepare", model) }
            trace("native.classifier.ready")
            trace("native.ocr.load")
            val ocr = PpOcrEngine.load(dir) { warn(it) } ?: error("OCR 模型加载失败，请重新安装边狱资源")
            trace("native.ocr.ready")
            recognizer = LimbusRecognizer(
                frames = device.frames,
                index = index,
                templateFileOf = index::fileOf,
                classifier = classifier,
                ocr = ocr,
                onLog = { warn(it) },
                titleAnchorFiles = index.titleAnchors,
                gameLanguage = loadedLanguage,
                onInfo = { info(it) },
                onDiagnostic = ::trace,
                captureDir = captureDirOrNull(dir),
            )
        } catch (failure: Throwable) {
            runCatching { classifier.release() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }

        // 强制显示规格：全部模板都按 1280x720 截取，分辨率不对就全都匹配不上
        val display = profile.display
        val ok = device.control.setDisplaySize(display.width, display.height, display.dpi)
        check(ok) { "设置显示规格 ${display.width}x${display.height} 失败" }

        emit(EngineEvent.Connection(ConnectionState.Connected))
    }.onFailure {
        runCatching { recognizer?.release() }.exceptionOrNull()?.let(it::addSuppressed)
        recognizer = null
        this.device = null
        emit(EngineEvent.Connection(ConnectionState.Failed, it.message))
    }

    // ------------------------------------------------------------------ tasks

    /**
     * 追加任务。
     *
     * @param type 流水线的入口节点名（`mirror` / `exp` / `thread` / `mail` / `reward`）
     * @param paramsJson **分节表**：顶层每个键是一个配置分节名。
     *
     * 之所以是分节表而不是「该任务自己那一节」：镜牢的动作要同时读 `mirror`、
     * `theme_pack`、`other_task` 三节。只传一节会让卡包权重与 ego 开关静默失效。
     * ```json
     * { "mirror": {"mirror_mode": "normal"}, "theme_pack": {"names": [...]} }
     * ```
     */
    override fun appendTask(type: String, paramsJson: String): Int {
        val reg = registry
        if (reg == null) {
            warn("尚未装载资源，无法追加任务 $type")
            return AutomationEngine.INVALID_TASK_ID
        }
        val task = LimbusTask.ofType(type)
        if (task == null) {
            warn("未知任务 $type，可选：${LimbusTask.entries.joinToString { it.type }}")
            return AutomationEngine.INVALID_TASK_ID
        }
        if (reg[task.nodeName] == null) {
            warn("流水线里没有 ${task.type} 对应的节点 ${task.nodeName}，资源包可能与本版本不匹配")
            return AutomationEngine.INVALID_TASK_ID
        }
        return synchronized(queue) {
            // Upstream runs each task type once with a configured repetition count.
            // Do not issue two IDs for one execution and silently lose the second ID.
            if (queue.any { it.type == type }) {
                warn("同类任务 $type 只能追加一次，请通过任务次数配置重复执行")
                return@synchronized AutomationEngine.INVALID_TASK_ID
            }
            taskIds.incrementAndGet().also { queue += QueuedTask(it, type, paramsJson) }
        }
    }

    override fun setTaskParams(taskId: Int, paramsJson: String): Boolean =
        synchronized(queue) {
            val idx = queue.indexOfFirst { it.id == taskId }
            if (idx < 0) return false
            queue[idx] = queue[idx].copy(paramsJson = paramsJson)
            true
        }

    // ------------------------------------------------------------------- run

    /**
     * 跑一次流水线。
     *
     * **所有选中的任务共用一次运行**，而不是每个任务跑一遍 —— 这是上游的模型：
     * 唯一入口 `main`，`task_center` 按各 `*_entry` 节点的 `enable` 决定跑哪些。
     * 若按任务各跑一遍，每次都要重新走「起游戏 → 回主页 → 进任务中心」，
     * 既慢又会在中途反复触发登录/公告等干扰界面。
     */
    override suspend fun start(): Boolean {
        if (isRunning) {
            warn("已在运行中")
            return false
        }
        val reg = registry ?: run { warn("尚未装载资源"); return false }
        val dev = device ?: run { warn("尚未连接设备"); return false }

        val tasks = synchronized(queue) { queue.toList().also { queue.clear() } }
        if (tasks.isEmpty()) {
            warn("任务队列为空")
            return false
        }

        val selected = tasks.mapNotNull { LimbusTask.ofType(it.type) }
        if (selected.isEmpty()) {
            warn("没有可识别的任务")
            return false
        }

        // 选中的开、其余一律关：不显式关掉的话，上游默认全开，
        // 用户只勾了镜牢却会连经验本一起跑
        val enableOverrides = LimbusTask.allNodeNames().associateWith { node ->
            selected.any { it.nodeName == node }
        }
        // 各任务的配置分节合并成一份：镜牢的动作要同时读 mirror / theme_pack / other_task
        val mergedConfig = mergeConfigs(tasks)
        val language = mergedConfig.str("other_task", "language", loadedLanguage)
        val config = mergedConfig.withLanguage(requireNotNull(resourceDir), language)
        val effectiveRegistry = reg.withEnabled(enableOverrides).withAndroidMailEntry().withTargetCounts(
            listOf("exp", "thread", "mirror").associate { "${it}_check" to config.int(it, "check_node_target_count", 1) },
        )
        if (language != loadedLanguage) {
            require(language in setOf("en", "zh")) { "不支持的游戏语言: $language" }
            templateIndex = ResourcePackTemplateIndex.load(requireNotNull(resourceDir), language) { warn(it) }
            loadedLanguage = language
            connect(dev).getOrThrow()
        }
        val rec = recognizer ?: return false
        val index = templateIndex ?: return false

        stopRequested = false
        counters.clear()

        runJob = scope.launch {
            var ok = false
            try {
                info("本次运行任务：${selected.joinToString { it.type }}")
                info("游戏语言：${if (language == "en") "英文" else "中文"}")
                ok = runPipeline(effectiveRegistry, dev, rec, index, config, tasks)
                if (ok && config.bool("other_task", "close_game", false)) {
                    val pkg = profile.gamePackages.firstOrNull { dev.control.isPackageInstalled(it) }
                    if (pkg != null) dev.control.stopApp(pkg)
                }
            } catch (cancelled: CancellationException) {
                ok = false
                throw cancelled
            } catch (failure: Throwable) {
                ok = false
                fail("任务运行后的处理失败: ${failure.message}", failure)
            } finally {
                emit(EngineEvent.AllTasksFinished(success = ok && !stopRequested))
            }
        }
        return true
    }

    /**
     * 合并各任务带来的配置分节。
     *
     * 同名分节以**后追加的任务**为准 —— 宿主按用户勾选顺序追加，后者更贴近用户当下意图。
     * 实践中不同任务的分节本就不重叠（exp / thread / mirror 各一节），
     * 只有 `other_task` 与 `theme_pack` 可能被多个任务同时带上。
     */
    private fun mergeConfigs(tasks: List<QueuedTask>): JsonLimbusConfig {
        val merged = LinkedHashMap<String, JsonObject>()
        for (task in tasks) {
            JsonLimbusConfig.sectionsOf(task.paramsJson).forEach { (k, v) -> merged[k] = v }
        }
        return JsonLimbusConfig(merged)
    }

    private suspend fun runPipeline(
        reg: PipelineRegistry,
        dev: DeviceHandle,
        rec: LimbusRecognizer,
        index: ResourcePackTemplateIndex,
        config: JsonLimbusConfig,
        tasks: List<QueuedTask>,
    ): Boolean {
        val nodeRecognizer = NodeRecognizer(rec) { warn(it) }
        val taskRun = LimbusTaskRun(
            registry = reg,
            tasks = tasks.associate { it.id to requireNotNull(LimbusTask.ofType(it.type)) },
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
            emit = ::emit,
            isStopRequested = { stopRequested },
            onLog = { debug(it) },
        )
        runner = taskRun

        return try {
            taskRun.execute()
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
    override fun release() {
        recognizer?.release()
        recognizer = null
        device = null
        synchronized(queue) { queue.clear() }
        registry = null
        templateIndex = null
        resourceDir = null
        counters.clear()
    }

    // ----------------------------------------------------------------- 事件

    private fun emit(event: EngineEvent) {
        // tryEmit 而非 emit：事件投递绝不该阻塞识别循环。缓冲满了宁可丢日志
        _events.tryEmit(event)
        // Persist selected boundaries even when no UI is collecting events. Raw payloads
        // and per-frame debug/trace messages never enter the synchronous diagnostic sink.
        when (event) {
            is EngineEvent.Task -> trace(
                "task.${event.phase.name.lowercase()}",
                "taskId=${event.taskId} type=${event.type}" +
                    (event.message?.let { " message=$it" } ?: ""),
            )
            is EngineEvent.AllTasksFinished -> trace("tasks.finished", "success=${event.success}")
            is EngineEvent.Log -> when (event.level) {
                LogLevel.Info, LogLevel.Warn, LogLevel.Error ->
                    trace("log.${event.level.name.lowercase()}", event.message)
                LogLevel.Debug -> {
                    // PipelineRunner emits these only at action/branch boundaries.
                    val message = event.message
                    if (message.startsWith("节点 ") && (
                        message.contains(" 执行动作 ") ||
                            message.contains(" 结束流水线:") ||
                            message.endsWith(" 的 next 与 interrupt 均未命中，该分支结束")
                        )) {
                        trace("pipeline.node", message)
                    }
                }
                LogLevel.Trace -> Unit
            }
            is EngineEvent.Failure -> trace(
                "engine.failure",
                "cause=${event.cause?.javaClass?.name ?: "none"} reason=${event.reason}",
            )
            else -> Unit
        }
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

    /**
     * 开发模式采集目录；未开启返回 null。
     *
     * **debug 构建默认开启**，不需要任何手动操作——安卓上没有方便的建文件手段，
     * 而适配期就是靠这些真帧裁素材，默认开着才用得上。
     * release 构建则要求显式放一个 [CAPTURE_MARKER] 标记文件，避免给普通用户白写盘。
     *
     * 位置放在 MAA 侧的 debug 树下——日志导出器已在收集它，采到的帧才能随日志一起拿到。
     * 找不到名为 Maa 的祖先目录时退到资源目录同级的 debug。
     */
    private fun captureDirOrNull(resourceDir: File): File? {
        var cursor: File? = resourceDir
        var debugRoot: File? = null
        while (cursor != null) {
            if (cursor.name == "Maa") {
                debugRoot = File(cursor, "debug/limbus")
                break
            }
            cursor = cursor.parentFile
        }
        val root = debugRoot ?: File(resourceDir.parentFile ?: resourceDir, "debug/limbus")
        val enabled = BuildConfig.DEBUG || File(root, CAPTURE_MARKER).isFile
        if (!enabled) return null
        val frames = File(root, "frames")
        info("开发模式：采集素材底片到 ${frames.absolutePath}")
        return frames
    }

    private data class QueuedTask(val id: Int, val type: String, val paramsJson: String)

    private companion object {
        /** 开发模式开关：该文件存在即开启采集，真机现场建/删即可，无需重新打包 */
        const val CAPTURE_MARKER = ".capture"

        /**
         * 内嵌补丁——每次 prepare 写入资源目录，确保不会残留旧版本的补丁。
         *
         * 真机踩过：旧版有 21 个 OCR 补丁（含 7 个 error_handler 弹窗候选），新版回退到
         * 10 个但资源没重装，旧补丁残留导致 7 个弹窗候选全走 OCR（每个 1~5 秒），
         * error_handler 打转 10 轮累积到 30~80 秒卡顿。
         */
        const val EMBEDDED_PATCH = """{"exp_choose_team":{"recognition":"ocr","params":{"text":"Details","mask":[880,100,120,50]}},"thread_choose_team":{"recognition":"ocr","params":{"text":"Details","mask":[880,100,120,50]}},"mirror_choose_team":{"recognition":"ocr","params":{"text":"Details","mask":[880,100,120,50]}},"mirror_ready_to_battle":{"recognition":"ocr","params":{"text":"Details","mask":[880,100,120,50]}},"touch_to_start":{"recognition":"ocr","params":{"text":"Clear all caches","mask":[170,630,190,55]}},"mirror_enter_resume":{"recognition":"ocr","params":{"text":"Resume","mask":[570,370,150,60]}},"mirror_enter_dungeon":{"recognition":"ocr","params":{"text":"Enter","mask":[1050,440,130,90]}},"check_enkephalin":{"params":{"post_delay":2.5}},"event_entry_dark":{"params":{"post_delay":2.5}},"event_entry":{"params":{"post_delay":2.5}}}"""

        const val PIPELINE_DIR = "config/task"

        /** 平台补丁文件路径，与清单白名单共用同一常量，避免两处不一致 */
        val PIPELINE_PATCH = LimbusResourceManifest.PIPELINE_PATCH
        const val LANGUAGE_MARKER = "config/language/current"
        const val DEFAULT_LANGUAGE = "zh"

        /** 识别循环日志较密，给足缓冲免得 tryEmit 频繁丢事件 */
        const val EVENT_BUFFER = 256
    }
}
