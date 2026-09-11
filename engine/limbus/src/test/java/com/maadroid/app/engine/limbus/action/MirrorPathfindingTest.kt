package com.maadroid.app.engine.limbus.action

import android.view.KeyEvent
import com.maadroid.app.engine.limbus.recognize.Match
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 镜牢带权寻路。
 *
 * 打分规则照抄上游 `exec_mirror_select_next_node`：沿每条连接累加途经节点的权重，
 * 每条路径取其最高分连接，再按分数降序尝试。期望值由 Python 独立复现同一算法算出。
 */
class MirrorPathfindingTest {

    @Before
    fun setUp() {
        ActionRegistry.clearForTest()
        LimbusActions.resetForTest()
        LimbusActions.install()
    }

    private fun ctx() = TestActionContext(node = nodeWith("""{"cfg_type":"mirror"}"""))

    /** 六个节点的类型，下标即 `列*3 + 该列第几个` */
    private val nodeTypes = listOf(
        "node_event",              // 0
        "node_shop",               // 1
        "node_empty",              // 2
        "node_regular_encounter",  // 3
        "node_elite_encounter",    // 4
        "node_event",              // 5
    )

    @Test
    fun `高分路径先被尝试`() = runTest {
        // 连接名首位是路径号，其后每位是该列选第几个节点。Python 独立复现同一算法：
        //   路径0: "00"→event(20)+regular(9)=29, "01"→20+elite(2)=22  → 取 29
        //   路径1: "10"→shop(0)+regular(9)=9,    "12"→0+event(20)=20  → 取 20
        //   路径2: "20"→empty(-100)+regular(9)=-91, "22"→-100+20=-80  → 取 -80
        val c = ctx()
        c.fakeRecognizer.classifyResult = nodeTypes
        c.fakeRecognizer.multiLabelResult["mirror_path"] =
            listOf(listOf("00", "01", "10", "12", "20", "22"))
        // 都进不去，于是三条路会被依次点到，点击顺序即优先级
        ActionRegistry["mirror_select_next_node"]!!.execute(c)

        val clicked = c.fakeInput.clicks().filter { it.first == 710 }
        // Python 复现：路径0=29 > 路径1=20 > 路径2=-80
        // 路径2 首节点是 node_empty 会被跳过，故只剩两次点击
        assertEquals(listOf(710 to 110, 710 to 330), clicked)
    }

    @Test
    fun `空节点开头的路径被跳过而不浪费点击`() = runTest {
        val c = ctx()
        // 三条路径的首节点分别是 empty / event / shop
        c.fakeRecognizer.classifyResult =
            listOf("node_empty", "node_event", "node_shop", "node_event", "node_event", "node_event")
        c.fakeRecognizer.multiLabelResult["mirror_path"] = listOf(listOf("00", "10", "20"))

        ActionRegistry["mirror_select_next_node"]!!.execute(c)

        val clicked = c.fakeInput.clicks().filter { it.first == 710 }
        assertTrue("第一条路径首节点为空，不该被点", 710 to 110 !in clicked)
    }

    @Test
    fun `用户可覆盖节点权重从而改变路径优先级`() = runTest {
        val c = ctx()
        // 把商店权重抬到最高，路径1 就该排到最前
        c.fakeConfig.put("mirror", "node_score_node_shop", 999)
        c.fakeRecognizer.classifyResult = nodeTypes
        c.fakeRecognizer.multiLabelResult["mirror_path"] = listOf(listOf("00", "10"))

        ActionRegistry["mirror_select_next_node"]!!.execute(c)

        val clicked = c.fakeInput.clicks().filter { it.first == 710 }
        assertEquals("商店权重最高后路径1 应先试", 710 to 330, clicked.first())
    }

