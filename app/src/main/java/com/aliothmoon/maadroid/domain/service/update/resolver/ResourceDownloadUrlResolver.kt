package com.aliothmoon.maadroid.domain.service.update.resolver

interface ResourceDownloadUrlResolver {
    suspend fun resolve(currentVersion: String): Result<String>
}
