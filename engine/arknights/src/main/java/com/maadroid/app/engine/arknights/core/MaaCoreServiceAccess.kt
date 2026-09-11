package com.maadroid.app.engine.arknights.core

import com.maadroid.app.MaaCoreService
import com.maadroid.app.RemoteService
import com.maadroid.app.remote.EngineIds

/**
 * 从提权进程按 engineId 取明日方舟引擎（MaaCore）的服务。
 *
 * 原先 `RemoteService` 上有一个专属方法 `getMaaCoreService()`，等于把「明日方舟」写进了
 * 进程层契约，第二个游戏无处安放。现在契约只剩通用的 `getEngineService(String)`，
 * 由各引擎自己把 binder 转回自己的接口，本文件属于 engine:arknights 的协议适配。
 *
 * 旧的 getMaaCoreService() 返回非空（MaaCoreManager.maaService 是惰性非空对象），而方舟
 * 引擎在 RemoteServiceImpl.init 里必定注册，故这里维持非空语义、缺失时抛出便于定位
 * 「提权进程未装配引擎」这类装配错误；需要容忍缺失的新代码用 [maaCoreServiceOrNull]。
 */
val RemoteService.maaCoreService: MaaCoreService
    get() = maaCoreServiceOrNull
        ?: error("方舟引擎未在提权进程注册（engineId=${EngineIds.ARKNIGHTS}）")

/** 允许引擎不存在的取用方式：未注册或跨进程调用失败时返回 null */
val RemoteService.maaCoreServiceOrNull: MaaCoreService?
    get() = runCatching {
        MaaCoreService.Stub.asInterface(getEngineService(EngineIds.ARKNIGHTS))
    }.getOrNull()
