package com.aliothmoon.maadroid.engine.limbus

import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.engine.ResourceRevision
import com.aliothmoon.maadroid.engine.UpstreamArchive
import com.aliothmoon.maadroid.engine.isSafeResourcePath
import com.aliothmoon.maadroid.engine.limbus.action.ActionRegistry
import com.aliothmoon.maadroid.engine.limbus.action.LimbusActions
import com.aliothmoon.maadroid.engine.limbus.resource.LimbusResourceManifest
import com.aliothmoon.maadroid.remote.EngineIds
import java.io.File
import kotlinx.serialization.json.*

/** LALC resources extracted from an immutable upstream source archive; no project Release required. */
object LimbusResourcePack : ResourcePackSpec {
    override val packId = "limbus-main"
    override val engineId = EngineIds.LIMBUS
    override val relativeRoot = "engines/limbus"
    override val bundledAssetPrefix: String? = null
    override val requiresPrivilegedDelivery = false
    override val upstreamArchive = UpstreamArchive(
        repository = LimbusResourceManifest.REPOSITORY,
        initialRevision = ResourceRevision("v5.0.0", "431b432e22f0b0da08b95d7c478fa213be20b3e8"),
        resourcePrefix = "lalc_backend",
        directories = LimbusResourceManifest.directories,
        inspectionSuffixes = listOf(".py"),
    )

    override fun finalizeUpstreamInstall(resourceDir: File, revision: ResourceRevision) =
        LimbusResourceManifest.finalize(resourceDir, revision, ::checkCompatibility)

    override fun verifyInstalledFiles(resourceDir: File): String? =
        LimbusResourceManifest.verify(resourceDir, ::checkCompatibility)

    override fun readInstalledVersion(resourceDir: File): String? = runCatching {
        val value = Json.parseToJsonElement(manifestFile(resourceDir).readText()).jsonObject["revision"]
        (value as? JsonPrimitive)?.takeIf { it.isString && it.content.matches(Regex("[0-9a-f]{64}")) }?.content
    }.getOrNull()

    /** Legacy flat resource ZIP mapping, kept for ResourcePackSpec callers. */
    override fun mapZipEntry(entryName: String): String? = entryName.takeIf {
        isSafeResourcePath(it) && !it.endsWith(".py", true) &&
            (it == MANIFEST_NAME || upstreamArchive.directories.any { dir -> it.startsWith("$dir/") })
    }

    override fun checkCompatibility(manifestJson: String?): String? {
        LimbusActions.install()
        return try {
            val obj = Json.parseToJsonElement(requireNotNull(manifestJson)).jsonObject
            val schema = (obj["schema_version"] as? JsonPrimitive)?.intOrNull
            if (schema != SUPPORTED_SCHEMA) {
                return "资源包清单版本 $schema 不受支持（本 App 支持 $SUPPORTED_SCHEMA），请升级 App"
            }
            val minEngine = (obj["min_engine_version"] as? JsonPrimitive)?.intOrNull
                ?: return "资源包缺少有效 min_engine_version"
            if (minEngine < 0 || minEngine > ENGINE_VERSION) {
                return "该资源包要求引擎版本 $minEngine，当前为 $ENGINE_VERSION，请升级 App"
            }
            val missing = ActionRegistry.missing(LimbusResourceManifest.stringList(obj, "required_actions"))
            if (missing.isEmpty()) null else "该资源包需要本 App 尚未实现的动作（${missing.joinToString("、")}），请升级 App"
        } catch (e: Exception) {
            "资源包缺少或无法解析 $MANIFEST_NAME：${e.message.orEmpty()}"
        }
    }

    override fun invalidateInstalledVersion(resourceDir: File) { manifestFile(resourceDir).delete() }
    fun manifestFile(resourceDir: File) = File(resourceDir, MANIFEST_NAME)

    const val MANIFEST_NAME = "manifest.json"
    const val SUPPORTED_SCHEMA = 1
    const val ENGINE_VERSION = 1
}
