package com.maadroid.app.data.datasource

import java.util.Locale

/** A missing Content-Length remains unknown, including after EOF. */
data class ByteProgress(val downloaded: Long, val total: Long) {
    val fraction: Float?
        get() = if (total > 0) (downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
    val percent: Int?
        get() = if (total > 0) (downloaded.toDouble() * 100 / total).coerceIn(0.0, 100.0).toInt() else null
    val downloadedText: String get() = formatBytes(downloaded)
    val totalText: String? get() = total.takeIf { it > 0 }?.let(::formatBytes)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "${bytes.coerceAtLeast(0)} B"
}
