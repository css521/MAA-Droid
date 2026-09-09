package com.aliothmoon.maadroid.data.datasource

import android.content.Context
import com.aliothmoon.maadroid.data.api.HttpClientHelper
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceDownloaderProgressTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun knownAndUnknownHttpLengthsStreamActualBytesOffUiThread() {
        val data = ByteArray(150_000) { (it % 251).toByte() }
        for (length in listOf(data.size.toLong(), -1L)) {
            val callerThread = AtomicReference<Thread>()
            val context = mockk<Context>()
            every { context.cacheDir } answers {
                assertNotSame(callerThread.get(), Thread.currentThread())
                temp.root
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("identity", chain.request().header("Accept-Encoding"))
                val body = object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = length
                    override fun source(): BufferedSource = Buffer().write(data)
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("test").body(body).build()
            }.build()
            Executors.newSingleThreadExecutor { Thread(it, "resource-ui-test") }.asCoroutineDispatcher().use { ui ->
                runBlocking(ui) {
                    callerThread.set(Thread.currentThread())
                    val events = mutableListOf<DownloadProgress>()
                    val file = ResourceDownloader(context, HttpClientHelper(client))
                        .downloadToTempFile("https://example.invalid/resources.zip") {
                            assertNotSame(callerThread.get(), Thread.currentThread())
                            events += it
                        }.getOrThrow()
                    assertArrayEquals(data, file.readBytes())
                    assertEquals(data.size.toLong(), events.last().downloaded)
                    if (length < 0) assertTrue(events.all { it.bytes.percent == null })
                    else assertEquals(100, events.last().bytes.percent)
                    file.delete()
                }
            }
        }
    }

    @Test fun interruptedHttpBodyRemovesPartialArchive() = runBlocking {
        val context = mockk<Context>()
        every { context.cacheDir } returns temp.root
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = object : ForwardingSource(Buffer().write(ByteArray(16_384))) {
                var count = 0
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (++count > 1) throw IOException("interrupted")
                    return super.read(sink, byteCount)
                }
            }.buffer()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("test").body(body).build()
        }.build()
        val result = ResourceDownloader(context, HttpClientHelper(client))
            .downloadToTempFile("https://example.invalid/resources.zip", {})
        assertTrue(result.isFailure)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
}
