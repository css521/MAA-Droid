package com.aliothmoon.maadroid.data.datasource.update

import com.aliothmoon.maadroid.domain.service.update.resolver.ResourceDownloadUrlResolver
import com.aliothmoon.maadroid.engine.arknights.constant.ArknightsApi

class GitHubResourceDownloadUrlResolver : ResourceDownloadUrlResolver {

    override suspend fun resolve(currentVersion: String): Result<String> {
        return Result.success(ArknightsApi.GITHUB_RESOURCE)
    }
}
