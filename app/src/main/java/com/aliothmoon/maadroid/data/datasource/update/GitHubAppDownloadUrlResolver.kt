package com.aliothmoon.maadroid.data.datasource.update

import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.constant.MaaApi
import com.aliothmoon.maadroid.data.api.HttpClientHelper
import com.aliothmoon.maadroid.data.api.model.GitHubRelease
import com.aliothmoon.maadroid.data.model.update.UpdateChannel
import com.aliothmoon.maadroid.domain.service.update.resolver.AppDownloadUrlResolver
import com.aliothmoon.maadroid.utils.JsonUtils
import com.aliothmoon.maadroid.utils.i18n.LocalizedException
import com.aliothmoon.maadroid.utils.i18n.uiTextOf
import timber.log.Timber

class GitHubAppDownloadUrlResolver(
    private val httpClient: HttpClientHelper
) : AppDownloadUrlResolver {

    private val json = JsonUtils.common

    override suspend fun resolve(version: String, channel: UpdateChannel): Result<String> {
        return runCatching {
            val tag = if (version.startsWith("v", ignoreCase = true)) version else "v$version"
            val response = httpClient.get(MaaApi.appGitHubReleaseByTag(tag))

            if (!response.isSuccessful) {
                throw LocalizedException(
                    uiTextOf(R.string.update_error_github_api_failed, response.code)
                )
            }

            val release = json.decodeFromString<GitHubRelease>(response.body.string())

            val apkAsset = release.assets.firstOrNull { it.name.endsWith("universal.apk") }
                ?: throw LocalizedException(uiTextOf(R.string.update_error_github_no_apk))

            apkAsset.browserDownloadUrl
        }.onFailure { e ->
            Timber.e(e, "GitHub 获取 Release 失败: $version")
        }
    }
}
