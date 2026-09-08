package com.aliothmoon.maadroid

import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.data.resource.ResourceDataManager
import com.aliothmoon.maadroid.domain.enums.UiUsageConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecruitAndInfrastI18nContractTest {

    @Test
    fun droneUsageOptions_storeOnlyStableValues() {
        assertTrue(
            "Drone usage options should store raw values instead of localized labels",
            UiUsageConstants.droneUsageValues.none { value -> value.any { it.code > 127 } }
        )
    }

    @Test
    fun displayLanguageCode_mapsSupportedAppLanguages() {
        assertEquals("zh-cn", ResourceDataManager.displayLanguageCode(AppSettingsManager.AppLanguage.ZH))
        assertEquals("en-us", ResourceDataManager.displayLanguageCode(AppSettingsManager.AppLanguage.EN))
        assertEquals(
            "zh-cn",
            ResourceDataManager.displayLanguageCode(AppSettingsManager.AppLanguage.SYSTEM)
        )
    }
}
