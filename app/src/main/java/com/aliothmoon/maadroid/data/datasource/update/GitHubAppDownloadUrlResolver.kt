package com.aliothmoon.maadroid.data.datasource.update

import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.data.api.HttpClientHelper
import com.aliothmoon.maadroid.data.api.model.GitHubRelease
import com.aliothmoon.maadroid.data.model.update.UpdateChannel
import com.aliothmoon.maadroid.data.model.update.AppUpdateSourceConfig
import com.aliothmoon.maadroid.domain.service.update.resolver.AppDownloadUrlResolver
import com.aliothmoon.maadroid.utils.JsonUtils
import com.aliothmoon.maadroid.common.i18n.LocalizedException
import com.aliothmoon.maadroid.common.i18n.uiTextOf
import timber.log.Timber
import com.aliothmoon.maadroid.constant.AppApi

class GitHubAppDownloadUrlResolver(
    private val httpClient: HttpClientHelper,
    private val sources: AppUpdateSourceConfig = AppApi.APP_UPDATE_SOURCE,
) : AppDownloadUrlResolver {

    private val json = JsonUtils.common

    override suspend fun resolve(version: String, channel: UpdateChannel): Result<String> {
        return runCatching {
            sources.requireEnabled()
            // GitHub 检查保留原 tag；MirrorChyan 可能返回不带 v 的版本号。
            val tags = if (version.startsWith("v", ignoreCase = true)) listOf(version)
                else listOf(version, "v$version")
            for ((index, tag) in tags.withIndex()) {
                httpClient.get(sources.githubReleaseByTag(tag)).use { response ->
                    if (response.code == 404 && index < tags.lastIndex) return@use
                    if (!response.isSuccessful) {
                        throw LocalizedException(
                            uiTextOf(R.string.update_error_github_api_failed, response.code)
                        )
                    }
                    val release = json.decodeFromString<GitHubRelease>(response.body.string())
                    val apkAsset = release.assets.firstOrNull { it.name.endsWith("universal.apk") }
                        ?: throw LocalizedException(uiTextOf(R.string.update_error_github_no_apk))
                    return@runCatching apkAsset.browserDownloadUrl
                }
            }
            throw LocalizedException(uiTextOf(R.string.update_error_github_no_apk))
        }.onFailure { e ->
            Timber.e(e, "GitHub 获取 Release 失败: $version")
        }
    }
}
