package com.maadroid.app.data.datasource.update

import com.maadroid.app.R
import com.maadroid.app.data.api.CdkRequiredException
import com.maadroid.app.data.api.MirrorChyanApiClient
import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.domain.service.update.resolver.ResourceDownloadUrlResolver
import com.maadroid.app.common.i18n.LocalizedException
import com.maadroid.app.common.i18n.uiTextOf
import com.maadroid.app.engine.arknights.constant.ArknightsApi

class MirrorChyanResourceDownloadUrlResolver(
    private val apiClient: MirrorChyanApiClient,
    private val appSettingsManager: AppSettingsManager
) : ResourceDownloadUrlResolver {

    override suspend fun resolve(currentVersion: String): Result<String> {
        val cdk = appSettingsManager.mirrorChyanCdk.value
        if (cdk.isBlank()) {
            return Result.failure(CdkRequiredException())
        }

        return apiClient.getLatest(
            ArknightsApi.MIRROR_CHYAN_RESOURCE,
            query = mapOf(
                "current_version" to currentVersion,
                "user_agent" to "MAA-Meow",
                "cdk" to cdk
            )
        ).map { data ->
            data.url ?: throw LocalizedException(uiTextOf(R.string.update_error_empty_download_url))
        }
    }
}
