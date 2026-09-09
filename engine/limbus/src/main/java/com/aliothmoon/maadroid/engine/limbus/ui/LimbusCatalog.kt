package com.aliothmoon.maadroid.engine.limbus.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class CatalogEntry(val name: String, val title: String, val style: String = "", val path: String, val weight: Int = 10)

@Serializable
internal data class LimbusCatalog(val gifts: List<CatalogEntry> = emptyList(), val packs: List<CatalogEntry> = emptyList()) {
    companion object {
        fun load(context: Context, root: File?): LimbusCatalog {
            val bundled = context.assets.open("lalc/ui/catalog.json").bufferedReader().use {
                Json { ignoreUnknownKeys = true }.decodeFromString<LimbusCatalog>(it.readText())
            }
            // 安装的上游图片可增加新条目；用户权重和偏好独立保存，更新不覆盖。
            fun extend(entries: List<CatalogEntry>, directory: String): List<CatalogEntry> {
                val result = entries.associateByTo(linkedMapOf()) { it.name }
                root?.resolve("img/general/$directory")?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "png" }?.forEach { file ->
                    result.putIfAbsent(file.nameWithoutExtension, CatalogEntry(file.nameWithoutExtension, file.nameWithoutExtension, file.parentFile?.name.orEmpty(), file.relativeTo(root).invariantSeparatorsPath))
                }
                return result.values.toList()
            }
            return LimbusCatalog(extend(bundled.gifts, "ego_gifts"), extend(bundled.packs, "theme_packs"))
        }
    }
}

@Composable
internal fun LimbusArtwork(path: String, root: File?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, path, root) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val installed = root?.resolve(path)
                val decoded = if (installed?.isFile == true) BitmapFactory.decodeFile(installed.path)
                else context.assets.open("lalc/ui/$path").use { BitmapFactory.decodeStream(it) }
                decoded?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
        else Text("◇")
    }
}
