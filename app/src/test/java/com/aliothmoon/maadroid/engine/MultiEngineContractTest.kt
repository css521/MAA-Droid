package com.aliothmoon.maadroid.engine

import com.aliothmoon.maadroid.engine.limbus.LimbusEngineProvider
import com.aliothmoon.maadroid.engine.limbus.LimbusProfile
import com.aliothmoon.maadroid.engine.limbus.ui.LimbusUi
import com.aliothmoon.maadroid.remote.EngineIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.io.path.createTempDirectory

/**
 * 多引擎共存契约。
 *
 * 「同一应用操控两个游戏」不是靠 UI 上有两个入口，而是靠：两个游戏方案能同时注册、
 * 各自的资源包能被同一套更新服务遍历、显示规格互不干扰。这三条一旦破了，
 * 双游戏就退化成「装了两个引擎但只有一个能用」，所以用测试钉住。
 */
class MultiEngineContractTest {

    @Before
    fun setUp() = EngineSetup.install()

    @Test
    fun bothEnginesAreRegistered() {
        val ids = EngineRegistry.profiles().map { it.id }
        assertTrue("方舟未注册: $ids", EngineIds.ARKNIGHTS in ids)
        assertTrue("边狱未注册: $ids", EngineIds.LIMBUS in ids)
    }

    @Test
    fun limbusProviderComesFromTheEngineModule() {
        val provider = EngineRegistry.provider(EngineIds.LIMBUS)!!
        assertSame(LimbusEngineProvider, provider)
        assertSame(LimbusProfile, provider.profile)
        assertSame(LimbusUi, provider.ui)
    }

    @Test
    fun repeatedInstallationKeepsProviderIdentityAndRegistrationOrder() {
        val profiles = EngineRegistry.profiles()
        val providers = profiles.map { EngineRegistry.provider(it.id) }
        val packs = EngineRegistry.allResourcePacks()

        EngineSetup.install()
        EngineSetup.install()

        assertEquals(profiles, EngineRegistry.profiles())
        assertEquals(packs, EngineRegistry.allResourcePacks())
        profiles.forEachIndexed { index, profile ->
            assertSame(providers[index], EngineRegistry.provider(profile.id))
        }
    }

    @Test
    fun eachEngineDeclaresItsOwnUpstreamPack() {
        val packs = EngineRegistry.allResourcePacks()
        // 每个引擎至少一个资源包，且归属正确 —— 这是「各自跟随各自上游」的遍历入口
        assertNotNull(packs.firstOrNull { it.engineId == EngineIds.ARKNIGHTS })
        assertNotNull(packs.firstOrNull { it.engineId == EngineIds.LIMBUS })
        // packId 不得冲突，否则资源目录会互相覆盖
        assertEquals(packs.size, packs.map { it.packId }.toSet().size)
    }

    @Test
    fun resourceRootsDoNotOverlap() {
        val roots = EngineRegistry.allResourcePacks().map { it.relativeRoot }
        assertEquals("资源根目录重复会导致两个引擎互相覆盖: $roots", roots.size, roots.toSet().size)
    }

    @Test
    fun displaySpecsAreIndependent() {
        val ark = EngineRegistry.provider(EngineIds.ARKNIGHTS)!!.profile.display
        val limbus = EngineRegistry.provider(EngineIds.LIMBUS)!!.profile.display
        // 两边都是 720p 横屏，但 dpi 刻意不同：方舟 160 是手机布局（MAA 模板按此截取），
        // 边狱 320。若被统一成同一个值，其中一方的模板会全部失配
        assertEquals(1280, ark.width)
        assertEquals(1280, limbus.width)
        assertTrue("方舟与边狱的 dpi 不应被统一", ark.dpi != limbus.dpi)
    }

    @Test
    fun capabilitiesDifferSoHostCanTailorUi() {
        val ark = EngineRegistry.provider(EngineIds.ARKNIGHTS)!!.profile.capabilities
        val limbus = EngineRegistry.provider(EngineIds.LIMBUS)!!.profile.capabilities
        // 方舟有抄作业生态，边狱没有；宿主据此裁剪入口而不是显示空白页
        assertTrue(Capability.COPILOT in ark)
        assertTrue(Capability.COPILOT !in limbus)
    }

