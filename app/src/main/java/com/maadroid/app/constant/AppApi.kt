package com.maadroid.app.constant

import com.maadroid.app.BuildConfig
import com.maadroid.app.data.model.update.AppUpdateSourceConfig

/**
 * MAA-Droid **自身**的外部地址：App 自更新、公告、文档与反馈。
 *
 * 与方舟侧的地址分开（见 `engine/arknights` 的 `ArknightsApi`）：宿主的自更新
 * 不该依赖任何游戏引擎。原先两者混在一个 `MaaApi` 里，一起搬进引擎模块就会造成
 * 这种倒挂。
 *
 * APK 自更新源必须由独立构建显式指定，未配置时禁用。
 * 公告、文档和反馈地址独立于 APK 分发配置。
 */
object AppApi {

    const val FAQ_URL = "https://github.com/css521/MAA-Droid#readme"
    const val FEEDBACK_URL = "https://github.com/css521/MAA-Droid/issues"

    /** 静态 API 基础地址 */
    const val MEOW_API_BASE = "https://raw.githubusercontent.com/css521/MAA-Droid/main/app/src/main/assets/"

    const val ANNOUNCEMENT_ZH = "${MEOW_API_BASE}announcement/announcement_zh.md"
    const val ANNOUNCEMENT_EN = "${MEOW_API_BASE}announcement/announcement_en.md"

    const val MIRROR_CHYAN_BASE = "https://mirrorchyan.com/"

    // ---- App 自更新 ----

    const val APP_GITHUB_OWNER = BuildConfig.APP_UPDATE_GITHUB_OWNER
    const val APP_GITHUB_REPO = BuildConfig.APP_UPDATE_GITHUB_REPO

    val APP_UPDATE_SOURCE = AppUpdateSourceConfig(
        githubOwner = APP_GITHUB_OWNER,
        githubRepo = APP_GITHUB_REPO,
        mirrorChyanRid = BuildConfig.APP_UPDATE_MIRROR_CHYAN_RID,
    )
}
