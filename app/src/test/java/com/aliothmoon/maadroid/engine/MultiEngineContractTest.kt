package com.aliothmoon.maadroid.engine

import com.aliothmoon.maadroid.remote.EngineIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}
