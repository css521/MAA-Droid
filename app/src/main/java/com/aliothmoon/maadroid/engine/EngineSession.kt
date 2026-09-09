package com.aliothmoon.maadroid.engine

import android.content.Context
import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.domain.models.RunMode
import com.aliothmoon.maadroid.engine.resource.EngineResourceService
import com.aliothmoon.maadroid.engine.resource.ResourcePackLocks
import com.aliothmoon.maadroid.remote.EngineDataRoot
import kotlinx.coroutines.*
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

    suspend fun prepare(): String? = withContext(Dispatchers.IO) {
        val profile = EngineRegistry.provider(engineId)?.profile ?: return@withContext "引擎 $engineId 未注册"
        try {
            for (pack in profile.resourcePacks.sortedBy { it.packId }) {
                if (pack.upstreamArchive != null) resources.ensureInstalled(pack).getOrThrow()
                resourceLeases += ResourcePackLocks.acquire(pack.packId)
                val dir = EngineDataRoot.forPack(context, pack)
                check(dir.isDirectory) { "资源尚未安装：${pack.packId}" }
                val manifest = File(dir, "manifest.json").takeIf { it.isFile }?.readText()
                pack.checkCompatibility(manifest)?.let { error(it) }
                pack.verifyInstalledFiles(dir)?.let { error(it) }
            }
            val mainPack = profile.resourcePacks.firstOrNull() ?: error("引擎未声明资源包")
            val activeEngine = engine ?: error("无法创建引擎 $engineId")
            activeEngine.prepare(EngineDataRoot.forPack(context, mainPack)).getOrThrow()
            var connected = false
            serviceProvider { service ->
                val device = EngineDeviceSession.open(profile, service, runMode)
                deviceSession = device
                device.connect(activeEngine).getOrThrow()
                connected = true
            }
            check(connected) { "远程服务未连接，请先在首页启用 Shizuku 或 Root" }
            null
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (error: Exception) {
            close()
            error.message ?: "准备引擎失败"
        }
    }

    fun events() = engine?.events
    fun appendTask(type: String, paramsJson: String) = engine?.appendTask(type, paramsJson) ?: AutomationEngine.INVALID_TASK_ID
    suspend fun start(): Boolean = withContext(Dispatchers.IO) { engine?.start() ?: false }
    suspend fun stop(): Boolean = withContext(Dispatchers.IO) { engine?.stop() ?: true }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        try {
            engine?.stop()
        } finally {
            try { engine?.release() } finally {
                try { deviceSession?.close() } finally {
                    deviceSession = null
                    resourceLeases.asReversed().forEach { it.close() }
                    resourceLeases.clear()
                }
            }
        }
    }
}
