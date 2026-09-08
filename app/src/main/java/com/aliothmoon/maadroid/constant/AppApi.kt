package com.aliothmoon.maadroid.constant

/**
 * MAA-Droid **自身**的外部地址：App 自更新、公告、文档与反馈。
 *
 * 与方舟侧的地址分开（见 `engine/arknights` 的 `ArknightsApi`）：宿主的自更新
 * 不该依赖任何游戏引擎。原先两者混在一个 `MaaApi` 里，一起搬进引擎模块就会造成
 * 这种倒挂。
 *
 * 其中的 `MAA-Meow` 仓库名与 `maameow.com` 域名**不随项目改名而变** ——
 * 老版本 App 靠它们检查更新、拉公告，改了会让已装用户再也收不到更新。
 */
object AppApi {

    const val FAQ_URL = "https://docs.maameow.com/faq/getting-started/"
    const val FEEDBACK_URL = "https://github.com/Aliothmoon/MAA-Meow/issues"

    /** 静态 API 基础地址 */
    const val MEOW_API_BASE = "https://maameow.com/api/"

    const val ANNOUNCEMENT_ZH = "${MEOW_API_BASE}announcement/announcement_zh.md"
    const val ANNOUNCEMENT_EN = "${MEOW_API_BASE}announcement/announcement_en.md"

    const val MIRROR_CHYAN_BASE = "https://mirrorchyan.com/"

    // ---- App 自更新 ----

    const val APP_GITHUB_OWNER = "Aliothmoon"

    /** 仓库名保持 MAA-Meow：老版本按它查更新 */
    const val APP_GITHUB_REPO = "MAA-Meow"

    const val APP_GITHUB_RELEASES =
        "https://api.github.com/repos/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/releases?per_page=1"

    const val APP_GITHUB_RELEASES_BETA =
        "https://api.github.com/repos/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/releases?per_page=5"

    fun appGitHubReleaseByTag(tag: String): String =
        "https://api.github.com/repos/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/releases/tags/$tag"

    /** Mirror酱 的 App 更新源，rid 同样保持旧名 */
    const val MIRROR_CHYAN_APP_RESOURCE = "https://mirrorchyan.com/api/resources/MAA-Meow/latest"
}
