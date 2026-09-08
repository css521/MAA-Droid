package com.aliothmoon.maadroid.engine.limbus

import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.engine.limbus.action.ActionRegistry
import com.aliothmoon.maadroid.remote.EngineIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 边狱资源包：流水线 JSON + 模板图 + ONNX 模型 + 语言包。
 *
 * feed 来源是本仓库自己的 Release，而不是上游 —— 上游 LALC 只发一个 249 MB 的 Windows
 * 整包（含 Python 运行时、无清单、无逐文件 sha256），无法增量。由 CI 从其 tag 取
 * config/task、config/language、img、ai/model 四份重打包成约 25 MB 的包，
 * 见 scripts/pack_engine_resource.py。
 */
object LimbusResourcePack : ResourcePackSpec {

    override val packId: String = "limbus-main"

    override val engineId: String = EngineIds.LIMBUS

    override val relativeRoot: String = "engines/limbus"

    /**
     * 不内置于 APK：25 MB 素材进包会让 APK 明显变大，而首启必然要联网校验更新，
     * 不如统一走热更。首次使用前宿主会引导下载。
     */
    override val bundledAssetPrefix: String? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 我们自己的清单以 revision（对全部文件 path+sha256 求的稳定摘要）作版本 */
    override fun readInstalledVersion(resourceDir: File): String? =
        manifestOf(resourceDir)?.get("revision")?.jsonPrimitive?.contentOrNull

    /**
     * 我们的包是平铺布局（config/ img/ ai/ 直接在包根），无顶层目录，
     * 所以除清单本身外原样落盘。
     */
    override fun mapZipEntry(entryName: String): String? = when {
        entryName.endsWith("/") -> null
        entryName == MANIFEST_NAME -> MANIFEST_NAME
        entryName.startsWith("config/") -> entryName
        entryName.startsWith("img/") -> entryName
        entryName.startsWith("ai/") -> entryName
        else -> null
    }

    /**
     * 兼容门闸。上游改流程、图、阈值 → 无感跟随；但若上游新增了本 App 尚未实现的动作，
     * 必须在装载前拦住并提示升级，而不是跑到一半崩在某个节点上。
     */
    override fun checkCompatibility(manifestJson: String?): String? {
        val obj = runCatching { manifestJson?.let { json.parseToJsonElement(it) as JsonObject } }
            .getOrNull() ?: return "资源包缺少或无法解析 $MANIFEST_NAME"

        val schema = obj["schema_version"]?.jsonPrimitive?.intOrNull
        if (schema != SUPPORTED_SCHEMA) {
            return "资源包清单版本 $schema 不受支持（本 App 支持 $SUPPORTED_SCHEMA），请升级 App"
        }

        val minEngine = obj["min_engine_version"]?.jsonPrimitive?.intOrNull ?: 0
        if (minEngine > ENGINE_VERSION) {
            return "该资源包要求引擎版本 $minEngine，当前为 $ENGINE_VERSION，请升级 App"
        }

        val required = obj["required_actions"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val missing = ActionRegistry.missing(required)
        if (missing.isNotEmpty()) {
            return "该资源包需要 ${missing.size} 个本 App 尚未实现的动作" +
                "（${missing.take(3).joinToString("、")}${if (missing.size > 3) "…" else ""}），请升级 App"
        }
        return null
    }

    fun manifestFile(resourceDir: File): File = File(resourceDir, MANIFEST_NAME)

    private fun manifestOf(resourceDir: File): JsonObject? =
        manifestFile(resourceDir).takeIf { it.isFile }
            ?.let { f -> runCatching { json.parseToJsonElement(f.readText()) as JsonObject }.getOrNull() }

    const val MANIFEST_NAME = "manifest.json"

    /** 与 scripts/pack_engine_resource.py 的 SCHEMA_VERSION 对齐 */
    const val SUPPORTED_SCHEMA = 1

    /**
     * 引擎实现版本。新增/改变动作语义时递增，并在打包器里同步提高受支持包的
     * min_engine_version，从而让旧 App 拒绝装载需要新语义的包。
     */
    const val ENGINE_VERSION = 1
}
