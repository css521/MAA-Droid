package com.aliothmoon.maadroid.domain.service.update.checker

import com.aliothmoon.maadroid.common.i18n.UiText
import com.aliothmoon.maadroid.constant.AppApi
import com.aliothmoon.maadroid.data.model.update.AppUpdateSourceConfig
import com.aliothmoon.maadroid.data.model.update.UpdateChannel
import com.aliothmoon.maadroid.data.model.update.UpdateCheckResult
import com.aliothmoon.maadroid.data.model.update.UpdateError

/** 保留现有 MirrorChyan 注入；独立构建不配置 RID 时直接查自己的 GitHub。 */
class ConfiguredAppVersionChecker(
    private val github: AppVersionChecker,
    private val mirrorChyan: AppVersionChecker,
    private val sources: AppUpdateSourceConfig = AppApi.APP_UPDATE_SOURCE,
) : AppVersionChecker {
    override suspend fun check(current: String, channel: UpdateChannel): UpdateCheckResult {
        sources.disabledReason?.let {
            return UpdateCheckResult.Error(UpdateError.UnknownError(UiText.Dynamic(it)))
        }
        return (if (sources.usesMirrorChyan) mirrorChyan else github).check(current, channel)
    }
}
