package com.aliothmoon.maadroid.data.datasource.update

import com.aliothmoon.maadroid.constant.MaaApi
import com.aliothmoon.maadroid.data.api.MirrorChyanApiClient
import com.aliothmoon.maadroid.data.api.MirrorChyanBizException
import com.aliothmoon.maadroid.data.datasource.ResourceDownloader
import com.aliothmoon.maadroid.data.model.update.UpdateCheckResult
import com.aliothmoon.maadroid.data.model.update.UpdateError
import com.aliothmoon.maadroid.data.model.update.UpdateInfo
import com.aliothmoon.maadroid.domain.service.update.checker.ResourceVersionChecker

class MirrorChyanResourceVersionChecker(
    private val apiClient: MirrorChyanApiClient
) : ResourceVersionChecker {

    override suspend fun check(currentVersion: String): UpdateCheckResult {
        val result = apiClient.getLatest(
            MaaApi.MIRROR_CHYAN_RESOURCE,
            query = mapOf(
                "current_version" to currentVersion,
                "user_agent" to "MAA-Meow"
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
