package com.maadroid.app.data.datasource.update

import com.maadroid.app.R
import com.maadroid.app.common.i18n.LocalizedException
import com.maadroid.app.common.i18n.UiText
import com.maadroid.app.common.i18n.uiTextOf
import com.maadroid.app.constant.AppApi
import com.maadroid.app.data.api.HttpClientHelper
import com.maadroid.app.data.api.model.GitHubRelease
import com.maadroid.app.data.datasource.AppDownloader
import com.maadroid.app.data.model.update.AppUpdateSourceConfig
import com.maadroid.app.data.model.update.UpdateChannel
import com.maadroid.app.data.model.update.UpdateCheckResult
import com.maadroid.app.data.model.update.UpdateError
import com.maadroid.app.data.model.update.UpdateInfo
import com.maadroid.app.domain.service.update.checker.AppVersionChecker
import com.maadroid.app.utils.JsonUtils
import kotlinx.coroutines.CancellationException

class GitHubAppVersionChecker(
    private val httpClient: HttpClientHelper,
    private val sources: AppUpdateSourceConfig = AppApi.APP_UPDATE_SOURCE,
) : AppVersionChecker {
    override suspend fun check(current: String, channel: UpdateChannel): UpdateCheckResult {
        sources.disabledReason?.let {
            return UpdateCheckResult.Error(UpdateError.UnknownError(UiText.Dynamic(it)))
        }
        return try {
            val beta = channel == UpdateChannel.BETA
            val releases = httpClient.get(sources.githubReleasesUrl(beta)).use { response ->
                if (!response.isSuccessful) {
                    throw LocalizedException(uiTextOf(R.string.update_error_github_api_failed, response.code))
                }
                val body = response.body.string()
                if (beta) JsonUtils.common.decodeFromString<List<GitHubRelease>>(body)
                else listOf(JsonUtils.common.decodeFromString<GitHubRelease>(body))
            }
            val release = releases
                .filter { (beta || !it.prerelease) && it.tagName.isNotBlank() }
                .maxWithOrNull { a, b -> AppDownloader.compareVersions(a.tagName, b.tagName) }
            when {
                release == null || AppDownloader.compareVersions(current, release.tagName) >= 0 ->
                    UpdateCheckResult.UpToDate(current)
                release.assets.none { it.name.endsWith("universal.apk") } ->
                    UpdateCheckResult.Error(UpdateError.UnknownError(uiTextOf(R.string.update_error_github_no_apk)))
                else -> UpdateCheckResult.Available(UpdateInfo(release.tagName, release.body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalizedException) {
            UpdateCheckResult.Error(UpdateError.UnknownError(e.uiText))
        } catch (e: Exception) {
            UpdateCheckResult.Error(UpdateError.NetworkError(e.message))
        }
    }
}
