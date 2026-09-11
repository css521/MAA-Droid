package com.maadroid.app.domain.service.update.checker

import com.maadroid.app.common.i18n.UiText
import com.maadroid.app.constant.AppApi
import com.maadroid.app.data.model.update.AppUpdateSourceConfig
import com.maadroid.app.data.model.update.UpdateChannel
import com.maadroid.app.data.model.update.UpdateCheckResult
import com.maadroid.app.data.model.update.UpdateError

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
