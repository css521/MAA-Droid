package com.maadroid.app.remote

/**
 * 引擎标识。跨进程按 id 取引擎服务（见 `RemoteService.getEngineService`）。
 *
 * core-bridge 只持有 id 字符串常量，不认识任何引擎的接口 —— 具体引擎在提权进程侧
 * 通过 `RemoteEngineRegistry` 注册自己的 binder，App 侧再按 id 取回并转成各自的
 * AIDL 接口。新增游戏时只在此追加一个常量，无需改动 core-* 任何代码。
 */
object EngineIds {
    /** 明日方舟，由 MaaCore 驱动 */
    const val ARKNIGHTS = "arknights"

    /** 边狱公司，由移植自 LALC 的流水线引擎驱动 */
    const val LIMBUS = "limbus"
}
