package com.maadroid.app.domain.service.update.resolver

import com.maadroid.app.data.model.update.UpdateChannel

interface AppDownloadUrlResolver {
    suspend fun resolve(version: String, channel: UpdateChannel): Result<String>
}
