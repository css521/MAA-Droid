package com.maadroid.app.engine

import android.content.Context
import android.view.Surface
import com.maadroid.app.RemoteService
import com.maadroid.app.diagnostics.AppDiagnostics
import com.maadroid.app.domain.models.RunMode
import com.maadroid.app.engine.resource.EngineResourceService
import com.maadroid.app.engine.resource.ResourcePackLocks
import com.maadroid.app.remote.EngineDataRoot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** 任务终态释放引擎和资源；已停止任务的设备可以继续用于预览和手动游戏。 */
class EngineSession(
    private val context: Context,
    private val engineId: String,
    private val resources: EngineResourceService,
    private val runMode: RunMode,
    private val serviceProvider: suspend (suspend (RemoteService) -> Unit) -> Unit,
    /**
     * 「设置 → 调试模式」的当前值。取函数而非快照：会话可能跨越用户改设置的时刻。
     *
     * 引擎据此决定是否采集画面帧、输出识别耗时等排查用输出。用设置而不是
     * BuildConfig.DEBUG，这样正式包遇到问题也能开诊断，不必分发两种包。
     */
    private val debugMode: () -> Boolean = { false },
) {
    private var deviceSession: EngineDeviceSession? = null
    private val resourceLeases = mutableListOf<Closeable>()
    private var executionLease: EngineExecutionCoordinator.Lease? = null
    @Volatile private var ownedEngine: AutomationEngine? = null
    private val engineLock = Any()
    private var closing = false
    private var preparationStarted = false
    private val closeMutex = Mutex()
    private val previewLock = Any()
    private var previewSurface: Surface? = null
    private var acceptsManualInput = false
    private val _previewReady = MutableStateFlow(false)
    val previewReady = _previewReady.asStateFlow()
    val isRunning: Boolean get() = ownedEngine?.isRunning == true
    val gamePackageName: String? get() = synchronized(previewLock) { deviceSession?.packageName }

    fun readGameFps(): Float? = synchronized(previewLock) { deviceSession?.readGameFps() }

    fun openManualInput(): EngineDeviceSession.ManualInput? = synchronized(previewLock) {
        if (acceptsManualInput && _previewReady.value) deviceSession?.openManualInput() else null
    }

    /** A tab owns only this surface, not the display or the automation lifetime. */
    fun setPreviewSurface(surface: Surface?) = synchronized(previewLock) {
        previewSurface = surface
        deviceSession?.setPreviewSurface(surface)
        Unit
    }

    private fun trace(phase: String, detail: String = "") = AppDiagnostics.record(engineId, phase, detail)

    /** The VM subscribes before prepare. Both entry points must use this one owned instance. */
    private fun getOrCreateEngine(): AutomationEngine? = synchronized(engineLock) {
        if (closing) return@synchronized null
        ownedEngine ?: EngineRegistry.createEngine(engineId)?.also { ownedEngine = it }
    }

    suspend fun prepare(): String? = withContext(Dispatchers.IO) {
        synchronized(engineLock) {
            check(!closing) { "会话已关闭，请创建新会话" }
            check(!preparationStarted) { "会话已在准备或运行" }
            preparationStarted = true
        }
        try {
            // Admission/resource failures also release an engine already obtained by events().
            val profile = EngineRegistry.provider(engineId)?.profile ?: error("引擎 $engineId 未注册")
            executionLease = EngineExecutionCoordinator.shared.tryStart(engineId)
                ?: error("其它任务正在运行或准备资源，请先停止该任务")
            // Preserve a compatible local engine's display through resource/model preparation.
            // Privileged delivery may restart the remote service and cannot retain its old lease.
            previewHandoffMutex.withLock {
                retainedPreview.get()?.let { previous ->
                    if (previous.engineId != engineId || previous.runMode != runMode ||
                        profile.resourcePacks.any { it.requiresPrivilegedDelivery }) previous.close()
                }
            }
            trace("prepare.begin", "mode=$runMode")
            val directories = linkedMapOf<String, File>()
            for (pack in profile.resourcePacks.sortedBy { it.packId }) {
                trace("resources.ensure", pack.packId)
                if (pack.upstreamArchive != null) resources.ensureInstalled(pack).getOrThrow()
                resourceLeases += ResourcePackLocks.acquire(pack.packId)
                val dir = EngineDataRoot.forPack(context, pack)
                check(dir.isDirectory) { "资源尚未安装：${pack.packId}" }
                val manifest = File(dir, "manifest.json").takeIf { it.isFile }?.readText()
                pack.checkCompatibility(manifest)?.let { error(it) }
                pack.verifyInstalledFiles(dir)?.let { error(it) }
                directories[pack.packId] = dir
                trace("resources.verified", "${pack.packId} version=${pack.readInstalledVersion(dir)}")
            }
            val activeEngine = getOrCreateEngine() ?: error("无法创建引擎 $engineId")
            activeEngine.setDiagnosticSink { phase, detail -> trace(phase, detail) }
            activeEngine.setDebugMode(debugMode())
            trace("engine.prepare")
            activeEngine.prepare(EngineResources(profile, directories)).getOrThrow()
            var connected = false
            trace("remote.acquire")
            serviceProvider { service ->
                trace("device.open")
                val device = acquireDevice(profile, service)
                synchronized(previewLock) {
                    deviceSession = device
                    try { device.setPreviewSurface(previewSurface) }
                    catch (failure: Throwable) {
                        if (!failure.isRecoverableEngineFailure()) throw failure
                        AppDiagnostics.failure(engineId, "preview.attach", failure)
                    }
                    _previewReady.value = true
                    acceptsManualInput = true
                }
                trace("device.ready", "display=${device.displayId} package=${device.packageName}")
                trace("engine.connect")
                device.connect(activeEngine).getOrThrow()
                connected = true
            }
            check(connected) { "远程服务未连接，请先在首页启用 Shizuku 或 Root" }
            trace("prepare.ready")
            null
        } catch (cancelled: CancellationException) {
            closeAfterFailure(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            if (!error.isRecoverableEngineFailure()) throw error
            AppDiagnostics.failure(engineId, "prepare.failed", error)
            closeAfterFailure(error)
            "${error.javaClass.simpleName}: ${error.message ?: "准备引擎失败"}"
        }
    }

    fun events() = getOrCreateEngine()?.events
    fun appendTask(type: String, paramsJson: String) = ownedEngine?.appendTask(type, paramsJson) ?: AutomationEngine.INVALID_TASK_ID
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        trace("engine.start")
        (ownedEngine?.start() ?: false).also { trace("engine.start.result", it.toString()) }
    }
    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        trace("engine.stop")
        ownedEngine?.stop() ?: true
    }

    private suspend fun closeAfterFailure(failure: Throwable) {
        try { close() } catch (cleanup: Throwable) {
            if (!cleanup.isRecoverableEngineFailure()) throw cleanup
            failure.addSuppressed(cleanup)
            AppDiagnostics.failure(engineId, "cleanup.failed", cleanup)
        }
    }

    /** Confirm stopped work and release task ownership, retaining the display for manual play. */
    suspend fun finishTask(): Boolean = endSession(keepPreview = true)

    /** Explicit disposal (or failed startup). An unconfirmed stop must remain retryable. */
    suspend fun close() {
        check(endSession(keepPreview = false)) { "引擎尚未停止，保留设备会话以便重试停止" }
    }

    /** Explicit quick action. Stop the exact lease's game only after automation is quiescent. */
    suspend fun closeGame() {
        check(endSession(keepPreview = false, stopGame = true)) { "引擎尚未停止，不能关闭游戏" }
    }

    private suspend fun acquireDevice(profile: GameProfile, service: RemoteService): EngineDeviceSession =
        previewHandoffMutex.withLock {
            val previous = retainedPreview.get()
            val reused = if (previous != null && previous.engineId == engineId && previous.runMode == runMode) {
                previous.closeMutex.withLock {
                    synchronized(previous.previewLock) {
                        val candidate = previous.deviceSession
                        val compatible = try {
                            candidate?.canReuse(profile, service, runMode) == true
                        } catch (failure: Throwable) {
                            if (!failure.isRecoverableEngineFailure()) throw failure
                            AppDiagnostics.failure(engineId, "device.reuse.check", failure)
                            false
                        }
                        if (!compatible) return@synchronized null
                        checkNotNull(candidate)
                        // Revoke the old view before transfer. A delayed disposal from that view
                        // must not detach the new preview or release its touches/display.
                        candidate.releaseManualInput()
                        candidate.setPreviewSurface(null)
                        previous.acceptsManualInput = false
                        previous.deviceSession = null
                        previous.previewSurface = null
                        previous._previewReady.value = false
                        retainedPreview.compareAndSet(previous, null)
                        synchronized(previewLock) { deviceSession = candidate }
                        candidate
                    }
                }
            } else null
            if (reused != null) {
                trace("device.reused", "display=${reused.displayId} package=${reused.packageName}")
                reused.resumeGame(service)
                reused
            } else {
                previous?.close()
                EngineDeviceSession.open(profile, service, runMode).also {
                    synchronized(previewLock) { deviceSession = it }
                }
            }
        }

    private suspend fun endSession(keepPreview: Boolean, stopGame: Boolean = false): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        closeMutex.withLock {
            // Serialize creation with closure: even events() racing close cannot leak a new engine.
            val activeEngine = synchronized(engineLock) {
                closing = true
                ownedEngine
            }
            if (!keepPreview) synchronized(previewLock) {
                acceptsManualInput = false
                deviceSession?.releaseManualInput()
            }
            val stopped = try {
                activeEngine?.stop() != false
            } catch (failure: Throwable) {
                // A missing native library before device acquisition cannot leave device work.
                // Once connected, even isRunning=false cannot rule out a pending async call.
                val safeToRelease = failure is LinkageError &&
                    synchronized(previewLock) { deviceSession == null } &&
                    runCatching { activeEngine?.isRunning == false }.getOrDefault(false)
                if (!safeToRelease) throw failure
                AppDiagnostics.failure(engineId, "cleanup.stop", failure)
                true
            }
            if (!stopped) return@withLock false
            if (stopGame) synchronized(previewLock) { deviceSession?.stopGame() }
            var cleanupError: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (!failure.isRecoverableEngineFailure()) throw failure
                    AppDiagnostics.failure(engineId, "cleanup.release", failure)
                    if (cleanupError == null) cleanupError = failure else cleanupError!!.addSuppressed(failure)
                }
            }
            try {
                cleanup { activeEngine?.release() }
                cleanup { activeEngine?.setDiagnosticSink(null) }
                synchronized(engineLock) { ownedEngine = null }
                synchronized(previewLock) {
                    if (keepPreview && deviceSession != null && _previewReady.value) {
                        acceptsManualInput = true
                        retainedPreview.set(this@EngineSession)
                    } else {
                        _previewReady.value = false
                        previewSurface = null
                        cleanup { deviceSession?.close() }
                        deviceSession = null
                        retainedPreview.compareAndSet(this@EngineSession, null)
                    }
                }
                resourceLeases.asReversed().forEach { lease -> cleanup { lease.close() } }
            } finally {
                resourceLeases.clear()
                executionLease?.close()
                executionLease = null
            }
            trace(if (keepPreview) "task.finished" else "session.closed")
            cleanupError?.let { throw it }
            true
        }
    }

    companion object {
        private val retainedPreview = AtomicReference<EngineSession?>()
        private val previewHandoffMutex = Mutex()

        /** Call after acquiring task admission and before resource/display work, including legacy hosts. */
        suspend fun closeRetainedPreview() = withContext(NonCancellable + Dispatchers.IO) {
            previewHandoffMutex.withLock {
                retainedPreview.get()?.close()
            }
        }
    }
}
