package com.aliothmoon.maadroid.engine

import java.io.File

/**
 * 一个可热更资源包的声明。宿主的更新服务只认这个接口，因此**两个引擎各自跟随各自上游**
 * 而宿主不需要为任何一个写特例。
 *
 * 两种 feed 形态都要支持（这是实测后的结论，不是设计洁癖）：
 * - 方舟：上游 MAA 直接发布 MaaResource，且已有 MirrorChyan 渠道 —— 沿用既有下载链路，
 *   本接口只描述「版本怎么读、zip 条目怎么落盘」。
 * - 边狱：上游 LALC 没有资源 feed（只发 249 MB 的 Windows 整包，无清单无逐文件校验），
 *   所以由本仓库 CI 从其 tag 重打包成 25 MB 带 sha256 清单的包发 Release，
 *   见 `scripts/pack_engine_resource.py`。
 */
interface ResourcePackSpec {

    /** 包标识，同一引擎可有多个包（如主资源 + OCR 模型） */
    val packId: String

    /** 所属引擎，见 `EngineIds` */
    val engineId: String

    /** 资源落盘根目录（相对提权进程的数据根） */
    val relativeRoot: String

    /** 内置于 APK 的初始资源在 assets 下的前缀；为空表示该包必须联网获取 */
    val bundledAssetPrefix: String?

    /**
     * 读取已落盘资源的版本。
     *
     * 方舟读 `version.json` 的 `last_updated`；边狱读我们清单里的 `revision`。
     * 返回 null 表示尚未装载或无法识别，宿主会当作需要全量下载。
     */
    fun readInstalledVersion(resourceDir: File): String?

    /**
     * 把热更 zip 的条目名映射为落盘相对路径；返回 null 表示该条目不属于资源应被忽略。
     *
     * 需要它是因为上游打包习惯不一：GitHub 归档带一层顶层目录，镜像源可能没有，
     * 而我们自己的包又是另一种布局。两侧（App 解包与提权进程落盘）必须用同一套规则。
     */
    fun mapZipEntry(entryName: String): String?

    /**
     * 装载前的兼容门闸。
     *
     * 上游改流程/图/阈值 → 无感跟随；但上游若引入了本 App 尚未实现的动作，
     * 必须在这里拦住并提示升级 App，而不是运行到一半才崩。
     *
     * @return null 表示可装载；非空为面向用户的拒绝原因
     */
    fun checkCompatibility(manifestJson: String?): String?
}
