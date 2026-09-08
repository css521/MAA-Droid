package com.aliothmoon.maadroid.domain.service.update.resolver

import com.aliothmoon.maadroid.data.model.update.UpdateChannel

interface AppDownloadUrlResolver {
    suspend fun resolve(version: String, channel: UpdateChannel): Result<String>
}
