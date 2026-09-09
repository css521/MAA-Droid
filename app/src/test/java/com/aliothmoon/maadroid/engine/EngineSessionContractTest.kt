package com.aliothmoon.maadroid.engine

import com.aliothmoon.maadroid.remote.EngineIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * 引擎会话的契约。
 *
 * 立这些用例的直接原因：`EngineRegistry.engine(id)`、`RemoteDeviceHandle` 与
 * `ResourcePackSpec.checkCompatibility` 三者此前**都没有任何生产调用方** ——
 * 引擎、设备句柄、兼容门闸全都写好了却没串起来，于是边狱引擎跑不起来，
 * 而门闸也等于不存在（只有测试在调）。这里钉住那条链的关键约定。
 */
class EngineSessionContractTest {

    @Before
    fun setUp() = EngineSetup.install()

    // ---- 兼容门闸的调用时机 ----

    @Test
    fun gateRejectsPackNeedingUnimplementedActions() {
        val pack = EngineRegistry.allResourcePacks()
            .first { it.engineId == EngineIds.LIMBUS }

        // 清单声明需要一个本 App 未实现的动作 —— 必须在装载前拒绝，并给出可行动的提示
        val reason = pack.checkCompatibility(
            """
            {
              "schema_version": 1,
              "min_engine_version": 1,
              "required_actions": ["click", "some_future_action"]
            }
            """.trimIndent()
        )
        assertNotNull("门闸应拒绝需要未实现动作的资源包", reason)
        assertTrue("拒绝原因要能让用户知道怎么办：$reason", reason!!.contains("升级"))
    }

    @Test
    fun gatePassesForCurrentUpstreamActionSet() {
        val pack = EngineRegistry.allResourcePacks()
            .first { it.engineId == EngineIds.LIMBUS }
        // 动作全部已实现时应放行；否则用户永远装不上包
        val reason = pack.checkCompatibility(
            """{"schema_version":1,"min_engine_version":1,"required_actions":["click","key","swipe"]}"""
        )
        assertNull("动作齐备时不应被拒：$reason", reason)
    }

    @Test
    fun gateRejectsMissingManifestForLimbusButNotArknights() {
        val packs = EngineRegistry.allResourcePacks()
        val limbus = packs.first { it.engineId == EngineIds.LIMBUS }
        val arknights = packs.first { it.engineId == EngineIds.ARKNIGHTS }

        // 边狱的包由我们自己的 CI 打，必带清单；缺清单说明包不完整
        assertNotNull("边狱缺清单应拒绝", limbus.checkCompatibility(null))
        // 方舟直接用上游 MaaResource，本就没有清单，其兼容由 MaaCore 版本 stamp 保证
        assertNull("方舟无清单是正常的，不该被拒", arknights.checkCompatibility(null))
    }

    // ---- 资源目录解析：两侧必须一致 ----

    @Test
    fun packDirectoriesDoNotCollide() {
        // 下载解包与引擎装载必须落在同一个目录，否则表现为「资源缺失」且无从溯源
        val dirs = EngineRegistry.allResourcePacks().map { it.relativeRoot }
        assertEquals("资源根重复会让两个引擎互相覆盖：$dirs", dirs.size, dirs.toSet().size)
    }

    @Test
    fun limbusResourceRootIsAppReadable() {
        val limbus = EngineRegistry.allResourcePacks().first { it.engineId == EngineIds.LIMBUS }
        // 边狱引擎跑在 App 进程，要直接打开模板与 ONNX 模型。
        // relativeRoot 的基准必须是 App 数据根，而不是提权进程的 /data/local/tmp
        assertTrue(
            "边狱的资源根不应指向提权进程专属目录：${limbus.relativeRoot}",
            !limbus.relativeRoot.startsWith("/"),
        )
        assertTrue(
            "跑在 App 进程的引擎不需要投递到提权侧",
            !limbus.requiresPrivilegedDelivery,
        )
    }

    // ---- 装载失败时的版本标记 ----

    @Test
    fun invalidatingVersionForcesRedownload() {
        val limbus = EngineRegistry.allResourcePacks().first { it.engineId == EngineIds.LIMBUS }
        val dir = createTempDirectory("engine-session").toFile()
        try {
            File(dir, "manifest.json").writeText("""{"revision":"abc"}""")
            assertNull("版本必须是完整内容摘要", limbus.readInstalledVersion(dir))
            val revision = "a".repeat(64)
            File(dir, "manifest.json").writeText("""{"revision":"$revision"}""")
            assertEquals(revision, limbus.readInstalledVersion(dir))

            // 解压中途失败时靠它抹掉标记，否则下次检查认为已最新、
            // 用户会拿着一份缺文件的资源包一直跑
            limbus.invalidateInstalledVersion(dir)
            assertNull(limbus.readInstalledVersion(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 引擎实例化 ----

    @Test
    fun limbusEngineIsInstantiableAndCached() {
        val first = EngineRegistry.engine(EngineIds.LIMBUS)
        assertNotNull("边狱引擎应可实例化（曾长期是 TODO()）", first)
        // 同一引擎多次取用必须是同一实例：它持有模板 Mat 缓存与设备句柄，
        // 每次新建会让上一次的原生资源泄漏
        assertTrue(first === EngineRegistry.engine(EngineIds.LIMBUS))
    }

    @Test
    fun limbusEngineDeclaresItsDisplayRequirement() {
        val profile = EngineRegistry.provider(EngineIds.LIMBUS)!!.profile
        // 全部模板按 1280x720 截取；会话必须据此强制显示规格，否则模板全部失配
        assertEquals(1280, profile.display.width)
        assertEquals(720, profile.display.height)
    }
}
