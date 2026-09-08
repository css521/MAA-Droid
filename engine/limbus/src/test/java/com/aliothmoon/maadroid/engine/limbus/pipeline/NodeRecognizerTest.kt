package com.aliothmoon.maadroid.engine.limbus.pipeline

import com.aliothmoon.maadroid.engine.limbus.action.FakeRecognizer
import com.aliothmoon.maadroid.engine.limbus.action.nodeWith
import com.aliothmoon.maadroid.engine.limbus.recognize.Crop
import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点识别门的语义测试，对齐上游 `TaskNode.do_recognize`。
 *
 * 判定式是 `enable && (hit xor inverse)`。这三个因子任一算错都不会报错，
 * 只会让流水线走错分支 —— 例如 inverse 反了会变成「在主界面时才回主界面」。
 */
class NodeRecognizerTest {

    private fun recognizerWith(vararg hits: Pair<String, List<Match>>) =
        FakeRecognizer().apply { hits.forEach { (k, v) -> templateHits[k] = v } }

    @Test
    fun `direct 恒命中且不做任何识别`() = runTest {
        val rec = FakeRecognizer()
        val node = PipelineNode(recognition = PipelineNode.RECOGNITION_DIRECT)

        val outcome = NodeRecognizer(rec).recognize(node)

        assertTrue(outcome.hit)
        assertTrue("direct 不该触发截图识别", rec.templateCalls.isEmpty())
    }

    @Test
    fun `template_match 命中时带回坐标`() = runTest {
        val rec = recognizerWith("main_window" to listOf(Match(100, 200, 0.93)))
        val node = nodeWith("""{"template":"main_window"}""")
            .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH)

        val outcome = NodeRecognizer(rec).recognize(node)

        assertTrue(outcome.hit)
        // 坐标要带回来：该节点的 click 靠它定位（见 ActionContext.recognizeResult）
        assertEquals(listOf(Match(100, 200, 0.93)), outcome.matches)
    }

    @Test
    fun `template_match 不中时为未命中且无坐标`() = runTest {
        val node = nodeWith("""{"template":"absent"}""")
            .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH)

        val outcome = NodeRecognizer(FakeRecognizer()).recognize(node)

        assertFalse(outcome.hit)
        assertTrue(outcome.matches.isEmpty())
    }

    @Test
    fun `inverse 把不中变成命中`() = runTest {
        val node = nodeWith("""{"template":"absent"}""")
            .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH, inverse = true)

        // 「不在主界面就先回主界面」这类判断全靠它
        assertTrue(NodeRecognizer(FakeRecognizer()).recognize(node).hit)
    }

    @Test
    fun `inverse 把命中变成不中`() = runTest {
        val rec = recognizerWith("main_window" to listOf(Match(1, 1, 0.9)))
        val node = nodeWith("""{"template":"main_window"}""")
            .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH, inverse = true)

        assertFalse(NodeRecognizer(rec).recognize(node).hit)
    }

    @Test
    fun `enable 为 false 时恒不命中`() = runTest {
        val rec = recognizerWith("main_window" to listOf(Match(1, 1, 0.9)))
        val node = nodeWith("""{"template":"main_window"}""")
            .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH, enable = false)

        // check 节点会把目标节点置 false 实现「用完即弃」
        assertFalse(NodeRecognizer(rec).recognize(node).hit)
    }

    @Test
    fun `enable 为 false 时 inverse 也不能让它命中`() = runTest {
        val node = nodeWith("""{"template":"absent"}""")
            .copy(
                recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH,
                inverse = true,
                enable = false,
            )
        // 上游是 enable and (...)，禁用优先于 inverse
        assertFalse(NodeRecognizer(FakeRecognizer()).recognize(node).hit)
    }

    @Test
    fun `默认阈值为 0_85 且可被 params 覆盖`() = runTest {
        val rec = FakeRecognizer()
        var seen = -1.0
        val spy = object : com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer by rec {
            override suspend fun templateMatch(
                template: String, threshold: Double, crop: Crop?,
                maskTemplate: Crop?, screenshotScale: Double,
            ): List<Match> {
                seen = threshold
                return emptyList()
            }
        }

        NodeRecognizer(spy).recognize(
            nodeWith("""{"template":"x"}""")
                .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH)
        )
        assertEquals(0.85, seen, 1e-9)

        NodeRecognizer(spy).recognize(
            nodeWith("""{"template":"x","threshold":0.9}""")
                .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH)
        )
        // 上游流水线里唯一出现过的非默认阈值就是 0.9
        assertEquals(0.9, seen, 1e-9)
    }

    @Test
    fun `mask 被当作裁剪区域传给识别器`() = runTest {
        val rec = FakeRecognizer()
        var seen: Crop? = null
        val spy = object : com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer by rec {
            override suspend fun templateMatch(
                template: String, threshold: Double, crop: Crop?,
                maskTemplate: Crop?, screenshotScale: Double,
            ): List<Match> {
                seen = crop
                return emptyList()
            }
        }

        NodeRecognizer(spy).recognize(
            nodeWith("""{"template":"x","mask":[10,20,30,40]}""")
                .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH)
        )
        // 上游这个参数名叫 mask 但语义是裁剪，坐标会加回偏移
        assertEquals(Crop(10, 20, 30, 40), seen)
    }

    @Test
    fun `未知识别方式按不命中处理并告警而不是抛异常`() = runTest {
        val warnings = mutableListOf<String>()
        val node = PipelineNode(recognition = "some_future_recognition")

        val outcome = NodeRecognizer(FakeRecognizer()) { warnings += it }.recognize(node)

        // 上游此处 raise ValueError 会炸掉整条任务链；资源包比 App 新本该被
        // 兼容门闸拦住，真漏到运行期时让一个分支走不通远好过全盘崩掉
        assertFalse(outcome.hit)
        assertTrue(warnings.any { "未知识别方式" in it })
    }

    @Test
    fun `缺少 template 参数时不命中`() = runTest {
        val node = PipelineNode(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH)
        assertFalse(NodeRecognizer(FakeRecognizer()).recognize(node).hit)
    }
}
