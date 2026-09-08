package com.aliothmoon.maadroid.engine.limbus

import com.aliothmoon.maadroid.engine.limbus.action.ActionBackend
import com.aliothmoon.maadroid.engine.limbus.action.ActionContext
import com.aliothmoon.maadroid.engine.limbus.action.ActionOutcome
import com.aliothmoon.maadroid.engine.limbus.action.ActionRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 资源包兼容门闸的契约测试。
 *
 * 这是「跟随上游更新」这件事的安全阀：上游改流程/图/阈值应当无感跟随，
 * 但上游若引入了本 App 未实现的动作，必须在装载前拒绝并提示升级，
 * 而不是运行到某个节点才崩。门闸失效会让用户在挂机中途遇到不可解释的失败，
 * 所以用测试钉住每一条拒绝理由。
 */
class LimbusResourcePackTest {

    private val noop = object : ActionBackend {
        override suspend fun execute(ctx: ActionContext) = ActionOutcome.Continue
    }

    @Before
    fun setUp() {
        ActionRegistry.clearForTest()
        listOf("click", "key", "swipe", "empty").forEach { ActionRegistry.register(it, noop) }
    }

    @After
    fun tearDown() = ActionRegistry.clearForTest()

    private fun manifest(
        schema: Int = LimbusResourcePack.SUPPORTED_SCHEMA,
        minEngine: Int = LimbusResourcePack.ENGINE_VERSION,
        actions: List<String> = listOf("click", "key"),
    ) = """
        {
          "schema_version": $schema,
          "engine": "limbus",
          "revision": "abc123",
          "min_engine_version": $minEngine,
          "required_actions": [${actions.joinToString(",") { "\"$it\"" }}]
        }
    """.trimIndent()

    @Test
    fun acceptsPackWhoseActionsAreAllImplemented() {
        assertNull(LimbusResourcePack.checkCompatibility(manifest()))
    }

    @Test
    fun rejectsPackRequiringUnimplementedAction() {
        val reason = LimbusResourcePack.checkCompatibility(
            manifest(actions = listOf("click", "mirror_choose_star", "battle_winrate"))
        )
        assertNotNull("需要未实现动作的包必须被拒绝", reason)
        // 提示里要点出缺哪些，否则用户无从判断
        assertTrue(reason!!.contains("mirror_choose_star"))
        assertTrue(reason.contains("升级 App"))
    }

    @Test
    fun rejectsFutureSchemaVersion() {
        val reason = LimbusResourcePack.checkCompatibility(manifest(schema = 99))
        assertNotNull("清单协议版本不匹配必须拒绝", reason)
        assertTrue(reason!!.contains("99"))
    }

    @Test
    fun rejectsPackRequiringNewerEngine() {
        val reason = LimbusResourcePack.checkCompatibility(
            manifest(minEngine = LimbusResourcePack.ENGINE_VERSION + 1)
        )
        assertNotNull("要求更高引擎版本的包必须拒绝", reason)
    }

    @Test
    fun rejectsMissingOrBrokenManifest() {
        assertNotNull(LimbusResourcePack.checkCompatibility(null))
        assertNotNull(LimbusResourcePack.checkCompatibility("not json"))
    }

    @Test
    fun zipEntryMappingKeepsOnlyResourceDirs() {
        // 我们自己的包是平铺布局，四份资源原样落盘
        listOf("config/task/main.json", "img/zh/battle/x.png", "ai/model/skill_icon/best_model.onnx")
            .forEach { assertTrue(it, LimbusResourcePack.mapZipEntry(it) == it) }
        // 目录条目与包外文件必须被忽略，避免热更包塞进无关内容
        listOf("config/", "README.md", "../evil.so", "lalc_backend/main.py")
            .forEach { assertNull(it, LimbusResourcePack.mapZipEntry(it)) }
    }
}
