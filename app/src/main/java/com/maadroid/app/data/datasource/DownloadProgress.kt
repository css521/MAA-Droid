package com.maadroid.app.data.datasource

import com.maadroid.app.R
import com.maadroid.app.common.i18n.UiText
import com.maadroid.app.common.i18n.uiTextDynamic
import com.maadroid.app.common.i18n.uiTextOf
import java.io.IOException
import java.util.Locale

data class DownloadProgress(
    /** Legacy percentage; only meaningful when total > 0. UI should use [bytes]. */
    val progress: Int,
    val speed: String,
    val downloaded: Long,
    val total: Long,
) {
    val bytes: ByteProgress get() = ByteProgress(downloaded, total)
}

internal fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024 * 1024 -> String.format(
        Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024)
    )

    bytesPerSecond >= 1024 -> String.format(
        Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0
    )

    else -> "$bytesPerSecond B/s"
}

internal fun formatDownloadError(e: Exception): UiText = when (e) {
    is IOException -> uiTextOf(R.string.update_error_network_io)
    else -> e.message?.let(::uiTextDynamic) ?: uiTextOf(R.string.update_error_unknown)
}
