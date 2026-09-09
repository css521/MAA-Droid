package com.aliothmoon.maadroid.data.datasource.update

import com.aliothmoon.maadroid.BuildConfig
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.data.api.CdkRequiredException
import com.aliothmoon.maadroid.data.api.MirrorChyanApiClient
import com.aliothmoon.maadroid.data.model.update.UpdateChannel
import com.aliothmoon.maadroid.data.model.update.AppUpdateSourceConfig
import com.aliothmoon.maadroid.data.model.update.AppUpdateSourceUnavailableException
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.domain.service.update.resolver.AppDownloadUrlResolver
import com.aliothmoon.maadroid.common.i18n.LocalizedException
import com.aliothmoon.maadroid.common.i18n.uiTextOf
import com.aliothmoon.maadroid.constant.AppApi

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
