package com.maadroid.app.remote

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

/**
 * 提权进程侧的引擎注册表。
 *
 * `RemoteServiceImpl.getEngineService(engineId)` 只查这张表，因此 core-* 不必依赖任何
 * 具体引擎 —— 依赖方向始终是 app / engine-* → core-*，不会倒挂。
 *
 * 注册时机：提权进程由 `RootUserService` 反射实例化 `--class=` 指定的类，那个类由 `:app`
 * 提供（唯一依赖全部引擎的模块），在其 init 中调用 [register] 装上所有引擎工厂。
 * 不用 ServiceLoader：`META-INF/services` 在 R8 + app_process 下不可靠。
 */
object RemoteEngineRegistry {

    private val factories = ConcurrentHashMap<String, RemoteEngineFactory>()

    /** 惰性创建：引擎的 native 库只在真正被取用时加载，避免未启用的引擎拖慢启动 */
    private val instances = ConcurrentHashMap<String, IBinder>()

    fun register(factory: RemoteEngineFactory) {
        factories[factory.engineId] = factory
    }

    /** 未注册返回 null；跨 AIDL 传出后 App 侧会看到 null binder */
    fun get(engineId: String): IBinder? {
        factories[engineId] ?: return null
        return instances.getOrPut(engineId) { factories.getValue(engineId).create() }
    }

    fun registeredIds(): Set<String> = factories.keys.toSet()

    /**
     * 通用 setup 之后逐个初始化引擎。会触发 [get] 从而创建引擎实例。
     * @return 第一个失败引擎的原因；全部成功返回 null
     */
    fun setupAll(userDir: java.io.File): String? {
        for (id in factories.keys) {
            get(id) ?: return "engine $id create failed"
            val err = runCatching { factories.getValue(id).onRemoteSetup(userDir) }
                .getOrElse { "engine $id setup threw: ${it.message}" }
            if (err != null) return err
        }
        return null
    }

    /** 各引擎自报版本，供提权进程的 version() 汇总 */
    fun versionSummary(): String = factories.values
        .mapNotNull { f -> runCatching { f.versionInfo() }.getOrNull()?.let { "${f.engineId}: $it" } }
        .joinToString("\n") .ifEmpty { "no engine loaded" }

    /** 进程退出与紧急清理时逐个释放；只关已经创建出来的，未加载的引擎不去触碰 */
    fun closeAll() {
        instances.keys.toList().forEach { id ->
            runCatching { factories[id]?.close() }
            instances.remove(id)
        }
    }
}

/**
 * 引擎在提权进程侧的工厂。每个引擎模块实现一个，由 `:app` 负责注册。
 */
interface RemoteEngineFactory {
    /** 见 [EngineIds] */
    val engineId: String

    /** 创建该引擎的 AIDL 服务实现；只会被调用一次，结果由注册表缓存 */
    fun create(): IBinder

    /** 释放引擎持有的原生资源；未被创建过时不会调用 */
    fun close() {}

    /**
     * 通用 setup 完成后由提权进程回调，让引擎做自己的初始化（如设置用户目录、加载核心）。
     *
     * 存在的原因：原先 RemoteServiceImpl.setup() 里直接调了 MaaCore 的 AsstSetUserDir，
     * 把方舟写进了游戏无关的提权服务。改由引擎自述，core-remote 便无需认识任何引擎。
     *
     * @param userDir 提权进程为引擎准备好的数据目录
     * @return null 表示成功；非空为失败原因，提权侧据此返回 setup 错误码
     */
    fun onRemoteSetup(userDir: java.io.File): String? = null

    /** 供 version() 汇总，形如 "MaaCore v6.17.2"；不可用时返回 null */
    fun versionInfo(): String? = null
}
