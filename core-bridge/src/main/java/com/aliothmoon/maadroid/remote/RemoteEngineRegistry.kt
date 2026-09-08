package com.aliothmoon.maadroid.remote

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
}
