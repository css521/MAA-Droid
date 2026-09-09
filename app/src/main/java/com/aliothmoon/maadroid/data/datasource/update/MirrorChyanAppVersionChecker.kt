package com.aliothmoon.maadroid.data.datasource.update

import com.aliothmoon.maadroid.data.api.MirrorChyanApiClient
import com.aliothmoon.maadroid.data.api.MirrorChyanBizException
import com.aliothmoon.maadroid.data.datasource.AppDownloader
import com.aliothmoon.maadroid.data.model.update.UpdateChannel
import com.aliothmoon.maadroid.data.model.update.UpdateCheckResult
import com.aliothmoon.maadroid.data.model.update.UpdateError
import com.aliothmoon.maadroid.data.model.update.UpdateInfo
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.domain.service.update.checker.AppVersionChecker
import com.aliothmoon.maadroid.constant.AppApi
import com.aliothmoon.maadroid.common.i18n.UiText
import com.aliothmoon.maadroid.data.model.update.AppUpdateSourceConfig

class MirrorChyanAppVersionChecker(
    private val apiClient: MirrorChyanApiClient,
    private val appSettings: AppSettingsManager,
    private val sources: AppUpdateSourceConfig = AppApi.APP_UPDATE_SOURCE,
) : AppVersionChecker {

    override suspend fun check(
        current: String,
        channel: UpdateChannel,
    ): UpdateCheckResult {
        sources.mirrorChyanUnavailableReason?.let {
            return UpdateCheckResult.Error(UpdateError.UnknownError(UiText.Dynamic(it)))
        }
        val cdk = appSettings.mirrorChyanCdk.value
        val query = mapOf(
            "current_version" to current,
            "user_agent" to sources.githubRepo,
            "os" to "android",
            "channel" to channel.value,
        ).let {
            if (cdk.length == 24) it + mapOf("cdk" to cdk) else it
        }
        val result = apiClient.getLatest(
            sources.mirrorChyanResourceUrl(),
            query = query,
            fetchVersion = true
        )

        return result.fold(
            onSuccess = { data ->
                val remoteVersion = data.versionName
                if (remoteVersion.isEmpty() || AppDownloader.compareVersions(
                        current,
                        remoteVersion
                    ) >= 0
                ) {
                    UpdateCheckResult.UpToDate(current)
                } else {
                    UpdateCheckResult.Available(
                        UpdateInfo(
                            version = remoteVersion,
                            releaseNote = data.releaseNote
                        )
                    )
                }
            },
            onFailure = { e ->
                when (e) {
                    is MirrorChyanBizException -> UpdateCheckResult.Error(e.toUpdateError())
                    else -> UpdateCheckResult.Error(
                        UpdateError.NetworkError(e.message)
                    )
                }
            }
        )
    }
}
