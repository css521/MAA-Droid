package com.aliothmoon.maadroid.engine.arknights.core

import com.aliothmoon.maadroid.MaaCoreCallback
import com.aliothmoon.maadroid.MaaCoreService

/** 真实的跨进程实现；测试使用同一接口的内存实现，无需加载 JNA 或 Android Binder。 */
class AidlMaaCoreClient(private val service: MaaCoreService) : MaaCoreClient {
    override fun hasInstance() = service.hasInstance()

    override fun createInstance(callback: (Int, String?) -> Unit) =
        service.CreateInstance(object : MaaCoreCallback.Stub() {
            override fun onCallback(msg: Int, json: String?) = callback(msg, json)
        })

    override fun setInstanceOption(key: Int, value: String) = service.SetInstanceOption(key, value)
    override fun asyncConnect(config: String) = service.AsyncConnect("", "Android", config, false)
    override fun appendTask(type: String, params: String) = service.AppendTask(type, params)
    override fun setTaskParams(taskId: Int, params: String) = service.SetTaskParams(taskId, params)
    override fun start() = service.Start()
    override fun stop() = service.Stop()
    override fun running() = service.Running()
    override fun version(): String = service.GetVersion()
}
