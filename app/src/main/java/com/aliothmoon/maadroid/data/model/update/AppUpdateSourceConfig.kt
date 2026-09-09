package com.aliothmoon.maadroid.data.model.update

import java.net.URLEncoder

/** APK 专用配置，不依赖 Android、BuildConfig 或任何游戏资源配置。 */
class AppUpdateSourceConfig(
    githubOwner: String = "",
    githubRepo: String = "",
    mirrorChyanRid: String = "",
) {
    val githubOwner = githubOwner.trim()
    val githubRepo = githubRepo.trim()
    val mirrorChyanRid = mirrorChyanRid.trim()

    val disabledReason: String? = when {
        this.githubOwner.isEmpty() || this.githubRepo.isEmpty() ->
            "应用自动更新未配置，请通过此安装包发布者提供的渠道获取新版。"
        !OWNER.matches(this.githubOwner) || !REPO.matches(this.githubRepo) ||
            this.githubRepo in setOf(".", "..") ->
            "应用更新地址无效，请联系此安装包的发布者。"
        else -> null
    }

    val mirrorChyanUnavailableReason: String? = disabledReason ?: when {
        this.mirrorChyanRid.isEmpty() ->
            "此安装包未启用 MirrorChyan 应用更新，请选择 GitHub 下载。"
        !RID.matches(this.mirrorChyanRid) || this.mirrorChyanRid in setOf(".", "..") ->
            "此安装包的 MirrorChyan 更新配置无效，请选择 GitHub 下载或联系发布者。"
        else -> null
    }

    val usesMirrorChyan: Boolean get() = mirrorChyanUnavailableReason == null

    fun requireEnabled() {
        disabledReason?.let { throw AppUpdateSourceUnavailableException(it) }
    }

    fun githubReleasesUrl(includePrereleases: Boolean): String {
        requireEnabled()
        return "$githubApi/releases" + if (includePrereleases) "?per_page=100" else "/latest"
    }

    fun githubReleaseByTag(tag: String): String {
        requireEnabled()
        require(tag.isNotBlank()) { "APK release tag must not be blank" }
        val encoded = URLEncoder.encode(tag, "UTF-8").replace("+", "%20")
        return "$githubApi/releases/tags/$encoded"
    }

    fun mirrorChyanResourceUrl(): String {
        mirrorChyanUnavailableReason?.let { throw AppUpdateSourceUnavailableException(it) }
        return "https://mirrorchyan.com/api/resources/$mirrorChyanRid/latest"
    }

    private val githubApi get() = "https://api.github.com/repos/$githubOwner/$githubRepo"

    private companion object {
        val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        val REPO = Regex("[A-Za-z0-9_.-]{1,100}")
        val RID = Regex("[A-Za-z0-9_.-]+")
    }
}

class AppUpdateSourceUnavailableException(message: String) : IllegalStateException(message)
