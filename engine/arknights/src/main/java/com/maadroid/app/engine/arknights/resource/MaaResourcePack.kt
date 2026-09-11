package com.maadroid.app.engine.arknights.resource

import com.maadroid.app.constant.MaaFiles
import com.maadroid.app.engine.ResourcePackSpec
import com.maadroid.app.remote.CoreDataDir
import com.maadroid.app.remote.EngineIds
import com.maadroid.app.remote.ResourceFiles
import java.io.File

/**
 * 明日方舟资源包。
 *
 * 与边狱不同，方舟侧**不需要我们重打包**：上游 MaaAssistantArknights 直接发布
 * MaaResource（GitHub 归档 + MirrorChyan 渠道），既有的 UpdateService / ResourceDownloader /
 * MirrorChyan* 链路已经能跟上。所以这里只是把那套现成机制按 [ResourcePackSpec] 的形状
 * 声明出来，让宿主的更新服务能与边狱包一视同仁地遍历，而不必为任一引擎写特例。
 *
 * 这也是「两个引擎各自跟随各自上游」的实现方式：差异被本接口吸收，宿主不感知。
 */
object MaaResourcePack : ResourcePackSpec {

    override val packId: String = "maa-resource"

    override val engineId: String = EngineIds.ARKNIGHTS

    /**
     * 沿用既有布局（`cache/resource`），**不迁到 engines/arknights 下**：
     * 改路径会让所有老用户重新下载 251 MB 资源，收益为零。
     */
    override val relativeRoot: String = "${MaaFiles.CACHE}/${MaaFiles.RESOURCE}"

    /** 方舟资源内置于 APK（assets/MaaSync/MaaResource），首启即可用，无需先联网 */
    override val bundledAssetPrefix: String? = MaaFiles.ASSET_DIR_NAME

    /** 上游 MaaResource 以 version.json 的 last_updated 作版本 */
    override fun readInstalledVersion(resourceDir: File): String? =
        ResourceFiles.readLastUpdated(File(resourceDir, ResourceFiles.VERSION_FILE))

    /**
     * 复用既有规则：GitHub 归档带一层顶层目录（MaaResource-main/），镜像源可能没有，
     * 两侧（App 解包与提权进程落盘）必须用同一套映射。
     */
    /** MaaCore 跑在提权进程，资源必须投递过去它才读得到 */
    override val requiresPrivilegedDelivery: Boolean = true

    override fun mapZipEntry(entryName: String): String? =
        CoreDataDir.hotUpdateEntryToRelPath(entryName)

    /** 抹掉 version.json，与既有 UpdateService 失败分支的做法一致 */
    override fun invalidateInstalledVersion(resourceDir: File) {
        File(resourceDir, MaaFiles.VERSION_FILE).delete()
    }

    /**
     * 方舟侧没有「动作白名单」这类门闸 —— MaaCore 自己解析 tasks.json，
     * 资源与核心的兼容由 MaaCore 版本保证（BuildConfig.MAA_CORE_VERSION 与
     * 资源包的 stamp 在 ensureCoreResources 里比对）。故此处放行。
     */
    override fun checkCompatibility(manifestJson: String?): String? = null
}
