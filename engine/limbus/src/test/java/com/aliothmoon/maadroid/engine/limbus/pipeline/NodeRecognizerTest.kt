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
    fun `绕过装配的未知识别明确失败且不被 inverse 翻转`() = runTest {
        for (inverse in listOf(false, true)) {
            for (enabled in listOf(false, true)) {
                val warnings = mutableListOf<String>()
                val rec = FakeRecognizer()
                val node = PipelineNode(recognition = "some_future_recognition", inverse = inverse, enable = enabled)

                val failure = runCatching { NodeRecognizer(rec) { warnings += it }.recognize(node) }.exceptionOrNull()

                assertTrue(failure is IllegalStateException)
                assertTrue(failure!!.message.orEmpty().contains("some_future_recognition"))
                assertTrue(failure.message.orEmpty().contains("请升级 App"))
                assertEquals(listOf(failure.message), warnings)
                assertTrue(rec.templateCalls.isEmpty())
            }
        }
    }

    @Test
    fun `缺少或损坏 template 时不能通过 inverse 成功`() = runTest {
        for (recognition in listOf("template_match", "color_template_match", "feature_match")) {
            for (params in listOf("{}", """{"template":[]}""", """{"template":["x",null]}""", """{"template":true}""")) {
                val rec = FakeRecognizer()
                val node = nodeWith(params).copy(recognition = recognition, inverse = true)
                val failure = runCatching { NodeRecognizer(rec).recognize(node) }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertTrue(failure!!.message.orEmpty().contains("params.template"))
                assertTrue(rec.templateCalls.isEmpty())
            }
        }
    }

    @Test fun malformedRecognitionParametersFailBeforeCallingBackend() = runTest {
        for ((params, field) in listOf(
            """{"template":"x","threshold":"NaN"}""" to "params.threshold",
            """{"template":"x","threshold":"bad"}""" to "params.threshold",
            """{"template":"x","mask":[0,0,10]}""" to "params.mask",
            """{"template":"x","mask":[0,0,10,0]}""" to "params.mask",
        )) {
            val rec = FakeRecognizer()
            val failure = runCatching {
                NodeRecognizer(rec).recognize(nodeWith(params).copy(recognition = "template_match", inverse = true))
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains(field))
            assertTrue(rec.templateCalls.isEmpty())
        }
    }

    @Test fun unsupportedNodeTypeCannotBeRecognizedAsNormal() = runTest {
        val failure = runCatching {
            NodeRecognizer(FakeRecognizer()).recognize(PipelineNode(type = "future_check"))
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("type 'future_check'"))
    }

    @Test fun colorAndFeatureModesDispatchToTheirOwnBackendWithExpectedParameters() = runTest {
        val calls = mutableListOf<Triple<String, Double, Crop?>>()
        val rec = object : com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer by FakeRecognizer() {
            override suspend fun colorTemplateMatch(template: String, threshold: Double, crop: Crop?): List<Match> {
                assertEquals("x", template)
                calls += Triple("color_template_match", threshold, crop)
                return listOf(Match(20, 30, .9))
            }
            override suspend fun featureMatch(template: String, threshold: Double, crop: Crop?): List<Match> {
                assertEquals("x", template)
                calls += Triple("feature_match", threshold, crop)
                return listOf(Match(20, 30, .9))
            }
        }
        for (mode in listOf("color_template_match", "feature_match")) {
            assertTrue(NodeRecognizer(rec).recognize(nodeWith("""{"template":"x"}""").copy(recognition = mode)).hit)
            val explicit = nodeWith("""{"template":"x","threshold":0.9,"mask":[10,20,30,40]}""").copy(recognition = mode)
            assertEquals(listOf(Match(20, 30, .9)), NodeRecognizer(rec).recognize(explicit).matches)
        }
        assertEquals(listOf(
            Triple("color_template_match", 0.7, null),
            Triple("color_template_match", 0.9, Crop(10,20,30,40)),
            Triple("feature_match", 0.7, null),
            Triple("feature_match", 0.9, Crop(10,20,30,40)),
        ), calls)
    }

    @Test fun templateArrayStillUsesFirstMatchingTemplate() = runTest {
        val rec = recognizerWith("second" to listOf(Match(3, 4, .9)))
        val node = nodeWith("""{"template":["first","second","third"]}""").copy(recognition = "template_match")
        assertTrue(NodeRecognizer(rec).recognize(node).hit)
        assertEquals(listOf("first", "second"), rec.templateCalls)
    }
}
