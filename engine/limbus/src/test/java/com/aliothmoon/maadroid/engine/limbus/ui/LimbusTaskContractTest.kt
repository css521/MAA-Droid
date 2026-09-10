package com.aliothmoon.maadroid.engine.limbus.ui

import com.aliothmoon.maadroid.engine.limbus.LimbusTask
import com.aliothmoon.maadroid.engine.limbus.config.JsonLimbusConfig
import com.aliothmoon.maadroid.engine.limbus.fixtures.LalcV500Fixtures
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务模型与面板参数的契约。
 *
 * 这里钉住的两件事都曾经错过：
 *
 * 1. **任务不是独立入口节点**。上游整条流水线只有一个入口 `main`，任务选择靠
 *    `task_center` 的 next 里那些 `*_entry` 节点的 `enable`。先前实现按
 *    `appendTask(type = 入口节点)` 处理，而 `mirror`/`exp`/`thread` 这些名字
 *    **压根不是节点**，等于任何任务都跑不起来。
 * 2. **参数是分节表**。镜牢的动作要同时读 `mirror`/`theme_pack`/`other_task` 三节，
 *    只传自己那一节会让卡包权重与 ego 开关静默失效。
 */
class LimbusTaskContractTest {

    private fun upstreamRegistry(): PipelineRegistry =
        PipelineRegistry.load(LalcV500Fixtures.taskFiles())

    // ---- 任务 ↔ 节点 ----

    @Test
    fun `每个任务都对应一个真实存在的流水线节点`() {
        val reg = upstreamRegistry()

        for (task in LimbusTask.entries) {
            assertNotNull(
                "任务 ${task.type} 声称对应节点 ${task.nodeName}，但流水线里没有它",
                reg[task.nodeName],
            )
        }
    }

    @Test
    fun `任务标识本身不是节点名`() {
        val reg = upstreamRegistry()
        // 这条正是先前 bug 的根源：把任务标识当入口节点用
        for (task in LimbusTask.entries) {
            assertNull(
                "上游并没有名为 ${task.type} 的节点，不能把它当入口",
                reg[task.type],
            )
        }
        assertNotNull("唯一入口应当是 main", reg[LimbusTask.ENTRY_NODE])
    }

    @Test
    fun `任务节点集合与上游 task_center 的分支一致`() {
        val reg = upstreamRegistry()
        val branches = reg.require("task_center").next

        // 我们声明的任务节点必须都在 task_center 的分支里，
        // 否则改 enable 也不会影响它跑不跑
        for (task in LimbusTask.entries) {
            assertTrue(
                "${task.nodeName} 不在 task_center 的 next 里：$branches",
                task.nodeName in branches,
            )
        }
        // 反过来，除了终止节点 end，task_center 的分支应当都被建模成任务 ——
        // 漏一个意味着用户无法控制它跑不跑
        val unmodelled = branches.filterNot { b ->
            b == "end" || LimbusTask.entries.any { it.nodeName == b }
        }
        assertEquals("task_center 有未建模成任务的分支: $unmodelled", emptyList<String>(), unmodelled)
    }

    // ---- enable 覆盖 ----

    @Test
    fun `未选中的任务节点会被关掉`() {
        val reg = upstreamRegistry()

        // 只选镜牢
        val overrides = LimbusTask.allNodeNames().associateWith { it == LimbusTask.MIRROR.nodeName }
        val patched = reg.withEnabled(overrides)

        assertTrue(patched.require(LimbusTask.MIRROR.nodeName).enable)
        // 不显式关掉的话上游默认全开，用户只勾镜牢却会连经验本一起跑
        assertFalse(patched.require(LimbusTask.EXP.nodeName).enable)
        assertFalse(patched.require(LimbusTask.MAIL.nodeName).enable)
    }

    @Test
    fun `覆盖不影响原注册表`() {
        val reg = upstreamRegistry()
        reg.withEnabled(mapOf(LimbusTask.EXP.nodeName to false))
        // 节点不可变是为了资源包能整体替换；一次运行的选择不该污染下一次
        assertTrue(reg.require(LimbusTask.EXP.nodeName).enable)
    }

    @Test
    fun `覆盖里的陌生节点名被忽略而不是让装配失败`() {
        val reg = upstreamRegistry()
        // 上游改了节点名时，宁可该任务不跑，也不要整条链起不来
        val patched = reg.withEnabled(mapOf("node_that_does_not_exist" to false))
        assertEquals(reg.size, patched.size)
    }

    // ---- 面板参数 → 引擎配置 ----

    @Test
    fun `面板产出的参数能被引擎按分节读出`() {
        // 面板改两个字段，模拟用户操作
        var json = TaskParams.parse("").with("exp", "exp_stage", "12")
        json = TaskParams.parse(json).with("exp", "luxcavation_mode", "skip battle")

        // 引擎侧按分节读取，键名与取值必须与动作代码比对的字符串一致
        val config = JsonLimbusConfig.fromSectionsJson(json)
        assertEquals("12", config.str("exp", "exp_stage", ""))
        assertEquals("skip battle", config.str("exp", "luxcavation_mode", ""))
    }

    @Test
    fun `改一个分节不会丢掉其它分节`() {
        var json = TaskParams.parse("").with("mirror", "mirror_mode", "hard")
        json = TaskParams.parse(json).with("theme_pack", "names", "a")
        json = TaskParams.parse(json).with("mirror", "accept_reward", false)

        val config = JsonLimbusConfig.fromSectionsJson(json)
        // 镜牢动作要同时读这几节；写第二个分节时把第一个覆盖掉，
        // 表现就是「设了却没生效」，从日志看不出来
        assertEquals("hard", config.str("mirror", "mirror_mode", ""))
        assertEquals(false, config.bool("mirror", "accept_reward", true))
        assertEquals("a", config.str("theme_pack", "names", ""))
    }

    @Test
    fun `空参数与坏参数都能安全解析`() {
        for (bad in listOf("", "   ", "not json", "[]", "null")) {
            val p = TaskParams.parse(bad)
            // 用户首次打开面板时参数是空串，面板必须能正常渲染
            assertEquals("默认值", p.str("exp", "exp_stage", "默认值"))
            assertEquals(true, p.bool("mirror", "accept_reward", true))
        }
    }

    @Test
    fun `多任务的配置分节合并后互不覆盖`() {
        val expParams = TaskParams.parse("").with("exp", "exp_stage", "07")
        val mirrorParams = TaskParams.parse("").with("mirror", "mirror_mode", "hard")

        val merged = JsonLimbusConfig(
            JsonLimbusConfig.sectionsOf(expParams) + JsonLimbusConfig.sectionsOf(mirrorParams)
        )
        assertEquals("07", merged.str("exp", "exp_stage", ""))
        assertEquals("hard", merged.str("mirror", "mirror_mode", ""))
    }

    // ---- 面板与任务的一一对应 ----

    @Test
    fun `每个任务都有面板且不重不漏`() {
        val panelTypes = LimbusUi.taskPanels.map { it.taskType }
        assertEquals(
            "面板与任务必须一一对应，否则用户看到的和引擎能跑的不一致",
            LimbusTask.entries.map { it.type },
            panelTypes,
        )
        assertEquals("面板不得重复", panelTypes.size, panelTypes.toSet().size)
    }

    @Test
    fun `面板标题都指向真实资源`() {
        for (panel in LimbusUi.taskPanels) {
            assertTrue("任务 ${panel.taskType} 的标题资源 id 非法", panel.titleRes != 0)
        }
    }
}
