package com.maadroid.app.engine.limbus.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

internal data class CatalogEntry(
    val name: String,
    val title: String,
    val style: String = "",
    val path: String,
) {
    // LALC theme_pack_page.dart: saved user weight, otherwise 10. The backend's
    // theme_pack_cfg.json is a desktop user's configuration, not catalog metadata.
    fun weight(weights: Map<String, Int>): Int = weights[name] ?: 10
}

/** Derived from the installed LALC archive, never merged with an APK snapshot. */
internal data class LimbusCatalog(
    val gifts: List<CatalogEntry> = emptyList(),
    val packs: List<CatalogEntry> = emptyList(),
) {
    companion object {
        fun load(root: File?): LimbusCatalog {
            if (root == null || !root.isDirectory) return LimbusCatalog()
            val language = root.resolve("config/language/zh/ego_gifts.json")
            val titles = if (language.isFile) Json.parseToJsonElement(language.readText()).jsonObject else emptyMap()
            val giftRoot = root.resolve("img/general/ego_gifts")
            val gifts = giftRoot.walkTopDown().filter { it.isFile && it.extension == "png" }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.map { file ->
                    val name = file.nameWithoutExtension
                    val title = (titles[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.takeIf { it.isNotBlank() } ?: name
                    CatalogEntry(name, title, file.parentFile!!.relativeTo(giftRoot).invariantSeparatorsPath,
                        file.relativeTo(root).invariantSeparatorsPath)
                }.distinctBy { it.name }.toList()
            // Upstream lists only direct children of theme_packs.
            val packs = root.resolve("img/general/theme_packs").listFiles().orEmpty()
                .filter { it.isFile && it.extension == "png" }.sortedBy { it.name }.map { file ->
                    CatalogEntry(file.nameWithoutExtension, file.nameWithoutExtension,
                        path = file.relativeTo(root).invariantSeparatorsPath)
                }
            return LimbusCatalog(gifts, packs)
        }
    }
}

internal data class LimbusCatalogState(
    val revision: String? = null,
    val catalog: LimbusCatalog = LimbusCatalog(),
    val message: String? = "正在读取资源图鉴…",
)

/** Called off the UI thread. A new manifest revision replaces the whole catalog. */
internal class LimbusCatalogReader {
    private var source: Pair<File?, String?>? = null
    private var state = LimbusCatalogState()

    fun refresh(root: File?, revision: String?): LimbusCatalogState {
        val next = root to revision
        if (next == source) return state
        state = if (root == null || revision == null) {
            LimbusCatalogState(message = "请先下载边狱资源，再配置图鉴。已有配置已保留，任务和队伍设置仍可编辑。")
        } else {
            try {
                LimbusCatalogState(revision, LimbusCatalog.load(root), message = null)
            } catch (_: Exception) {
                LimbusCatalogState(revision, message = "图鉴资源读取失败，请重新下载边狱资源。已有配置已保留。")
            }
        }
        source = next
        return state
    }
}
