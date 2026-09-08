package com.aliothmoon.maadroid.remote

import android.content.Context
import com.aliothmoon.maadroid.constant.MaaFiles
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import java.io.File

/**
 * 引擎资源目录的解析，游戏无关。
 *
 * 与 [CoreDataDir] 的分工要分清，混了会让引擎读不到自己的资源：
 *
 * | | 位置 | 谁能读 |
 * |---|---|---|
 * | [EngineDataRoot] | App 的 `externalFilesDir/Maa/<relativeRoot>` | **App 进程与提权进程都能读** |
 * | [CoreDataDir] | `/data/local/tmp/maameow` | **只有提权进程**（注释已明写「App 读不到」）|
 *
 * 跑在 App 进程的引擎（边狱）必须落在前者 —— 它要直接打开模板 PNG 与 ONNX 模型。
 * 跑在提权进程的引擎（方舟的 MaaCore）在 `CoreDataLocation.LOCAL_TMP` 模式下还需要
 * 一次额外投递，由 [ResourcePackSpec.requiresPrivilegedDelivery] 标记。
 */
object EngineDataRoot {

    /**
     * App 数据根。
     *
     * 目录名 `Maa` 是历史遗留：MAA-Meow 时代的用户数据（作业、自定义基建、覆盖配置）
     * 就在里面，**改名会让已装用户的文件凭空消失**，所以即便项目已改名 MAA-Droid
     * 也保持不动。同理 [CoreDataDir.ROOT] 里的 `maameow` 也不改。
     */
    fun of(context: Context): File = File(context.getExternalFilesDir(null), MaaFiles.MAA)

    /**
     * 某个资源包的落盘目录。
     *
     * 这就是 [ResourcePackSpec.relativeRoot] 的解析基准，也是引擎 `prepare()` 的入参。
     * 两侧必须用同一套解析，否则会出现「下载解包到了 A，引擎去 B 找」这种
     * 只在真机上才暴露、且日志里只表现为「资源缺失」的问题。
     */
    fun forPack(context: Context, pack: ResourcePackSpec): File =
        File(of(context), pack.relativeRoot)
}
