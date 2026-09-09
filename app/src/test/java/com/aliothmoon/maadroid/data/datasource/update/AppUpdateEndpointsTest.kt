package com.aliothmoon.maadroid.data.datasource.update

import com.aliothmoon.maadroid.data.api.*
import com.aliothmoon.maadroid.data.api.model.MirrorChyanData
import com.aliothmoon.maadroid.data.model.update.*
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AppUpdateEndpointsTest {
    private val config = AppUpdateSourceConfig("example-org", "independent-app")
    private val base = "https://api.github.com/repos/example-org/independent-app/releases"
    private val http = mockk<HttpClientHelper>()
    private val api = mockk<MirrorChyanApiClient>()
    private val settings = mockk<AppSettingsManager>()

    @Test fun disabledConfigBlocksAllFourNetworkEntryPoints() = runBlocking {
        for (source in listOf(AppUpdateSourceConfig(), AppUpdateSourceConfig("", "app", "OldApk"))) {
            assertTrue(GitHubAppVersionChecker(http, source).check("1.0.0", UpdateChannel.STABLE) is UpdateCheckResult.Error)
            assertTrue(GitHubAppDownloadUrlResolver(http, source).resolve("1.1.0", UpdateChannel.STABLE).exceptionOrNull() is AppUpdateSourceUnavailableException)
            assertTrue(MirrorChyanAppVersionChecker(api, settings, source).check("1.0.0", UpdateChannel.BETA) is UpdateCheckResult.Error)
            assertTrue(MirrorChyanAppDownloadUrlResolver(api, settings, source).resolve("1.1.0", UpdateChannel.BETA).exceptionOrNull() is AppUpdateSourceUnavailableException)
        }
        verify { listOf(http, api, settings) wasNot Called }
    }

    @Test fun stableChecksConfiguredRepositoryAndReturnsTagAndNotes() = runBlocking {
        coEvery { http.get("$base/latest", any(), any()) } returns response(release("v2.0.0"))
        val result = GitHubAppVersionChecker(http, config).check("1.0.0", UpdateChannel.STABLE)
        assertEquals(UpdateCheckResult.Available(UpdateInfo("v2.0.0", "notes")), result)
        coVerify(exactly = 1) { http.get("$base/latest", any(), any()) }
    }

    @Test fun betaChoosesHighestVersionFromUnsortedReleases() = runBlocking {
        coEvery { http.get("$base?per_page=100", any(), any()) } returns response(
            "[${release("v1.9.0")},${release("v1.10.0-beta.2", true)},${release("v1.10.0-beta.1", true)}]"
        )
        val result = GitHubAppVersionChecker(http, config).check("1.9.0", UpdateChannel.BETA)
        assertEquals("v1.10.0-beta.2", (result as UpdateCheckResult.Available).info.version)
    }

    @Test fun stableIgnoresPrereleaseAndNeverOffersDowngrade() = runBlocking {
        val checker = GitHubAppVersionChecker(http, config)
        coEvery { http.get(any(), any(), any()) } returns response(release("v2.0.0-beta.1", true))
        assertEquals(UpdateCheckResult.UpToDate("1.0.0"), checker.check("1.0.0", UpdateChannel.STABLE))
        coEvery { http.get(any(), any(), any()) } returns response(release("v1.0.0"))
        assertEquals(UpdateCheckResult.UpToDate("2.0.0"), checker.check("2.0.0", UpdateChannel.STABLE))
    }

    @Test fun githubHttpMalformedJsonAndMissingApkReturnErrors() = runBlocking {
        val checker = GitHubAppVersionChecker(http, config)
        for (reply in listOf(response("{}", 404), response("not-json"), response(release("v2.0.0", apk = false)))) {
            coEvery { http.get(any(), any(), any()) } returns reply
            assertTrue(checker.check("1.0.0", UpdateChannel.STABLE) is UpdateCheckResult.Error)
        }
        verify { api wasNot Called }
    }

    @Test fun githubCancellationPropagates() = runBlocking {
        coEvery { http.get(any(), any(), any()) } throws CancellationException("cancel")
        val failure = runCatching { GitHubAppVersionChecker(http, config).check("1.0.0", UpdateChannel.BETA) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun githubDownloadUsesExactTagAndConfiguredAsset() = runBlocking {
        for (tag in listOf("v2.0.0", "2.0.0")) {
            coEvery { http.get("$base/tags/$tag", any(), any()) } returns response(release(tag))
            assertEquals(assetUrl, GitHubAppDownloadUrlResolver(http, config).resolve(tag, UpdateChannel.STABLE).getOrThrow())
            coVerify(exactly = 1) { http.get("$base/tags/$tag", any(), any()) }
        }
    }

    @Test fun mirrorVersionWithoutVOnlyRetriesWithinSameGithubRepository() = runBlocking {
        val urls = mutableListOf<String>()
        coEvery { http.get(capture(urls), any(), any()) } answers {
            if (firstArg<String>().endsWith("/tags/2.0.0")) response("{}", 404)
            else response(release("v2.0.0"))
        }
        assertTrue(GitHubAppDownloadUrlResolver(http, config).resolve("2.0.0", UpdateChannel.STABLE).isSuccess)
        assertEquals(listOf("$base/tags/2.0.0", "$base/tags/v2.0.0"), urls)
    }

    @Test fun missingRidBlocksMirrorBeforeReadingCdkOrMakingRequest() = runBlocking {
        assertTrue(MirrorChyanAppVersionChecker(api, settings, config).check("1.0.0", UpdateChannel.STABLE) is UpdateCheckResult.Error)
        assertTrue(MirrorChyanAppDownloadUrlResolver(api, settings, config).resolve("2.0.0", UpdateChannel.STABLE).exceptionOrNull() is AppUpdateSourceUnavailableException)
        verify { listOf(api, settings) wasNot Called }
    }

    @Test fun explicitMirrorRidIsUsedByBothCheckAndDownload() = runBlocking {
        val mirrorConfig = AppUpdateSourceConfig("example-org", "independent-app", "OwnApk")
        every { settings.mirrorChyanCdk } returns MutableStateFlow("x".repeat(24))
        val urls = mutableListOf<String>()
        val queries = mutableListOf<Map<String, String>>()
        coEvery { api.getLatest(capture(urls), capture(queries), any()) } returns Result.success(MirrorChyanData("2.0.0", assetUrl))
        assertTrue(MirrorChyanAppVersionChecker(api, settings, mirrorConfig).check("1.0.0", UpdateChannel.BETA) is UpdateCheckResult.Available)
        assertEquals(assetUrl, MirrorChyanAppDownloadUrlResolver(api, settings, mirrorConfig).resolve("2.0.0", UpdateChannel.BETA).getOrThrow())
        assertEquals(List(2) { "https://mirrorchyan.com/api/resources/OwnApk/latest" }, urls)
        queries.forEach {
            assertEquals("independent-app", it["user_agent"])
            assertEquals("beta", it["channel"])
            assertEquals("android", it["os"])
        }
    }

    @Test fun configuredMirrorStillRequiresCdkAndReturnsFailureForEmptyDownloadUrl() = runBlocking {
        val mirrorConfig = AppUpdateSourceConfig("example-org", "independent-app", "OwnApk")
        val cdk = MutableStateFlow("")
        every { settings.mirrorChyanCdk } returns cdk
        val resolver = MirrorChyanAppDownloadUrlResolver(api, settings, mirrorConfig)
        assertTrue(resolver.resolve("2.0.0", UpdateChannel.STABLE).exceptionOrNull() is CdkRequiredException)
        verify { api wasNot Called }
        cdk.value = "x".repeat(24)
        coEvery { api.getLatest(any(), any(), any()) } returns Result.success(MirrorChyanData("2.0.0", ""))
        assertTrue(resolver.resolve("2.0.0", UpdateChannel.STABLE).isFailure)
    }

    private fun release(tag: String, prerelease: Boolean = false, apk: Boolean = true): String =
        """{"tag_name":"$tag","body":"notes","prerelease":$prerelease,"assets":[{"name":"app-${if (apk) "universal.apk" else "source.zip"}","browser_download_url":"$assetUrl"}]}"""

    private fun response(body: String, code: Int = 200): Response = Response.Builder()
        .request(Request.Builder().url("$base/latest").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()

    private val assetUrl = "https://github.com/example-org/independent-app/releases/download/v2.0.0/app-universal.apk"
}
