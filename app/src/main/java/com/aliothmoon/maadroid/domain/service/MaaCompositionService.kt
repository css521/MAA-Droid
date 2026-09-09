package com.aliothmoon.maadroid.domain.service

import android.content.Context
import android.os.IBinder
import com.aliothmoon.maadroid.MaaCoreService
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.constant.DefaultDisplayConfig
import com.aliothmoon.maadroid.constant.Packages
import com.aliothmoon.maadroid.data.model.LogLevel
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.data.preferences.TaskChainState
import com.aliothmoon.maadroid.data.resource.ActivityManager
import com.aliothmoon.maadroid.domain.models.RemoteBackend
import com.aliothmoon.maadroid.domain.models.RunMode
import com.aliothmoon.maadroid.domain.notification.LiveSessionCoordinator
import com.aliothmoon.maadroid.engine.arknights.state.MaaExecutionState
import com.aliothmoon.maadroid.engine.arknights.core.AidlMaaCoreClient
import com.aliothmoon.maadroid.engine.arknights.core.MaaCoreSession
import com.aliothmoon.maadroid.maa.callback.MaaCallbackDispatcher
import com.aliothmoon.maadroid.maa.callback.MaaExecutionStateHolder
import com.aliothmoon.maadroid.maa.callback.SubTaskHandler
import com.aliothmoon.maadroid.maa.callback.TaskChainStatusTracker
import com.aliothmoon.maadroid.maa.callback.ToolboxResultCollector
import com.aliothmoon.maadroid.maa.task.MaaTaskParams
import com.aliothmoon.maadroid.manager.RemoteAccessCoordinator
import com.aliothmoon.maadroid.manager.RemoteServiceManager
import com.aliothmoon.maadroid.manager.RemoteServiceManager.useRemoteService
import com.aliothmoon.maadroid.manager.ShizukuManager
import com.aliothmoon.maadroid.remote.PermissionGrantRequest
import com.aliothmoon.maadroid.utils.Misc
import com.aliothmoon.maadroid.common.i18n.UiText
import com.aliothmoon.maadroid.common.i18n.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.aliothmoon.maadroid.engine.arknights.core.maaCoreService
import com.aliothmoon.maadroid.engine.EngineExecutionCoordinator
import com.aliothmoon.maadroid.remote.EngineIds

