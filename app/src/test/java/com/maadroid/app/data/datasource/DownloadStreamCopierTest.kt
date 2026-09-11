package com.maadroid.app.data.datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

class DownloadStreamCopierTest {

    /** 永远读得到数据的流，模拟没下完的下载 */
    private class EndlessInputStream(private val reads: AtomicInteger) : InputStream() {
        override fun read(): Int = 1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads.incrementAndGet()
            b.fill(1, off, off + len)
            return len
        }
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    @Test
    fun `取消协程时拷贝循环立刻退出`() = runBlocking {
        val reads = AtomicInteger(0)
        val job = launch(Dispatchers.IO) {
            EndlessInputStream(reads).copyWithProgress(
                output = NullOutputStream,
                total = 0L,
                bufferSize = 8 * 1024,
                onProgress = {},
            )
        }

        // 等循环真的转起来再取消
        while (reads.get() < 5) Thread.yield()

        // 循环里没有取消检查的话，这里会一直挂到超时
        withTimeout(5_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
    }

    @Test
    fun `正常读到 EOF 时数据完整`() = runBlocking {
        val data = ByteArray(300 * 1024) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()

        ByteArrayInputStream(data).copyWithProgress(
            output = output,
            total = data.size.toLong(),
            bufferSize = 64 * 1024,
            onProgress = {},
        )

        assertArrayEquals(data, output.toByteArray())
    }

    @Test fun shortDownloadStillPublishesFirstChunkAndFinalByteCount() = runBlocking {
        val events = mutableListOf<DownloadProgress>()
        ByteArrayInputStream(ByteArray(100)).copyWithProgress(ByteArrayOutputStream(), 100, 25, events::add, nanoTime = { 0 })
        assertEquals(listOf(0L, 25L, 100L), events.map { it.downloaded })
        assertEquals(100, events.last().bytes.percent)
    }

    @Test fun unknownLengthUpdatesBytesWithoutPercentageAtEof() = runBlocking {
        val events = mutableListOf<DownloadProgress>()
        var time = 0L
        ByteArrayInputStream(ByteArray(400)).copyWithProgress(ByteArrayOutputStream(), 0, 100, events::add, nanoTime = { time.also { time += 100_000_000 } })
        assertEquals(400L, events.last().downloaded)
        assertTrue(events.count { it.downloaded > 0 } >= 4)
        assertTrue(events.all { it.bytes.percent == null && it.total == 0L })
    }

    @Test fun truncatedStreamFailsWithoutReportingCompletion() = runBlocking {
        val events = mutableListOf<DownloadProgress>()
        val error = runCatching {
            ByteArrayInputStream(ByteArray(20)).copyWithProgress(ByteArrayOutputStream(), 100, 10, events::add)
        }.exceptionOrNull()
        assertTrue(error is java.io.EOFException)
        assertTrue(events.none { it.progress == 100 })
    }

    @Test fun finalFlushNeverBlocksTheCallerThread() {
        Executors.newSingleThreadExecutor { Thread(it, "resource-ui-test") }.asCoroutineDispatcher().use { ui ->
            runBlocking(ui) {
                val callerThread = Thread.currentThread()
                val writes = mutableListOf<Thread>()
                var flushed = false
                val output = object : ByteArrayOutputStream() {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        writes += Thread.currentThread()
                        super.write(b, off, len)
                    }
                    override fun flush() { writes += Thread.currentThread(); flushed = true }
                }
                ByteArrayInputStream(ByteArray(4)).copyWithProgress(output, 4, 2, {})
                assertTrue(flushed)
                assertTrue(writes.isNotEmpty() && writes.none { it === callerThread })
                assertSame(callerThread, Thread.currentThread())
            }
        }
    }
}
