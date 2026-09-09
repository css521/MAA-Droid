package com.aliothmoon.maadroid.engine

import android.content.Context
import android.view.Surface
import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.diagnostics.AppDiagnostics
import com.aliothmoon.maadroid.domain.models.RunMode
import com.aliothmoon.maadroid.engine.resource.EngineResourceService
import com.aliothmoon.maadroid.engine.resource.ResourcePackLocks
import com.aliothmoon.maadroid.remote.EngineDataRoot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File

/** 下载并验证资源 → 占用资源版本 → 创建设备会话 → 运行；所有终态先停引擎再释放。 */
class EngineSession(
    private val context: Context,
    private val engineId: String,
    private val resources: EngineResourceService,
    private val runMode: RunMode,
    private val serviceProvider: suspend (suspend (RemoteService) -> Unit) -> Unit,
) {
    private var deviceSession: EngineDeviceSession? = null
    private val resourceLeases = mutableListOf<Closeable>()
    private val engine get() = EngineRegistry.engine(engineId)
    private var executionLease: EngineExecutionCoordinator.Lease? = null
    private var ownedEngine: AutomationEngine? = null
    private val closeMutex = Mutex()
    private val previewLock = Any()
    private var previewSurface: Surface? = null
    private var acceptsManualInput = false
    private val _previewReady = MutableStateFlow(false)
    val previewReady = _previewReady.asStateFlow()
    val isRunning: Boolean get() = ownedEngine?.isRunning == true

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

    suspend fun prepare(): String? = withContext(Dispatchers.IO) {
        val profile = EngineRegistry.provider(engineId)?.profile ?: return@withContext "引擎 $engineId 未注册"
        check(executionLease == null) { "会话已在准备或运行" }
        executionLease = EngineExecutionCoordinator.shared.tryStart(engineId)
            ?: return@withContext "其它任务正在运行或准备资源，请先停止该任务"
        trace("prepare.begin", "mode=$runMode")
        try {
            for (pack in profile.resourcePacks.sortedBy { it.packId }) {
                trace("resources.ensure", pack.packId)
                if (pack.upstreamArchive != null) resources.ensureInstalled(pack).getOrThrow()
                resourceLeases += ResourcePackLocks.acquire(pack.packId)
                val dir = EngineDataRoot.forPack(context, pack)
                check(dir.isDirectory) { "资源尚未安装：${pack.packId}" }
                val manifest = File(dir, "manifest.json").takeIf { it.isFile }?.readText()
                pack.checkCompatibility(manifest)?.let { error(it) }
                pack.verifyInstalledFiles(dir)?.let { error(it) }
                trace("resources.verified", "${pack.packId} version=${pack.readInstalledVersion(dir)}")
            }
            val mainPack = profile.resourcePacks.firstOrNull() ?: error("引擎未声明资源包")
            val activeEngine = engine ?: error("无法创建引擎 $engineId")
            ownedEngine = activeEngine
            activeEngine.setDiagnosticSink { phase, detail -> trace(phase, detail) }
            trace("engine.prepare")
            activeEngine.prepare(EngineDataRoot.forPack(context, mainPack)).getOrThrow()
            var connected = false
            trace("remote.acquire")
            serviceProvider { service ->
                trace("device.open")
                val device = EngineDeviceSession.open(profile, service, runMode)
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

    fun events() = engine?.events
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

    suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        closeMutex.withLock {
            synchronized(previewLock) {
                acceptsManualInput = false
                deviceSession?.releaseManualInput()
            }
            try {
                check(ownedEngine?.stop() != false) { "引擎尚未停止，保留设备会话以便重试停止" }
            } catch (failure: Throwable) {
                if (!failure.isRecoverableEngineFailure() || isRunning) throw failure
                AppDiagnostics.failure(engineId, "cleanup.stop", failure)
            }
            var cleanupError: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (!failure.isRecoverableEngineFailure()) throw failure
                    AppDiagnostics.failure(engineId, "cleanup.release", failure)
                    if (cleanupError == null) cleanupError = failure else cleanupError!!.addSuppressed(failure)
                }
            }
            try {
                cleanup { ownedEngine?.release() }
                cleanup { ownedEngine?.setDiagnosticSink(null) }
                ownedEngine = null
                synchronized(previewLock) {
                    _previewReady.value = false
                    previewSurface = null
                    cleanup { deviceSession?.close() }
                    deviceSession = null
                }
                resourceLeases.asReversed().forEach { lease -> cleanup { lease.close() } }
            } finally {
                resourceLeases.clear()
                executionLease?.close()
                executionLease = null
            }
            trace("session.closed")
            cleanupError?.let { throw it }
            Unit
        }
    }
}