    @Test
    fun eachPackMapsItsOwnZipLayout() {
        // 两个上游的打包布局不同：MaaResource 带一层顶层目录，我们给边狱重打的包是平铺的。
        // 更新服务用 pack.mapZipEntry 落盘，所以两者必须各自认得自己的条目、
        // 且不认得对方的 —— 混用会把文件解到错误位置或整包被过滤掉
        val ark = EngineRegistry.allResourcePacks().first { it.engineId == EngineIds.ARKNIGHTS }
        val limbus = EngineRegistry.allResourcePacks().first { it.engineId == EngineIds.LIMBUS }

        assertNotNull("边狱包应认得自己的 config/task 条目", limbus.mapZipEntry("config/task/main.json"))
        assertNull("边狱包不该收下无关条目", limbus.mapZipEntry("some/other/file.txt"))
        // 目录条目一律忽略，否则会在资源目录里建出空目录
        assertNull(limbus.mapZipEntry("config/"))

        // 方舟的条目形态与边狱不同，互相不该认
        assertNull("方舟包不该认得边狱的平铺条目", ark.mapZipEntry("config/task/main.json"))
    }

    @Test
    fun privilegedDeliveryFollowsWhereTheEngineRuns() {
        val ark = EngineRegistry.allResourcePacks().first { it.engineId == EngineIds.ARKNIGHTS }
        val limbus = EngineRegistry.allResourcePacks().first { it.engineId == EngineIds.LIMBUS }
        // MaaCore 是 native、跑在提权进程，资源必须投递过去；边狱跑在 App 进程直接读。
        // 这个开关错了：方舟侧会读不到新资源，边狱侧会白留一份 zip 并触发无用的推送
        assertTrue("方舟资源需要投递到提权进程", ark.requiresPrivilegedDelivery)
        assertFalse("边狱资源不需要投递", limbus.requiresPrivilegedDelivery)
    }

    @Test
    fun invalidatingVersionForcesFullRedownload() {
        val ark = EngineRegistry.allResourcePacks().first { it.engineId == EngineIds.ARKNIGHTS }
        val limbus = EngineRegistry.allResourcePacks().first { it.engineId == EngineIds.LIMBUS }
        val dir = createTempDirectory("pack-invalidate").toFile()
        try {
            // 解压中途失败时靠它抹掉版本标记，否则下次检查会认为已最新、
            // 用户会拿着一份缺文件的资源包一直跑
            for (pack in listOf(ark, limbus)) {
                pack.invalidateInstalledVersion(dir)
                assertNull(
                    "抹除后应读不出版本: ${pack.packId}",
                    pack.readInstalledVersion(dir),
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun storedGameIdResolvesToARegisteredProfile() {
        // 正常情形：两个引擎都在
        assertEquals(EngineIds.ARKNIGHTS, EngineRegistry.resolveOrFallback(EngineIds.ARKNIGHTS)?.id)
        assertEquals(EngineIds.LIMBUS, EngineRegistry.resolveOrFallback(EngineIds.LIMBUS)?.id)
    }

    @Test
    fun unknownStoredGameIdFallsBackInsteadOfLeavingHostEmpty() {
        // 存下来的是字符串，所以这两种情形都会真实发生：
        // 用户曾选的游戏在新版被移除；旧版存的 id 在当前构建里没注册。
        // 回落到第一个可用方案，而不是让宿主拿着空引擎渲染空白页
        val fallback = EngineRegistry.resolveOrFallback("some_removed_game")
        assertNotNull("未知 id 必须回落而不是给 null", fallback)
        assertTrue(EngineRegistry.isRegistered(fallback!!.id))

        assertNotNull("null 同样回落", EngineRegistry.resolveOrFallback(null))
    }

    @Test
    fun defaultGameIsArknightsSoUpgradingUsersStayPut() {
        // 本 App 从只有方舟的 MAA-Meow 演进而来；已装用户升级后应停在原来的游戏上，
        // 而不是被切到边狱
        assertEquals(
            EngineIds.ARKNIGHTS,
            com.aliothmoon.maadroid.domain.models.AppSettings().currentGameId,
        )
    }
}
