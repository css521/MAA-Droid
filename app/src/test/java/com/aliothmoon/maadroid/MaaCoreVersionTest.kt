package com.aliothmoon.maadroid

import com.aliothmoon.maadroid.data.resource.MaaCoreVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaaCoreVersionTest {

    @Test
    fun meetsMinimumRequired_basicComparison() {
        assertTrue(MaaCoreVersion.meetsMinimumRequired("v6.0.0", current = "v6.12.0"))
        assertFalse(MaaCoreVersion.meetsMinimumRequired("v6.12.0", current = "v6.0.0"))
        assertFalse(MaaCoreVersion.meetsMinimumRequired("v7.0.0", current = "v6.99.99"))
        assertFalse(MaaCoreVersion.meetsMinimumRequired("v6.12.1", current = "v6.12.0"))
    }

    @Test
    fun meetsMinimumRequired_equalVersions() {
        assertTrue(MaaCoreVersion.meetsMinimumRequired("v6.12.0-beta.2", current = "v6.12.0-beta.2"))
        assertTrue(MaaCoreVersion.meetsMinimumRequired("6.12.0", current = "v6.12.0"))
    }

    @Test
    fun meetsMinimumRequired_releaseIsNewerThanPreRelease() {
        assertTrue(MaaCoreVersion.meetsMinimumRequired("v6.12.0-beta.2", current = "v6.12.0"))
        assertFalse(MaaCoreVersion.meetsMinimumRequired("v6.12.0", current = "v6.12.0-beta.2"))
    }

    @Test
    fun meetsMinimumRequired_numericPreReleaseSegments_compareNumerically() {
        // 字典序会得出 beta.10 < beta.2，必须按数值比较
        assertTrue(MaaCoreVersion.meetsMinimumRequired("v6.12.0-beta.2", current = "v6.12.0-beta.10"))
        assertFalse(MaaCoreVersion.meetsMinimumRequired("v6.12.0-beta.10", current = "v6.12.0-beta.2"))
    }

    @Test
    fun meetsMinimumRequired_alphaIsOlderThanBeta() {
        assertFalse(MaaCoreVersion.meetsMinimumRequired("v6.12.0-beta.1", current = "v6.12.0-alpha.5"))
        assertTrue(MaaCoreVersion.meetsMinimumRequired("v6.12.0-alpha.5", current = "v6.12.0-beta.1"))
    }

    @Test
    fun meetsMinimumRequired_shorterPreReleaseIsOlder() {
        assertFalse(MaaCoreVersion.meetsMinimumRequired("v6.12.0-beta.1", current = "v6.12.0-beta"))
        assertTrue(MaaCoreVersion.meetsMinimumRequired("v6.12.0-beta", current = "v6.12.0-beta.1"))
    }

    @Test
    fun meetsMinimumRequired_noRequirement_passes() {
        assertTrue(MaaCoreVersion.meetsMinimumRequired(null, current = "v6.0.0"))
        assertTrue(MaaCoreVersion.meetsMinimumRequired("", current = "v6.0.0"))
        assertTrue(MaaCoreVersion.meetsMinimumRequired("  ", current = "v6.0.0"))
    }

    @Test
    fun meetsMinimumRequired_unknownCurrentVersion_passes() {
        // 本地未运行 setup_maa_core.py 时 BuildConfig.MAA_CORE_VERSION 为空串，宽松放行
        assertTrue(MaaCoreVersion.meetsMinimumRequired("v99.0.0", current = ""))
        assertTrue(MaaCoreVersion.meetsMinimumRequired("v99.0.0", current = "  "))
    }

    @Test
    fun meetsMinimumRequired_malformedOrPartialVersions_areLenient() {
        // 畸形版本号解析为 0.0.0，不抛异常
        assertTrue(MaaCoreVersion.meetsMinimumRequired("garbage", current = "v6.0.0"))
        // 缺失 patch 位补 0
        assertTrue(MaaCoreVersion.meetsMinimumRequired("v6.12", current = "v6.12.0"))
    }
}
