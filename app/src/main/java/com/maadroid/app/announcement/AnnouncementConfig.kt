package com.maadroid.app.announcement

import android.content.Context
import com.maadroid.app.data.preferences.AppSettingsManager
import java.io.IOException
import java.util.Locale
import com.maadroid.app.constant.AppApi

object AnnouncementConfig {

    private const val ASSET_DIR = "announcement"

    fun loadContent(context: Context, language: AppSettingsManager.AppLanguage): String {
        return try {
            context.assets.open("$ASSET_DIR/${fileName(language)}").bufferedReader()
                .use { it.readText() }
        } catch (_: IOException) {
            ""
        }
    }

    fun imageAssetPath(language: AppSettingsManager.AppLanguage): String =
        if (isZh(language)) "announcement/NoSkland.jpg" else "announcement/NoSklandEn.jpg"

    fun remoteUrl(language: AppSettingsManager.AppLanguage): String =
        if (isZh(language)) AppApi.ANNOUNCEMENT_ZH else AppApi.ANNOUNCEMENT_EN

    /** assets 与磁盘缓存共用 */
    fun fileName(language: AppSettingsManager.AppLanguage): String =
        if (isZh(language)) "announcement_zh.md" else "announcement_en.md"

    private fun isZh(language: AppSettingsManager.AppLanguage) = when (language) {
        AppSettingsManager.AppLanguage.ZH -> true
        AppSettingsManager.AppLanguage.EN -> false
        AppSettingsManager.AppLanguage.SYSTEM -> Locale.getDefault().language.startsWith("zh")
    }
}
