package com.aliothmoon.maadroid.data.datasource

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

private const val PROGRESS_INTERVAL_NS = 100_000_000L

/** 边拷贝边回报进度，每轮检查取消 */
internal suspend fun InputStream.copyWithProgress(
    output: OutputStream,
    total: Long,
    bufferSize: Int,
    onProgress: (DownloadProgress) -> Unit,
    nanoTime: () -> Long = System::nanoTime,
) = withContext(Dispatchers.IO) {
    require(bufferSize > 0)
    val buffer = ByteArray(bufferSize)
    var downloaded = 0L
    var lastUpdateTime = nanoTime()
    var lastDownloaded = 0L
    var speed = 0L

    fun publish(now: Long) {
        val elapsed = now - lastUpdateTime
        if (elapsed > 0 && downloaded > lastDownloaded) {
            speed = ((downloaded - lastDownloaded).toDouble() * 1_000_000_000 / elapsed).toLong()
        }
        onProgress(DownloadProgress(
            progress = ByteProgress(downloaded, total).percent ?: 0,
            speed = formatSpeed(speed),
            downloaded = downloaded,
            total = total.coerceAtLeast(0),
        ))
        lastUpdateTime = now
        lastDownloaded = downloaded
    }

    publish(lastUpdateTime)
    var firstChunk = true

    while (true) {
        currentCoroutineContext().ensureActive()

        val read = read(buffer)
        if (read == -1) break
        if (read == 0) continue
        output.write(buffer, 0, read)
        downloaded += read
        if (total > 0 && downloaded > total) throw EOFException("Download exceeds Content-Length")
        val now = nanoTime()
        if (firstChunk || now - lastUpdateTime >= PROGRESS_INTERVAL_NS) {
            publish(now)
            firstChunk = false
        }
    }
    currentCoroutineContext().ensureActive()
    if (total > 0 && downloaded != total) throw EOFException("Incomplete download: $downloaded/$total bytes")
    // The final buffered write/flush and the final byte report stay on IO, even for small downloads.
    output.flush()
    currentCoroutineContext().ensureActive()
    publish(nanoTime())
}
