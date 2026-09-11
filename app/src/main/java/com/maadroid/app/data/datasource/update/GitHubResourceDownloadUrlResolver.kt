package com.maadroid.app.data.datasource.update

import com.maadroid.app.domain.service.update.resolver.ResourceDownloadUrlResolver
import com.maadroid.app.engine.arknights.constant.ArknightsApi

class GitHubResourceDownloadUrlResolver : ResourceDownloadUrlResolver {

    override suspend fun resolve(currentVersion: String): Result<String> {
        return Result.success(ArknightsApi.GITHUB_RESOURCE)
    }
}
