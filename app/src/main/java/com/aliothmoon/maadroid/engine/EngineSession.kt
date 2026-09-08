package com.aliothmoon.maadroid.engine

import android.content.Context
import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.engine.AutomationEngine
import com.aliothmoon.maadroid.engine.EngineEvent
import com.aliothmoon.maadroid.engine.GameProfile
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.remote.EngineDataRoot
import com.aliothmoon.maadroid.remote.RemoteDeviceHandle
import java.io.File

/**
 * 把一个引擎从「注册了」推到「真的在跑」。
 *
 * 在此之前 `EngineRegistry.engine(id)`、`RemoteDeviceHandle`、
 * `ResourcePackSpec.checkCompatibility` 三者**都没有任何生产调用方** —— 引擎装配好了，
 * 设备句柄写好了，兼容门闸也写好了，但没有一处代码把它们串起来。所以边狱引擎此前
 * 无法运行，兼容门闸也等于不存在（只有测试在调它）。本类就是那条缺失的链。
 *
 * 顺序不是随意的，每一步都有前一步的前提：
 *
 * 1. **门闸** —— 必须在 `prepare` 之前。门闸的全部意义就是「装载前拒绝」：上游若引入了
 *    本 App 未实现的动作，此时报「请升级 App」；放到装载后就变成跑到一半崩在某个节点上。
 * 2. **prepare** —— 装流水线与素材，并校验模板引用完整
 * 3. **强制显示规格** —— 全部模板按 `GameProfile.display` 截取，分辨率不对会全部失配
 * 4. **connect** —— 打开帧通道
 * 5. **appendTask / start**
 *
 * 只服务「跑在 App 进程」的引擎。方舟的 MaaCore 在提权进程内直接访问帧缓冲，
 * 走的是另一条路（`MaaCoreServiceAccess`），不经这里。
 */
class EngineSession(
    private val context: Context,
    private val engineId: String,
    private val serviceProvider: suspend (suspend (RemoteService) -> Unit) -> Unit,
) {

    /** 引擎与设备句柄都按会话持有：句柄含共享内存映射，跨会话复用会带着上次的映射 */
    private var handle: RemoteDeviceHandle? = null

    /**
     * 准备到「可以下发任务」为止。
     *
     * @return 失败原因；成功返回 null。失败原因是**面向用户**的文案（门闸会给出
     *   「请升级 App」这类可行动的提示），不是异常栈。
     */
    suspend fun prepare(): String? {
        val provider = EngineRegistry.provider(engineId)
            ?: return "引擎 $engineId 未注册"
        val profile = provider.profile

        // ---- 1. 兼容门闸：必须先于装载 ----
        val gateFailure = checkPacks(profile)
        if (gateFailure != null) return gateFailure

        val engine = EngineRegistry.engine(engineId) ?: return "引擎 $engineId 无法创建"

        // ---- 2. 装载资源 ----
        val mainPack = profile.resourcePacks.firstOrNull()
            ?: return "引擎 $engineId 未声明资源包"
        val resourceDir = EngineDataRoot.forPack(context, mainPack)
        if (!resourceDir.isDirectory) {
            return "资源尚未下载（${resourceDir.name}），请先在资源中心获取"
        }
        engine.prepare(resourceDir).exceptionOrNull()?.let {
            return "装载资源失败：${it.message}"
        }

        // ---- 3~4. 强制显示规格并连接设备 ----
        var connectFailure: String? = null
        serviceProvider { service ->
            val h = RemoteDeviceHandle(
                service = service,
                width = profile.display.width,
                height = profile.display.height,
            )
            handle = h
            engine.connect(h).exceptionOrNull()?.let {
                connectFailure = "连接设备失败：${it.message}"
            }
        }
        return connectFailure
    }

    /**
     * 逐个资源包过门闸。
     *
     * 读清单文件本身而不是只看目录存在：门闸校验的是清单里的 `required_actions` 与
     * `min_engine_version`，清单缺失或解析失败同样应当拒绝 —— 那意味着包不完整。
     */
    private fun checkPacks(profile: GameProfile): String? {
        for (pack in profile.resourcePacks) {
            // 未内置且尚未下载的包，交由上层引导下载，不在此处报「不兼容」
            val dir = EngineDataRoot.forPack(context, pack)
            if (!dir.isDirectory) continue
            val manifest = manifestOf(dir)
            pack.checkCompatibility(manifest)?.let { return it }
        }
        return null
    }

    /**
     * 读包内清单。文件名由各包自己约定，这里按约定名取 —— 取不到就传 null，
     * 让 [ResourcePackSpec.checkCompatibility] 自己决定是否算不兼容
     * （方舟没有清单，其实现直接放行；边狱缺清单则拒绝）。
     */
    private fun manifestOf(dir: File): String? =
        File(dir, MANIFEST_NAME).takeIf { it.isFile }?.let {
            runCatching { it.readText() }.getOrNull()
        }

    fun events(): kotlinx.coroutines.flow.SharedFlow<EngineEvent>? =
        EngineRegistry.engine(engineId)?.events

    fun appendTask(type: String, paramsJson: String): Int =
        EngineRegistry.engine(engineId)?.appendTask(type, paramsJson)
            ?: AutomationEngine.INVALID_TASK_ID

    suspend fun start(): Boolean = EngineRegistry.engine(engineId)?.start() ?: false

    suspend fun stop(): Boolean = EngineRegistry.engine(engineId)?.stop() ?: false

    /** 释放帧通道与原生资源。会话结束必须调，否则共享内存映射会一直留着 */
    fun close() {
        handle?.close()
        handle = null
    }

    private companion object {
        /** 与 `LimbusResourcePack.MANIFEST_NAME` 一致；方舟无清单 */
        const val MANIFEST_NAME = "manifest.json"
    }
}
