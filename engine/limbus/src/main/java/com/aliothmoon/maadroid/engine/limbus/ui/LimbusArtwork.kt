package com.aliothmoon.maadroid.engine.limbus.ui

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
import com.aliothmoon.maadroid.engine.limbus.LimbusResourcePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

internal val LocalLimbusResourceRevision = staticCompositionLocalOf<String?> { null }

/** The host currently exposes only a File. Watch its manifest while this page is
 * composed so an atomic install at the same path also refreshes the catalog.
 * A future host revision key can restart this producer immediately. */
@Composable
internal fun rememberLimbusCatalog(root: File?, updateKey: Any? = null): State<LimbusCatalogState> =
    produceState(LimbusCatalogState(), root, updateKey) {
        value = LimbusCatalogState()
        val reader = LimbusCatalogReader()
        while (isActive) {
            val loaded = withContext(Dispatchers.IO) {
                val revision = root?.let(LimbusResourcePack::readInstalledVersion)
                val state = reader.refresh(root, revision)
                // Do not publish a scan spanning two atomic resource installations.
                state.takeIf { revision == root?.let(LimbusResourcePack::readInstalledVersion) }
            }
            if (loaded != null) value = loaded
            delay(1_000)
        }
    }

@Composable
internal fun LimbusArtwork(path: String, root: File?, modifier: Modifier = Modifier) {
    val revision = LocalLimbusResourceRevision.current
    key(path, root, revision) {
        val bitmap by produceState<ImageBitmap?>(null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    if (revision == null) return@runCatching null
                    root?.resolve(path)?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
                }.getOrNull()
            }
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap!!, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
            else Text("◇")
        }
    }
}
