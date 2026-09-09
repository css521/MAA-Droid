package com.aliothmoon.maadroid.domain.service.update

import android.content.Context
import com.aliothmoon.maadroid.common.i18n.UiText
import com.aliothmoon.maadroid.constant.AppApi
import com.aliothmoon.maadroid.data.api.HttpClientHelper
import com.aliothmoon.maadroid.data.api.MirrorChyanApiClient
import com.aliothmoon.maadroid.data.datasource.AppDownloader
import com.aliothmoon.maadroid.data.datasource.ResourceDownloader
import com.aliothmoon.maadroid.data.model.update.*
import com.aliothmoon.maadroid.domain.service.update.checker.AppVersionChecker
import com.aliothmoon.maadroid.domain.service.update.checker.ResourceVersionChecker
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UpdateServiceAppSourceTest {
    @After fun tearDown() = unmockkAll()

    @Test fun disabledApkDoesNotReachNetworkCacheOrInstallerAndResourcesStillCheck() = runBlocking {
        mockkObject(AppApi)
        val sources = AppUpdateSourceConfig("", "", "OldApk")
        every { AppApi.APP_UPDATE_SOURCE } returns sources
        val context = mockk<Context>()
        val http = mockk<HttpClientHelper>()
        val api = mockk<MirrorChyanApiClient>()
        val apk = mockk<AppDownloader>()
        val resource = mockk<ResourceDownloader>()
        val appChecker = mockk<AppVersionChecker>()
        val resourceChecker = mockk<ResourceVersionChecker>()
        coEvery { resourceChecker.check("resource-version") } returns UpdateCheckResult.UpToDate("resource-version")
        val service = UpdateService(context, api, mockk(), http, appChecker, resourceChecker, apk, resource, mockk(), mockk(), mockk())
        assertTrue(service.checkAppUpdate() is UpdateCheckResult.Error)
        for (source in UpdateSource.entries) {
            val result = service.downloadApp(source, "2.0.0")
            assertTrue(result.isFailure)
            assertEquals(sources.disabledReason, result.exceptionOrNull()!!.message)
            val failed = service.appProcessState.value as UpdateProcessState.Failed
            assertEquals(sources.disabledReason, (failed.error.text as UiText.Dynamic).value)
        }
        assertEquals(UpdateCheckResult.UpToDate("resource-version"), service.checkResourceUpdate("resource-version"))
        coVerify(exactly = 1) { resourceChecker.check("resource-version") }
        verify { listOf(context, http, api, apk, resource, appChecker) wasNot Called }
    }

    @Test fun missingMirrorRidCannotDownloadEvenWhenGithubIsConfigured() = runBlocking {
        mockkObject(AppApi)
        val sources = AppUpdateSourceConfig("example-org", "independent-app")
        every { AppApi.APP_UPDATE_SOURCE } returns sources
        val http = mockk<HttpClientHelper>()
        val api = mockk<MirrorChyanApiClient>()
        val apk = mockk<AppDownloader>()
        val service = UpdateService(mockk(), api, mockk(), http, mockk(), mockk(), apk, mockk(), mockk(), mockk(), mockk())
        assertTrue(service.downloadApp(UpdateSource.MIRROR_CHYAN, "2.0.0").isFailure)
        val failed = service.appProcessState.value as UpdateProcessState.Failed
        assertEquals(sources.mirrorChyanUnavailableReason, (failed.error.text as UiText.Dynamic).value)
        verify { listOf(http, api, apk) wasNot Called }
    }
}