class MaaCompositionService(
    private val context: Context,
    private val resourceLoader: MaaResourceLoader,
    private val appSettings: AppSettingsManager,
    private val gameMuteCoordinator: GameMuteCoordinator,
    private val unifiedStateDispatcher: UnifiedStateDispatcher,
    private val sessionLogger: MaaSessionLogger,
    private val activityManager: ActivityManager,
    private val appWatchdog: AppWatchdog,
    private val gameFpsWatcher: GameFpsWatcher,
    private val taskChainState: TaskChainState,
    private val subTaskHandler: SubTaskHandler,
    private val taskChainStatusTracker: TaskChainStatusTracker,
    private val notificationCenter: MaaNotificationCenter,
    private val liveCoordinator: LiveSessionCoordinator,
    private val dropsRefresher: FightDropsRefresher,
    private val toolboxResultCollector: ToolboxResultCollector,
    private val coreDataPusher: CoreDataPusher,
) : MaaExecutionStateHolder {

    private val _state = MutableStateFlow(MaaExecutionState.IDLE)
    val state: StateFlow<MaaExecutionState> = _state.asStateFlow()
    private val executionLease = AtomicReference<EngineExecutionCoordinator.Lease?>()
    private val startupInProgress = AtomicBoolean(false)

    /** 宿主任务页只需知道设备是否占用，无需依赖方舟的执行状态枚举。 */
    val isTaskActive: Boolean
        get() = when (_state.value) {
            MaaExecutionState.STARTING, MaaExecutionState.RUNNING, MaaExecutionState.STOPPING -> true
            MaaExecutionState.IDLE, MaaExecutionState.ERROR -> false
        }

    /** 停止发起方：用户操作 / 回调侧（掉线等）中止 */
    enum class StopOrigin { USER, CALLBACK }

    /** 本轮 STOPPING 的发起方，STARTING 时复位；供 TaskEndRegistry 区分手动停止与异常中止 */
    @Volatile
    var lastStopOrigin: StopOrigin = StopOrigin.USER
        private set

    private val defaultResolution = DefaultDisplayConfig.Resolution(
        DefaultDisplayConfig.WIDTH, DefaultDisplayConfig.HEIGHT, DefaultDisplayConfig.DPI
    )
    private val _displayResolution = MutableStateFlow(defaultResolution)
    val displayResolution: StateFlow<DefaultDisplayConfig.Resolution> =
        _displayResolution.asStateFlow()

    override fun currentRunState(): MaaExecutionState = _state.value

    override fun reportRunState(state: MaaExecutionState) {
        // STOPPING 期间，回调不主动设 IDLE — 由 finishStop() 统一处理
        if (_state.value == MaaExecutionState.STOPPING && state == MaaExecutionState.IDLE) {
            return
        }
        setRunState(state)
    }

    override fun requestStopFromCallback() {
        val current = _state.value
        if (current != MaaExecutionState.RUNNING && current != MaaExecutionState.STARTING) {
            Timber.d("忽略回调侧停止请求：当前状态 $current")
            return
        }
        // 掉线弹窗会连着触发 OfflineConfirm 与 OfflineConfirmAfterBattle，去重后只停一次
        if (!callbackStopRequested.compareAndSet(false, true)) {
            Timber.d("回调侧停止请求已在处理中")
            return
        }
        scope.launch {
            try {
                stop(StopOrigin.CALLBACK)
            } finally {
                callbackStopRequested.set(false)
            }
        }
    }

    private fun setRunState(state: MaaExecutionState) {
        if (state == MaaExecutionState.STARTING) {
            lastStopOrigin = StopOrigin.USER
            // 顺带提前拿断网闸门：这里在 IO 线程，留给 FGS 的 startForeground 拿会占主线程
            liveCoordinator.prepareProgress(liveCoordinator.beginRun())
        }
        _state.value = state
        if (!startupInProgress.get() && (state == MaaExecutionState.IDLE || state == MaaExecutionState.ERROR)) {
            executionLease.getAndSet(null)?.close()
        }
        // 仅在 STARTING 拉起前台服务；终态不做外部 stopService —
        // 快速失败时 stopService 可能抢在服务创建之前到达，系统会因
        // startForeground 契约未履行直接杀进程（RemoteServiceException）。
        // 服务自身观察状态流，startForeground 后对 IDLE/ERROR 自行 stopSelf
        if (state == MaaExecutionState.STARTING) {
            TaskExecutionService.start(context)
        }
    }

    private val callbackDispatcher: MaaCallbackDispatcher by inject(MaaCallbackDispatcher::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class CoreBinding(val binder: IBinder, val session: MaaCoreSession)
    private val coreBinding = AtomicReference<CoreBinding?>()
    // 受 startMutex 保护。只清理本轮实际接触的核心，不能误停上轮闲置 Binder。
    private var startupBinding: CoreBinding? = null
    private var startupCleanupResult: Boolean? = null

    private fun sessionFor(maa: MaaCoreService): MaaCoreSession {
        val binder = maa.asBinder()
        coreBinding.get()?.takeIf { it.binder == binder }?.let { return it.session }
        val session = MaaCoreSession(AidlMaaCoreClient(maa), onEvent = { msg, json ->
            callbackDispatcher.onEvent(msg, json)
        })
        coreBinding.getAndSet(CoreBinding(binder, session))?.session?.invalidate()
        return session
    }

    /** 回调侧停止请求的去重闸门 */
    private val callbackStopRequested = AtomicBoolean(false)

    sealed class StartResult {
        data class Success(val version: String) : StartResult()

        /** 资源加载失败（网络/IO/解压） */
        data class ResourceError(
            val exception: Throwable? = null
        ) : StartResult()

        /** MaaCore 实例初始化失败（创建实例、设置选项） */
        data class InitializationError(
            val phase: InitPhase,
        ) : StartResult() {
            enum class InitPhase {
                CREATE_INSTANCE,
                SET_TOUCH_MODE,
            }
        }

        /** 显示/连接层失败（虚拟屏幕、连接）；[shizukuAsRoot] 标记 Shizuku 后端却以 root 运行 */
        data class ConnectionError(
            val phase: ConnectPhase,
            val shizukuAsRoot: Boolean = false,
        ) : StartResult() {
            enum class ConnectPhase {
                DISPLAY_MODE,
                VIRTUAL_DISPLAY,
                MAA_CONNECT,
            }
        }

        /** MaaCore 运行时启动失败 */
        data object StartError : StartResult()

        /** 前台模式下检测到竖屏（高 > 宽），需要横屏才能运行 */
        data object PortraitOrientationError : StartResult()

        /** 前台模式下物理分辨率不是 16:9，MAA 识别要求 16:9 */
        data object InvalidAspectRatioError : StartResult()

        /** 远程服务正在连接中，任务无法立即启动 */
        data object ServiceConnecting : StartResult()

        /** 远程后端（Shizuku/Root）不可用或无法获取，任务拒绝启动 */
        data class RemoteAccessUnavailable(val backend: RemoteBackend) : StartResult()

        /** 已有任务在启动/运行/停止，拒绝重入 */
        data object AlreadyRunning : StartResult()
    }

    sealed class StopResult {
        data object Success : StopResult()
        data object Failed : StopResult()
    }


    init {
        scope.launch {
            unifiedStateDispatcher.serviceDiedEvent.collect {
                coreBinding.getAndSet(null)?.session?.invalidate()
                stopBackgroundMonitors()
                setRunState(MaaExecutionState.ERROR)
                sessionLogger.completeSessionAndWait(
                    "SERVICE_DIED",
                    context.getString(R.string.runlog_service_terminated),
                    LogLevel.ERROR
                )
                notificationCenter.notifyServiceDied()
            }
        }

        scope.launch {
            appWatchdog.appDiedEvent.collect { packageName ->
                Timber.w("App watchdog detected app died: %s", packageName)
                sessionLogger.appendAndWait(
                    context.getString(R.string.runlog_game_process_gone, packageName),
                    LogLevel.WARNING
                )
            }
        }

        scope.launch {
            appWatchdog.displayDriftEvent.collect { packageName ->
                Timber.w("App watchdog detected display drift: %s", packageName)
                sessionLogger.appendAndWait(
                    context.getString(R.string.runlog_game_left_virtual_display, packageName),
                    LogLevel.WARNING
                )
            }
        }
    }

    suspend fun start(
        tasks: List<MaaTaskParams>,
        clientType: String,
        preflightLogs: List<Pair<UiText, LogLevel>> = emptyList(),
        onSessionStarted: (suspend () -> Unit)? = null
    ): StartResult = executeStart(
        tasks = tasks,
        clientType = clientType,
        startMessage = context.getString(R.string.runlog_task_start, tasks.size),
        successMessage = context.getString(R.string.runlog_task_started),
        preflightLogs = preflightLogs,
        onSessionStarted = onSessionStarted,
    )

    suspend fun startCopilot(
        tasks: List<MaaTaskParams>,
        clientType: String = taskChainState.clientType
    ): StartResult = executeStart(
        tasks = tasks,
        clientType = clientType,
        startMessage = context.getString(R.string.runlog_copilot_start),
        successMessage = context.getString(R.string.runlog_copilot_started),
    )

    private suspend fun failStart(
        message: String, sessionStatus: String, result: StartResult
    ): StartResult {
        if (!settleFailedStartup()) return retainFailedStartup(message, result)
        setRunState(MaaExecutionState.ERROR)
        sessionLogger.appendAndWait(message, LogLevel.ERROR)
        sessionLogger.endSessionAndWait(sessionStatus)
        notificationCenter.notifyStartFailed(message)
        return result
    }

    /** 服务尚未就绪，任务拒绝启动但不进入 ERROR 状态（服务本身没有故障） */
    private suspend fun rejectStart(
        message: String, sessionStatus: String, result: StartResult
    ): StartResult {
        if (!settleFailedStartup()) return retainFailedStartup(message, result)
        setRunState(MaaExecutionState.IDLE)
        sessionLogger.appendAndWait(message, LogLevel.WARNING)
        sessionLogger.endSessionAndWait(sessionStatus)
        // 前置失败未进 STARTING；先 beginRun 刷新 token，否则 notifyStartFailed 会因旧 run 已 claim 结果而被吞
        liveCoordinator.beginRun()
        notificationCenter.notifyStartFailed(message)
        return result
    }

    /** 先确认 native 已停止，再发布终态；否则前台服务与日志会先于核心退出。 */
    private suspend fun settleFailedStartup(): Boolean {
        startupCleanupResult?.let { return it }
        return withContext(NonCancellable + Dispatchers.IO) {
            val binding = startupBinding
            val stopped = if (binding == null || coreBinding.get() !== binding) true else {
                runCatching { binding.session.stop() }
                    .onFailure { Timber.e(it, "Failed to clean up MaaCore startup") }
                    .getOrDefault(false)
            } || coreBinding.get() !== binding
            // 写在不可取消的块内，切回取消的调用者时仍保留结果，避免再次等待超时。
            startupCleanupResult = stopped
            stopped
        }
    }

    private suspend fun retainFailedStartup(message: String, result: StartResult): StartResult {
        if (coreBinding.get() !== startupBinding) return result // 服务死亡已有独立收尾。
        setRunState(MaaExecutionState.RUNNING)
        sessionLogger.appendAndWait(message, LogLevel.ERROR)
        sessionLogger.appendAndWait(context.getString(R.string.runlog_task_stop_failed), LogLevel.ERROR)
        // 不结束会话、不发终态通知；前台服务和设备准入一直保留到用户重试停止。
        return result
    }

    /**
     * 启动前对齐资源档，必须早于 mute——换进程会让旧进程收尾时解除游戏静音
     * 持 [startMutex] 挡住并发启动，加锁顺序与 [executeStart] 一致
     * 失败交给 [checkPreconditions] 统一报错
     */
    suspend fun prepareResources(clientType: String) = startMutex.withLock {
        // 换进程会杀掉虚拟显示器
        when (_state.value) {
            MaaExecutionState.IDLE, MaaExecutionState.ERROR -> Unit
            else -> {
                Timber.i("Skip resource prepare while busy: %s", _state.value)
                return@withLock
            }
        }
        // 可能在主线程调用，解绑是同步 binder 调用
        withContext(Dispatchers.IO) {
            resourceLoader.ensureLoaded(clientType)
        }
        Unit
    }

    private suspend fun checkPreconditions(mode: RunMode, clientType: String): StartResult? {
        // 服务连接中时直接拒绝，避免与后台自动 load() 并发触发 LoadResource
        // 换进程重连是资源加载自己发起的，不算，交给 ensureLoaded 等它结束
        val serviceState = RemoteServiceManager.state.value
        val resourceState = resourceLoader.state.value
        val reconnectingForResource = resourceState is MaaResourceLoader.State.Loading ||
                resourceState is MaaResourceLoader.State.Reloading
        if (serviceState is RemoteServiceManager.ServiceState.Connecting && !reconnectingForResource) {
            return rejectStart(
                context.getString(R.string.runlog_service_connecting),
                "SERVICE_CONNECTING",
                StartResult.ServiceConnecting
            )
        }

        val access = RemoteAccessCoordinator.refresh()
        val backend = access.configuredBackend
        if (!access.isAvailable(backend)) {
            return rejectStart(
                context.getString(R.string.runlog_backend_unavailable, backend.display),
                "BACKEND_UNAVAILABLE",
                StartResult.RemoteAccessUnavailable(backend)
            )
        }

        activityManager.runIfDirty { resourceLoader.load(clientType) }
        val loaded = resourceLoader.ensureLoaded(clientType)
        if (loaded.isFailure) {
            return rejectStart(
                context.getString(R.string.runlog_resource_load_failed), "RESOURCE_ERROR",
                StartResult.ResourceError(loaded.exceptionOrNull())
            )
        }
        // 前台（含定时 / LAUNCH_PROFILE）必须横屏且 16:9；后台走虚拟屏自带 16:9
        if (mode == RunMode.FOREGROUND) {
            val (width, height) = Misc.getScreenSize(context)
            if (height > width) {
                return rejectStart(
                    context.getString(R.string.runlog_portrait_orientation), "PORTRAIT",
                    StartResult.PortraitOrientationError
                )
            }
            if (!Misc.isAspectRatio16x9(width, height)) {
                return rejectStart(
                    context.getString(R.string.runlog_invalid_aspect_ratio, width, height),
                    "INVALID_ASPECT_RATIO",
                    StartResult.InvalidAspectRatioError
                )
            }
        }
        return null
    }

    private suspend fun ensureMaaInstance(session: MaaCoreSession): StartResult? =
        when (session.initialize()) {
            MaaCoreSession.Initialization.READY -> null
            MaaCoreSession.Initialization.BUSY -> {
                setRunState(MaaExecutionState.RUNNING)
                StartResult.AlreadyRunning
            }
            MaaCoreSession.Initialization.CREATE_FAILED -> failStart(
                context.getString(R.string.runlog_create_instance_failed), "CREATE_INSTANCE_ERROR",
                StartResult.InitializationError(StartResult.InitializationError.InitPhase.CREATE_INSTANCE)
            )
            MaaCoreSession.Initialization.TOUCH_MODE_FAILED -> failStart(
                context.getString(R.string.runlog_set_touch_mode_failed), "SET_TOUCH_MODE_ERROR",
                StartResult.InitializationError(StartResult.InitializationError.InitPhase.SET_TOUCH_MODE)
            )
        }

    private suspend fun setupDisplayAndConnect(
        service: RemoteService, session: MaaCoreSession, mode: RunMode, clientType: String
    ): StartResult? {
        if (!service.setVirtualDisplayMode(mode.displayMode))
            return failStart(
                context.getString(R.string.runlog_display_mode_failed), "DISPLAY_MODE_ERROR",
                StartResult.ConnectionError(StartResult.ConnectionError.ConnectPhase.DISPLAY_MODE)
            )
        val config = when (mode) {
            RunMode.FOREGROUND -> {
                val displayId = service.startVirtualDisplay()
                if (displayId == -1) return failVirtualDisplayStart()
                val (w, h) = Misc.getScreenSize(context)
                buildConnectConfig(w, h, displayId)
            }

            RunMode.BACKGROUND -> {
                val r = resolveAndSetResolution(service, clientType)
                val displayId = service.startVirtualDisplay()
                if (displayId == -1) return failVirtualDisplayStart()
                buildConnectConfig(r.width, r.height, displayId)
            }
        }
        // 在 MAA 连接（含 force_stop 重启游戏）之前提前授予电池优化豁免与后台不受限权限，
        // 让新进程一启动就处于受保护状态
        grantGameBatteryExemption(clientType)
        return if (session.connect(config, appSettings.deployWithPause.value)) null else failStart(
            context.getString(R.string.runlog_maa_connect_failed), "MAA_CONNECT_ERROR",
            StartResult.ConnectionError(StartResult.ConnectionError.ConnectPhase.MAA_CONNECT)
        )
    }

    /** 虚拟显示启动失败；若是 Root 授权的 Shizuku（uid 0）则附加改用内置 Root 模式的提示 */
    private suspend fun failVirtualDisplayStart(): StartResult {
        val shizukuAsRoot =
            RemoteServiceManager.connectedBackendOrNull() == RemoteBackend.SHIZUKU &&
                    ShizukuManager.isRunningAsRoot()
        val message = if (shizukuAsRoot) {
            context.getString(R.string.runlog_virtual_display_failed_shizuku_as_root)
        } else {
            context.getString(R.string.runlog_virtual_display_failed)
        }
        return failStart(
            message,
            "VIRTUAL_DISPLAY_ERROR",
            StartResult.ConnectionError(
                StartResult.ConnectionError.ConnectPhase.VIRTUAL_DISPLAY,
                shizukuAsRoot = shizukuAsRoot,
            )
        )
    }

    private fun grantGameBatteryExemption(clientType: String) {
        val pkg = Packages[clientType] ?: return
        runCatching {
            RemoteServiceManager.getInstanceOrNull()?.grantPermissions(
                PermissionGrantRequest(
                    packageName = pkg,
                    permissions = PermissionGrantRequest.PERM_BATTERY or PermissionGrantRequest.PERM_BACKGROUND
                )
            )
            Timber.d("Battery exemption granted for game: %s", pkg)
        }.onFailure { e ->
            Timber.w(e, "Failed to grant battery exemption for game")
        }
    }

    private suspend fun appendTasksAndStart(
        session: MaaCoreSession,
        tasks: List<MaaTaskParams>,
        successMessage: String,
        mode: RunMode,
    ): StartResult {
        // 独立目录：先投递用户文件，送不过去 core 那边就是 file-not-found，直接报资源错误
        if (!coreDataPusher.pushUserData()) {
            Timber.e("core user data push failed before start")
            return failStart(
                context.getString(R.string.runlog_resource_load_failed), "RESOURCE_ERROR",
                StartResult.ResourceError(IllegalStateException("core user data push failed"))
            )
        }
        taskChainStatusTracker.clear()
        // 不清 dropsRefresher：stage 已在 Analyze 完成，会话结束/下次 Analyze 再清
        tasks.forEach { t ->
            sessionLogger.appendToFileOnly("[TaskParams] ${t.type.value}: ${t.params}")
        }
        val started = session.startTasks(tasks.map { MaaCoreSession.Task(it.type.value, it.params) }) { index, taskId ->
            val task = tasks[index]
            taskChainStatusTracker.register(taskId, task.type.value, task.slot)
            task.slot?.let { dropsRefresher.bind(it, taskId) }
        }
        if (started != MaaCoreSession.StartResult.Started) {
            if (started == MaaCoreSession.StartResult.Busy) {
                setRunState(MaaExecutionState.RUNNING)
                return StartResult.AlreadyRunning
            }
            if (started is MaaCoreSession.StartResult.RejectedTask) {
                Timber.e("MaaCore rejected task #%d (%s)", started.index + 1, tasks[started.index].type.value)
            }
            taskChainStatusTracker.clear()
            return failStart(
                context.getString(R.string.runlog_maa_start_failed),
                "START_ERROR",
                StartResult.StartError
            )
        }
        setRunState(MaaExecutionState.RUNNING)
        if (mode == RunMode.BACKGROUND) {
            startBackgroundMonitors()
        }
        sessionLogger.appendAndWait(successMessage, LogLevel.SUCCESS)
        return StartResult.Success(session.version)
    }

    /** 启动互斥：前置检查耗时，双入口/双击可能同时穿过 AlreadyRunning 窗口 */
    private val startMutex = Mutex()

    private suspend fun executeStart(
        tasks: List<MaaTaskParams>,
        clientType: String,
        startMessage: String,
        successMessage: String,
        preflightLogs: List<Pair<UiText, LogLevel>> = emptyList(),
        onSessionStarted: (suspend () -> Unit)? = null,
    ): StartResult = startMutex.withLock {
        // 位于资源装载/进程重启之前，定时、作业和手动入口均必须取得同一准入。
        if (isTaskActive) return@withLock StartResult.AlreadyRunning
        val reservation = EngineExecutionCoordinator.shared.tryStart(EngineIds.ARKNIGHTS)
            ?: return@withLock StartResult.AlreadyRunning
        startupInProgress.set(true)
        startupBinding = null
        startupCleanupResult = null
        executionLease.set(reservation)
        var result: StartResult? = null
        try {
            com.aliothmoon.maadroid.engine.EngineSession.closeRetainedPreview()
            executeStartLocked(
                tasks, clientType, startMessage, successMessage, preflightLogs, onSessionStarted,
            ).also { result = it }
        } finally {
            try {
                if (result !is StartResult.Success && result !is StartResult.AlreadyRunning) {
                    // 启动取消/异常也可能发生在 native 已接受任务之后。确认停止后再归还设备。
                    withContext(NonCancellable + Dispatchers.IO) {
                        val binding = startupBinding
                        val stopped = settleFailedStartup()
                        if (!stopped && binding != null && coreBinding.get() === binding) {
                            setRunState(MaaExecutionState.RUNNING)
                        } else if (isTaskActive) {
                            stopBackgroundMonitors()
                            setRunState(MaaExecutionState.ERROR)
                        }
                        if (stopped && result == null) sessionLogger.endSessionAndWait("START_ABORTED")
                    }
                }
            } finally {
                // 切回已取消的调用者上下文也可能抛异常，准入收尾必须仍然执行。
                startupInProgress.set(false)
                if (!isTaskActive) {
                    if (executionLease.compareAndSet(reservation, null)) reservation.close()
                }
            }
        }
    }

    private suspend fun executeStartLocked(
        tasks: List<MaaTaskParams>,
        clientType: String,
        startMessage: String,
        successMessage: String,
        preflightLogs: List<Pair<UiText, LogLevel>> = emptyList(),
        onSessionStarted: (suspend () -> Unit)? = null,
    ): StartResult {
        // 会话与日志先开；STARTING/FGS 必须在前置检查通过后再进入。
        // 否则竖屏等快速失败会 stop 尚未 startForeground 的 FGS，触发
        // ForegroundServiceDidNotStartInTimeException。
        when (_state.value) {
            MaaExecutionState.STARTING,
            MaaExecutionState.RUNNING,
            MaaExecutionState.STOPPING -> return StartResult.AlreadyRunning

            MaaExecutionState.IDLE,
            MaaExecutionState.ERROR -> Unit
        }
        val mode = appSettings.runMode.value
        sessionLogger.startSession(tasks.map { it.type.value })
        subTaskHandler.resetSessionState()
        toolboxResultCollector.onSessionStart()
        onSessionStarted?.invoke()
        sessionLogger.appendAndWait(startMessage, LogLevel.INFO)
        preflightLogs.forEach { (text, level) ->
            sessionLogger.appendAndWait(text.resolve(context), level)
        }
        sessionLogger.appendAndWait(fetchDeviceMemoryInfo(), LogLevel.INFO)

        return withContext(Dispatchers.IO) {
            checkPreconditions(mode, clientType)?.let { return@withContext it }

            setRunState(MaaExecutionState.STARTING)

            try {
                useRemoteService { service ->
                    val session = sessionFor(service.maaCoreService)
                    startupBinding = coreBinding.get()
                    ensureMaaInstance(session)?.let { return@useRemoteService it }

                    setupDisplayAndConnect(
                        service,
                        session,
                        mode,
                        clientType
                    )?.let { return@useRemoteService it }
                    val result = appendTasksAndStart(session, tasks, successMessage, mode)
                    if (result is StartResult.Success) {
                        taskChainState.saveLastUsedClientType(clientType)
                    }
                    result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to acquire remote service during start")
                rejectStart(
                    context.getString(R.string.runlog_remote_connect_failed, e.message ?: ""),
                    "REMOTE_ACCESS_UNAVAILABLE",
                    StartResult.RemoteAccessUnavailable(RemoteAccessCoordinator.configuredBackend())
                )
            }
        }
    }

    private fun resolveAndSetResolution(
        service: RemoteService,
        clientType: String
    ): DefaultDisplayConfig.Resolution {
        val preference = appSettings.backgroundResolution.value
        val r = DefaultDisplayConfig.resolveResolution(clientType, preference)
        service.setVirtualDisplayResolution(r.width, r.height, r.dpi)
        Timber.i(
            "Virtual display resolution: %dx%d@%d for client=%s, pref=%s",
            r.width,
            r.height,
            r.dpi,
            clientType,
            preference
        )
        _displayResolution.value = r
        return r
    }

    private fun buildConnectConfig(width: Int, height: Int, displayId: Int): String {
        return buildJsonObject {
            put("library_path", "libbridge.so")
            put("screen_resolution", buildJsonObject {
                put("width", width)
                put("height", height)
            })
            put("display_id", displayId)
            put("force_stop", true)
        }.toString()
    }


    private fun fetchDeviceMemoryInfo(): String {
        return try {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
                ?: return context.getString(R.string.task_start_device_memory_unavailable)
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val mb = 1024L * 1024
            val availMb = mi.availMem / mb
            val totalMb = mi.totalMem / mb
            val usedPercent = if (totalMb > 0) (totalMb - availMb) * 100 / totalMb else 0
            val base = context.getString(
                R.string.task_start_device_memory, availMb, totalMb, usedPercent
            )
            if (mi.lowMemory) base + context.getString(R.string.task_start_device_memory_low) else base
        } catch (e: Exception) {
            Timber.w(e, "读取设备内存信息失败")
            context.getString(R.string.task_start_device_memory_unavailable)
        }
    }

    suspend fun stop(origin: StopOrigin = StopOrigin.USER): StopResult = startMutex.withLock {
        if (!isTaskActive) return@withLock StopResult.Success
        // 先记来源再切状态，TaskEndRegistry 在 STOPPING→IDLE 边沿读取
        lastStopOrigin = origin
        setRunState(MaaExecutionState.STOPPING)

        // 停止已经发出后必须确认结局；页面离开不能中断收尾并留下 STOPPING。
        withContext(NonCancellable + Dispatchers.IO) {
            sessionLogger.appendAndWait(context.getString(R.string.runlog_task_stopping), LogLevel.INFO)
            val stopped = runCatching {
                coreBinding.get()?.session?.stop() ?: true
            }.onFailure {
                Timber.e(it, "Failed to stop MaaCore")
            }.getOrDefault(false)
            if (_state.value == MaaExecutionState.ERROR) {
                // 服务死亡已有独立错误通知，不能再覆盖成“任务已停止”。
                StopResult.Failed
            } else {
                finishStop(if (stopped) StopResult.Success else StopResult.Failed)
            }
        }
    }

    // 后台模式随会话启停的监视器：游戏存活/漂移看门狗、帧率
    private fun startBackgroundMonitors() {
        appWatchdog.startWatching()
        gameFpsWatcher.start()
    }

    private fun stopBackgroundMonitors() {
        appWatchdog.stopWatching()
        gameFpsWatcher.stop()
    }

    private fun finishStop(result: StopResult): StopResult {
        if (result == StopResult.Failed) {
            // 核心仍可能注入输入；保留占用、日志和监视器，恢复停止按钮以允许重试。
            setRunState(MaaExecutionState.RUNNING)
            sessionLogger.append(context.getString(R.string.runlog_task_stop_failed), LogLevel.ERROR)
            return result
        }
        stopBackgroundMonitors()
        setRunState(MaaExecutionState.IDLE)
        val status = "STOPPED"
        sessionLogger.append(
            context.getString(R.string.runlog_task_stopped, status),
            LogLevel.INFO
        )
        sessionLogger.endSession(status)
        notificationCenter.notifyTaskStopped()
        return result
    }

    suspend fun stopVirtualDisplay() {
        try {
            stopBackgroundMonitors()
            _displayResolution.value = defaultResolution
            withContext(Dispatchers.IO) {
                val service = RemoteServiceManager.getInstanceOrNull()
                    ?: return@withContext
                service.stopVirtualDisplay()
            }
        } finally {
            withContext(NonCancellable) {
                if (!gameMuteCoordinator.unmute()) {
                    Timber.w("Virtual display close did not restore managed game audio; retry pending")
                }
            }
        }
    }
}
