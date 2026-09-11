package com.maadroid.app.domain.service

import android.content.Context
import com.maadroid.app.data.config.MaaPathConfig
import com.maadroid.app.data.datasource.AssetExtractor
import com.maadroid.app.domain.state.ResourceInitState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceInitServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun missingOcrAfterExtractionIsNotMarkedReadyAndCanBeRetried() = runBlocking {
        val root = tmp.newFolder()
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } returns "Preparing"
        every { context.assets.open(any()) } answers { ByteArrayInputStream("{}".toByteArray()) }
        val paths = mockk<MaaPathConfig> {
            every { isResourceReady } returns false
            every { ensureDirectories() } returns true
            every { resourceDir } returns File(root, "resource").absolutePath
            every { overrideTasksFile } returns File(root, "overrides/resource/tasks/tasks.json")
            every { missingOcrFiles() } returns listOf("PaddleOCR/rec/rec.ncnn.bin")
        }
        justRun { paths.markAppVersion() }
        val extractor = mockk<AssetExtractor>()
        coEvery { extractor.extract(any(), any(), any()) } returns Result.success(100)
        val service = ResourceInitService(context, extractor, paths)

        service.checkAndInit()
        assertTrue(service.state.value is ResourceInitState.Failed)
        verify(exactly = 0) { paths.markAppVersion() }

        every { paths.missingOcrFiles() } returns emptyList()
        service.checkAndInit()
        assertTrue(service.state.value is ResourceInitState.Ready)
        verify(exactly = 1) { paths.markAppVersion() }
        coVerify(exactly = 2) { extractor.extract(any(), any(), any()) }
    }
}
