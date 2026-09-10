package com.aliothmoon.maadroid.engine

import java.io.File

/**
 * 一个可热更资源包的声明。宿主的更新服务只认这个接口，因此**两个引擎各自跟随各自上游**
 * 而宿主不需要为任何一个写特例。
 *
 * 两种 feed 形态都要支持（这是实测后的结论，不是设计洁癖）：
 * - 方舟：上游 MAA 直接发布 MaaResource，且已有 MirrorChyan 渠道 —— 沿用既有下载链路，
 *   本接口只描述「版本怎么读、zip 条目怎么落盘」。
 * - 边狱：直接下载 LALC 固定 commit 的源码归档，在暂存目录提取资源并生成逐文件
 *   sha256 清单；首次安装不依赖 GitHub API 或本项目 Release。
 */
interface ResourcePackSpec {

    /** 包标识，同一引擎可有多个包（如主资源 + OCR 模型） */
    val packId: String

    /** 所属引擎，见 `EngineIds` */
    val engineId: String

    /**
     * 资源落盘根目录，**相对 App 的数据根**（`externalFilesDir/Maa`，见 `EngineDataRoot`）。
     *
     * 不是相对提权进程的 `/data/local/tmp` —— 那个目录 App 进程读不到，而跑在 App 进程的
     * 引擎必须能直接打开自己的模板与模型。需要投到提权侧的包由
     * [requiresPrivilegedDelivery] 标记，那是**额外一步**，不改变本字段的基准。
     */
    val relativeRoot: String

    /** 内置于 APK 的初始资源在 assets 下的前缀；为空表示该包必须联网获取 */
    val bundledAssetPrefix: String?

    /** Null preserves the engine's existing updater (for example MAA / MirrorChyan). */
    val upstreamArchive: UpstreamArchive? get() = null

    /**
     * APK assets 下的**素材覆盖层**前缀；null 表示不覆盖。
     *
     * 上游归档解包后、[finalizeUpstreamInstall] 之前，安装器会把该前缀下的文件按相对路径
     * 盖到 staging 目录上。存在的理由：上游素材可能是为另一个平台截的
     * （边狱的上游 LALC 只自动化 Steam 客户端，部分控件在安卓上完全不同），
     * 而这类修正必须**在上游更新后依然生效**——放在覆盖层里，上游怎么更新都不会被冲掉，
     * 且只需装「上游素材在本平台不适用」的那几张，不是整套重做。
     *
     * 与 [bundledAssetPrefix] 的区别：那个是「该包的初始内容」，这个是「盖在上游之上的修正」。
     */
    val overlayAssetPrefix: String? get() = null

    /** Validate an extracted source archive and write its installed manifest, or throw. */
    fun finalizeUpstreamInstall(resourceDir: File, revision: ResourceRevision) {
        error("$packId does not support source archive installation")
    }

    /** Content verification before use; an error must leave the previous installation intact. */
    fun verifyInstalledFiles(resourceDir: File): String? = null

    /**
     * 资源是否需要额外投递到**提权进程**的数据目录。
     *
     * 取决于该引擎跑在哪个进程（见 AutomationEngine 的说明）：
     * - 方舟的 MaaCore 是 native、跑在提权进程，资源必须送到那边它才读得到 → true
     * - 边狱跑在 App 进程、直接读自己的资源目录 → false
     *
     * 是引擎的**执行位置**决定的属性，不是某个游戏的特例，所以放在契约里而不是
     * 让更新服务去认游戏 id。
     */
    val requiresPrivilegedDelivery: Boolean

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
     * 让已落盘资源重新被视作「未装载」。
     *
     * 解压中途失败时资源目录处于残缺状态，必须抹掉版本标记，否则下次检查会认为
     * 已是最新而不再补齐 —— 用户会拿着一份缺文件的资源包一直跑。
     *
     * 实现应删掉 [readInstalledVersion] 依赖的那个文件：方舟是 `version.json`，
     * 边狱是我们清单的 `manifest.json`。
     */
    fun invalidateInstalledVersion(resourceDir: File)

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
