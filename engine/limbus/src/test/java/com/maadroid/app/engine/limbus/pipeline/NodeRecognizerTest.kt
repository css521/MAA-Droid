package com.maadroid.app.engine.limbus.pipeline

import com.maadroid.app.engine.limbus.action.FakeRecognizer
import com.maadroid.app.engine.limbus.action.nodeWith
import com.maadroid.app.engine.limbus.recognize.Crop
import com.maadroid.app.engine.limbus.recognize.Match
import com.maadroid.app.engine.limbus.recognize.Recognizer
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

    @Test fun mobileTeamEvidenceOnlyAuthorizesKnownTeamActionNotDetailsClicks() = runTest {
        var observations = 0
        val rec = object : Recognizer by FakeRecognizer() {
            override suspend fun observeTeamSelection(): Match {
                observations++
                return Match(1176, 520, .828)
            }
        }
        val gate = NodeRecognizer(rec)
        for (section in listOf("exp", "thread")) {
            val node = nodeWith("""{"template":"details","cfg_type":"$section"}""", "choose_team")
                .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH)
            assertTrue(gate.recognize(node).hit)
            for (other in listOf(node.copy(action = "click"), node.copy(inverse = true),
                node.copy(enable = false),
                nodeWith("""{"template":"details","cfg_type":"mirror"}""", "choose_team")
                    .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH),
                nodeWith("""{"template":"details","cfg_type":"$section","threshold":0.95}""", "choose_team")
                    .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH),
                nodeWith("""{"template":"details","cfg_type":"$section","mask":[0,0,100,100]}""", "choose_team")
                    .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH))) {
                val before = observations
                gate.recognize(other)
                assertEquals(before, observations)
            }
        }
        assertEquals(2, observations)
    }

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
        val spy = object : com.maadroid.app.engine.limbus.recognize.Recognizer by rec {
            override suspend fun templateMatch(
                template: String, threshold: Double, crop: Crop?,
                maskTemplate: Crop?, screenshotScale: Double,
                onMiss: ((Double, Int, Int) -> Unit)?,
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
        val spy = object : com.maadroid.app.engine.limbus.recognize.Recognizer by rec {
            override suspend fun templateMatch(
                template: String, threshold: Double, crop: Crop?,
                maskTemplate: Crop?, screenshotScale: Double,
                onMiss: ((Double, Int, Int) -> Unit)?,
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

                val failure = runCatching { NodeRecognizer(rec, onUnknownRecognition = { warnings += it }).recognize(node) }.exceptionOrNull()

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
        val rec = object : com.maadroid.app.engine.limbus.recognize.Recognizer by FakeRecognizer() {
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

    /**
     * 相关图峰值是「差多少才算命中」唯一的定量答案，过去在阈值判定后被丢弃。
     * 丢了它，「画面不是这一页」（峰值 0.2）和「页面对了但素材匹配不上」（峰值 0.83
     * 卡在 0.85）在日志里长得一模一样，而这两者一个该改时序、一个该重截素材。
     */
    private fun peakReporting(peak: Double?, template: String = "skip_battle") =
        object : Recognizer by FakeRecognizer() {
            override suspend fun templateMatch(
                t: String, threshold: Double, crop: Crop?,
                maskTemplate: Crop?, screenshotScale: Double,
                onMiss: ((Double, Int, Int) -> Unit)?,
            ): List<Match> {
                // 模板缺失或尺寸超出画面时没有相关图可言，此时底层不回调
                if (t == template && peak != null) onMiss?.invoke(peak, 640, 410)
                return emptyList()
            }
        }

    @Test fun `未命中时把相关图峰值和位置一并上传`() = runTest {
        val node = nodeWith("""{"template":"skip_battle"}""")
            .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH)

        val outcome = NodeRecognizer(peakReporting(0.831)).recognize(node)

        assertFalse(outcome.hit)
        val miss = requireNotNull(outcome.miss)
        assertEquals("skip_battle", miss.template)
        assertEquals(0.85, miss.threshold, 1e-9)
        assertEquals(0.831, requireNotNull(miss.peak), 1e-9)
        assertEquals(640, miss.x)
        assertEquals(410, miss.y)
        // 日志里要能一眼看出差多少，而不是只说"没命中"
        assertTrue(miss.toString(), miss.toString().contains("峰值=0.831@640,410"))
        assertTrue(miss.toString(), miss.toString().contains("阈值=0.85"))
    }

    @Test fun `没有相关图时报素材缺失而不是伪造一个峰值`() = runTest {
        val node = nodeWith("""{"template":"skip_battle"}""")
            .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH)

        val miss = requireNotNull(NodeRecognizer(peakReporting(null)).recognize(node).miss)

        assertEquals(null, miss.peak)
        assertTrue(miss.toString(), miss.toString().contains("无相关图"))
    }

    @Test fun `inverse 因未命中而命中时不带 miss`() = runTest {
        // inverse 是「识别不中才算命中」，此时那份 miss 不是失败原因，报出去会误导排查
        val node = nodeWith("""{"template":"skip_battle"}""")
            .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH, inverse = true)

        val outcome = NodeRecognizer(peakReporting(0.2)).recognize(node)

        assertTrue(outcome.hit)
        assertEquals(null, outcome.miss)
    }

    @Test fun `多模板时保留峰值最高的那次未命中`() = runTest {
        val rec = object : Recognizer by FakeRecognizer() {
            override suspend fun templateMatch(
                t: String, threshold: Double, crop: Crop?,
                maskTemplate: Crop?, screenshotScale: Double,
                onMiss: ((Double, Int, Int) -> Unit)?,
            ): List<Match> {
                onMiss?.invoke(if (t == "near") 0.842 else 0.31, 1, 2)
                return emptyList()
            }
        }
        val node = nodeWith("""{"template":["far","near","alsofar"]}""")
            .copy(recognition = PipelineNode.RECOGNITION_TEMPLATE_MATCH)

        // 最接近命中的那个才说明问题；报第一个或最后一个都会指错方向
        assertEquals("near", NodeRecognizer(rec).recognize(node).miss?.template)
    }

    @Test fun `命中时不带 miss`() = runTest {
        val node = nodeWith("""{"template":"hit"}""").copy(recognition = "template_match")
        val outcome = NodeRecognizer(recognizerWith("hit" to listOf(Match(1, 2, .9)))).recognize(node)
        assertTrue(outcome.hit)
        assertEquals(null, outcome.miss)
    }
}
