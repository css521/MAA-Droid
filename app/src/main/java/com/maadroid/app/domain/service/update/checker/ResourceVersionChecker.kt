package com.maadroid.app.domain.service.update.checker

import com.maadroid.app.data.model.update.UpdateCheckResult

interface ResourceVersionChecker {
    suspend fun check(currentVersion: String): UpdateCheckResult
}
