package com.aliothmoon.maadroid.data.datasource.update

import com.aliothmoon.maadroid.constant.MaaApi
import com.aliothmoon.maadroid.domain.service.update.resolver.ResourceDownloadUrlResolver

class GitHubResourceDownloadUrlResolver : ResourceDownloadUrlResolver {

    override suspend fun resolve(currentVersion: String): Result<String> {
        return Result.success(MaaApi.GITHUB_RESOURCE)
    }
}
