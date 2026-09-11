package com.maadroid.app.data.datasource.update

import com.maadroid.app.BuildConfig
import com.maadroid.app.R
import com.maadroid.app.data.api.CdkRequiredException
import com.maadroid.app.data.api.MirrorChyanApiClient
import com.maadroid.app.data.model.update.UpdateChannel
import com.maadroid.app.data.model.update.AppUpdateSourceConfig
import com.maadroid.app.data.model.update.AppUpdateSourceUnavailableException
import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.domain.service.update.resolver.AppDownloadUrlResolver
import com.maadroid.app.common.i18n.LocalizedException
import com.maadroid.app.common.i18n.uiTextOf
import com.maadroid.app.constant.AppApi

class MirrorChyanAppDownloadUrlResolver(
    private val apiClient: MirrorChyanApiClient,
    private val appSettingsManager: AppSettingsManager,
    private val sources: AppUpdateSourceConfig = AppApi.APP_UPDATE_SOURCE,
) : AppDownloadUrlResolver {

    override suspend fun resolve(version: String, channel: UpdateChannel): Result<String> {
        sources.mirrorChyanUnavailableReason?.let {
            return Result.failure(AppUpdateSourceUnavailableException(it))
        }
        val cdk = appSettingsManager.mirrorChyanCdk.value
        if (cdk.isBlank()) {
            return Result.failure(CdkRequiredException())
        }

        return apiClient.getLatest(
            sources.mirrorChyanResourceUrl(),
            query = mapOf(
                "current_version" to BuildConfig.VERSION_NAME,
                "user_agent" to sources.githubRepo,
                "os" to "android",
                "channel" to channel.value,
                "cdk" to cdk
            )
        ).mapCatching { data ->
            data.url?.takeIf { it.isNotBlank() }
                ?: throw LocalizedException(uiTextOf(R.string.update_error_empty_download_url))
        }
    }
}
