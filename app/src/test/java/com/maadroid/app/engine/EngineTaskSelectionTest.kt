package com.maadroid.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务勾选与定序。
 *
 * 这两段算错都不报错，只会让用户「勾了没跑」或「没勾却跑了」——
 * 而这是自动化工具最不能出的一类错（半夜挂机跑错任务）。
 */
class EngineTaskSelectionTest {

    private val declared = listOf(
        "mail" to true,
        "exp" to true,
        "thread" to false,
        "mirror" to true,
    )

    // ---- 默认值 ----

    @Test
    fun `什么都没配时用引擎声明的默认组合`() {
        // 首次使用：用户点开始，应当跑引擎作者认为合理的默认任务，而不是什么都不发生
        val selected = EngineTaskSelection.select(declared, EngineTaskStore.EngineTasks())
        assertEquals(listOf("mail", "exp", "mirror"), selected.map { it.first })
    }

    @Test
    fun `用户的勾选覆盖默认值`() {
        val saved = EngineTaskStore.EngineTasks(
            enabled = mapOf("mail" to false, "thread" to true),
        )
        val selected = EngineTaskSelection.select(declared, saved)
        assertEquals(listOf("exp", "thread", "mirror"), selected.map { it.first })
    }

    @Test
    fun `全部取消勾选时得到空表`() {
        val saved = EngineTaskStore.EngineTasks(
            enabled = declared.associate { it.first to false },
        )
        assertTrue(EngineTaskSelection.select(declared, saved).isEmpty())
    }

    // ---- 顺序 ----

    @Test
    fun `顺序取自引擎声明而非用户勾选的先后`() {
        // 用户先勾镜牢再勾邮件（Map 的插入顺序是 mirror, mail），
        // 但执行次序必须仍是引擎声明的 mail → mirror：
        // 面板顺序是引擎作者定的执行次序（先领邮件再刷副本），按点击顺序会让结果不可预期
        val saved = EngineTaskStore.EngineTasks(
            enabled = linkedMapOf("mirror" to true, "mail" to true, "exp" to false),
        )
        val selected = EngineTaskSelection.select(declared, saved)
        assertEquals(listOf("mail", "mirror"), selected.map { it.first })
    }

    @Test
    fun `存储里有引擎已不再声明的任务时忽略它`() {
        // 资源包热更后任务可能被上游移除；旧勾选不该让宿主去下发一个不存在的任务
        val saved = EngineTaskStore.EngineTasks(
            enabled = mapOf("removed_task" to true),
        )
        val selected = EngineTaskSelection.select(declared, saved)
        assertTrue("removed_task" !in selected.map { it.first })
    }

    // ---- 参数 ----

    @Test
    fun `参数按任务原样带出`() {
        val saved = EngineTaskStore.EngineTasks(
            enabled = mapOf("exp" to true, "mail" to false, "mirror" to false),
            params = mapOf("exp" to """{"exp":{"exp_stage":"12"}}"""),
        )
        val selected = EngineTaskSelection.select(declared, saved)
        assertEquals(1, selected.size)
        // 宿主不解释参数结构，原样存取 —— 引擎新增任务才不需要改宿主
        assertEquals("""{"exp":{"exp_stage":"12"}}""", selected.single().second)
    }

    @Test
    fun `没配过参数的任务给空串`() {
        val selected = EngineTaskSelection.select(listOf("mail" to true), EngineTaskStore.EngineTasks())
        assertEquals("mail" to "", selected.single())
    }

    // ---- 解析容错 ----

    @Test
    fun `坏数据回落到空状态而不是抛异常`() {
        // 这份数据在启动路径上：解析失败若抛异常，表现是 App 起不来。
        // 让用户重新配一次任务远好过这个
        for (bad in listOf(null, "", "   ", "not json", "[]", """{"enabled":"应该是对象"}""")) {
            val decoded = EngineTaskSelection.decode(bad)
            assertTrue("坏数据 $bad 应回落到空: $decoded", decoded.enabled.isEmpty())
        }
    }

    @Test
    fun `正常数据能往返`() {
        val original = EngineTaskStore.EngineTasks(
            enabled = mapOf("mail" to true, "exp" to false),
            params = mapOf("mail" to """{"a":1}"""),
        )
        val json = EngineTaskSelection.json.encodeToString(original)
        assertEquals(original, EngineTaskSelection.decode(json))
    }

    @Test
    fun `多出来的字段不影响解析`() {
        // 引擎升级后可能给状态加字段；旧版 App 读到不该整份丢弃
        val decoded = EngineTaskSelection.decode(
            """{"enabled":{"mail":true},"params":{},"futureField":123}"""
        )
        assertEquals(mapOf("mail" to true), decoded.enabled)
    }
}
