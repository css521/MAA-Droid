package com.maadroid.app.data.datasource.update

import com.maadroid.app.data.api.MirrorChyanApiClient
import com.maadroid.app.data.api.MirrorChyanBizException
import com.maadroid.app.data.datasource.ResourceDownloader
import com.maadroid.app.data.model.update.UpdateCheckResult
import com.maadroid.app.data.model.update.UpdateError
import com.maadroid.app.data.model.update.UpdateInfo
import com.maadroid.app.domain.service.update.checker.ResourceVersionChecker
import com.maadroid.app.engine.arknights.constant.ArknightsApi

class MirrorChyanResourceVersionChecker(
    private val apiClient: MirrorChyanApiClient
) : ResourceVersionChecker {

    override suspend fun check(currentVersion: String): UpdateCheckResult {
        val result = apiClient.getLatest(
            ArknightsApi.MIRROR_CHYAN_RESOURCE,
            query = mapOf(
                "current_version" to currentVersion,
                "user_agent" to "MAA-Droid"
            )
        )

        return result.fold(
            onSuccess = { data ->
                val remoteVersion = data.versionName
                if (remoteVersion.isEmpty() || ResourceDownloader.compareVersions(
                        currentVersion,
                        remoteVersion
                    ) >= 0
                ) {
                    UpdateCheckResult.UpToDate(currentVersion)
                } else {
                    UpdateCheckResult.Available(
                        UpdateInfo(
                            version = ResourceDownloader.formatVersionForDisplay(remoteVersion),
                            releaseNote = data.releaseNote
                        )
                    )
                }
            },
            onFailure = { e ->
                when (e) {
                    is MirrorChyanBizException -> UpdateCheckResult.Error(e.toUpdateError())
                    else -> UpdateCheckResult.Error(UpdateError.NetworkError(e.message))
                }
            }
        )
    }
}