    @Test
    fun `空节点权重不可被用户配成正分`() = runTest {
        // 上游读完配置后强制把 node_empty 覆盖为 -100，否则用户配成正分会一直走死路。
        //
        // 场景刻意让空节点**不在路径首位**（首位是 shop），否则「首节点为空就跳过」
        // 那条规则会先生效、把打分的影响遮住 —— 这个用例要单独验证打分本身。
        //   路径0: "00" → shop(0) + 空节点     强制 -100 时 = -100，允许覆盖则 = 999
        //   路径1: "11" → shop(0) + focused(1) = 1
        // 故强制时顺序为 [1,0]，允许覆盖则为 [0,1]，首个点击位置不同
        val c = ctx()
        c.fakeConfig.put("mirror", "node_score_node_empty", 999)
        c.fakeRecognizer.classifyResult = listOf(
            "node_shop", "node_shop", "node_event",
            "node_empty", "node_focused_encounter", "node_shop",
        )
        c.fakeRecognizer.multiLabelResult["mirror_path"] = listOf(listOf("00", "11"))

        ActionRegistry["mirror_select_next_node"]!!.execute(c)

        val clicked = c.fakeInput.clicks().filter { it.first == 710 }
        assertEquals(
            "即便把空节点配成 999，它所在的路径也不该被优先",
            710 to 330,
            clicked.first(),
        )
    }

    @Test
    fun `连接名的每位按列偏移三个节点取用`() = runTest {
        // 连接名第 i 位对应第 i 列，取值是该列内第几个节点，故下标是 列*3 + 取值。
        // 若把每列的节点数算错（例如写成 列*2），打分会读到隔壁节点的类型 ——
        // 结果依然是一组「看起来合理」的分数，只是路径优先级整体错乱。
        //
        //   节点: [shop, shop, event, empty, event, shop]
        //   路径0: "00" → 列0取0=下标0(shop,0) + 列1取0=下标3(empty,-100) = -100
        //   路径1: "11" → 列0取1=下标1(shop,0) + 列1取1=下标4(event,20)   = 20
        //   正确顺序 [1,0]；若按 列*2 则读成下标2与3，得 [0,1]，首个点击位置相反
        val c = ctx()
        c.fakeRecognizer.classifyResult = listOf(
            "node_shop", "node_shop", "node_event",
            "node_empty", "node_event", "node_shop",
        )
        c.fakeRecognizer.multiLabelResult["mirror_path"] = listOf(listOf("00", "11"))

        ActionRegistry["mirror_select_next_node"]!!.execute(c)

        val clicked = c.fakeInput.clicks().filter { it.first == 710 }
        assertEquals("路径1 得分更高，应先试", 710 to 330, clicked.first())
    }

    @Test
    fun `分类器不可用时退回上到下依次尝试`() = runTest {
        val c = ctx()
        // 两个分类器都给不出结果（模型不在资源包内时就是这样）
        ActionRegistry["mirror_select_next_node"]!!.execute(c)

        val clicked = c.fakeInput.clicks().filter { it.first == 710 }
        assertEquals(
            "识别不可用时应仍能推进镜牢，只是不再择优",
            listOf(710 to 110, 710 to 330, 710 to 540),
            clicked,
        )
        assertTrue(c.logs.any { "依次尝试" in it })
    }

    @Test
    fun `某条路径可进入时按回车并停止尝试`() = runTest {
        val c = ctx()
        c.fakeRecognizer.classifyResult = nodeTypes
        c.fakeRecognizer.multiLabelResult["mirror_path"] = listOf(listOf("00", "10"))
        c.fakeRecognizer.onTemplate("node_enter", Match(1, 1, 0.9))

        val outcome = ActionRegistry["mirror_select_next_node"]!!.execute(c)

        assertEquals(ActionOutcome.Continue, outcome)
        assertTrue(c.fakeInput.keyPresses().contains(KeyEvent.KEYCODE_ENTER))
        // 第一条就进去了，不该再点第二条
        assertEquals(1, c.fakeInput.clicks().count { it.first == 710 })
    }

    @Test
    fun `车头偏上时先下滑再寻路`() = runTest {
        val c = ctx()
        c.fakeRecognizer.onTemplate("train_head", Match(500, 200, 0.9))

        ActionRegistry["mirror_select_next_node"]!!.execute(c)

        // 不下滑的话九宫格上沿被裁掉，节点分类会读到不完整的图
        assertTrue("应先下滑一次", c.fakeInput.events.contains("down(460,270)"))
    }
}
