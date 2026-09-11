package com.maadroid.app

import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.data.resource.ResourceDataManager
import com.maadroid.app.engine.arknights.enums.UiUsageConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.maadroid.app.domain.service.toDisplayLanguageCode

class RecruitAndInfrastI18nContractTest {

    @Test
    fun droneUsageOptions_storeOnlyStableValues() {
        assertTrue(
            "Drone usage options should store raw values instead of localized labels",
            UiUsageConstants.droneUsageValues.none { value -> value.any { it.code > 127 } }
        )
    }

    /**
     * 语言枚举 → 资源语言代码。
     *
     * 转换函数已从 `ResourceDataManager` 移到宿主侧（`toDisplayLanguageCode`）——
     * 前者属方舟引擎，不该认识宿主的 `AppSettingsManager.AppLanguage`；引擎只收字符串。
     * 映射本身不变，故断言原样保留。
     */
    @Test
    fun displayLanguageCode_mapsSupportedAppLanguages() {
        assertEquals("zh-cn", AppSettingsManager.AppLanguage.ZH.toDisplayLanguageCode())
        assertEquals("en-us", AppSettingsManager.AppLanguage.EN.toDisplayLanguageCode())
        assertEquals("zh-cn", AppSettingsManager.AppLanguage.SYSTEM.toDisplayLanguageCode())
    }
}
