package com.aliothmoon.maadroid.engine.limbus.pipeline

import com.aliothmoon.maadroid.engine.limbus.fixtures.LalcV500Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * 流水线装配的契约测试。
 *
 * 分两类：
 * - 合成用例：不依赖外部仓库，保证装配规则（重名、断引用、error 节点 interrupt 清空、
 *   默认 interrupt）在 CI 上始终被校验。
 * - 真实上游用例：读取测试资源里固定的 LALC v5.0.0 config/task JSON，
 *   验证装配与真实数据的契约；缺文件或 hash 不符直接失败。
 */
class PipelineRegistryTest {

    // ---------- 合成用例 ----------

    @Test
    fun defaultInterruptIsErrorHandler() {
        val reg = PipelineRegistry.load(
            mapOf(
                "main.json" to """
                    {
                      "main": { "action": "empty", "next": ["leaf"] },
                      "leaf": { "action": "empty" },
                      "empty": { "action": "empty" },
                      "error_handler": { "action": "empty" }
                    }
                """.trimIndent(),
            )
        )
        // 未显式声明 interrupt 的节点应拿到上游的缺省值
        assertEquals(listOf("error_handler"), reg.interruptsOf("main"))
    }

    @Test
    fun errorFileNodesGetEmptyInterrupt() {
        val reg = PipelineRegistry.load(
            mapOf(
                "main.json" to """{ "empty": { "action": "empty" } }""",
                "error.json" to """{ "error_handler": { "action": "empty" } }""",
            )
        )
        // error.json 的节点必须清空 interrupt，否则异常处理会自我递归
        assertTrue(reg.interruptsOf("error_handler").isEmpty())
    }

    @Test
    fun danglingReferenceIsRejected() {
        val ex = runCatching {
            PipelineRegistry.load(
                mapOf(
                    "main.json" to """
                        {
                          "empty": { "action": "empty" },
                          "error_handler": { "action": "empty" },
                          "main": { "action": "empty", "next": ["nope"] }
                        }
                    """.trimIndent(),
                )
            )
        }.exceptionOrNull()
        assertNotNull("断引用必须让装配失败，而不是产出半可用流水线", ex)
        assertTrue(ex!!.message!!.contains("nope"))
    }

    @Test
    fun duplicateNodeNameIsRejected() {
        val ex = runCatching {
            PipelineRegistry.load(
                mapOf(
                    "a.json" to """{ "empty": { "action": "empty" } }""",
                    "b.json" to """{ "empty": { "action": "empty" } }""",
                )
            )
        }.exceptionOrNull()
        assertNotNull("重名节点必须被拒绝", ex)
    }

    private fun loadGate(nodeJson: String): PipelineRegistry = PipelineRegistry.load(
        mapOf("gate.json" to """{
            "empty": {"interrupt": []},
            "error_handler": {"interrupt": []},
            "gate": $nodeJson
        }"""),
    )

    private fun rejectsGate(nodeJson: String, field: String): String {
        val error = assertThrows(IllegalStateException::class.java) { loadGate(nodeJson) }
        val message = error.message.orEmpty()
        assertTrue(message, message.contains("节点 gate（gate.json）"))
        assertTrue(message, message.contains(field))
        return message
    }

    /**
     * 文字判据不需要 template，但**必须**有非空 text ——
     * 空串会让 findText 恒返回命中，节点变成无条件通过，比识别不中更危险。
     */
    @Test fun `ocr 判据要求非空 text 而不要求 template`() {
        val ok = loadGate("""{"recognition":"ocr","params":{"text":"Details","mask":[850,100,200,60]}}""")
        assertEquals("ocr", ok.require("gate").recognition)
        for (bad in listOf(
            """{"recognition":"ocr","params":{}}""",
            """{"recognition":"ocr","params":{"text":""}}""",
            """{"recognition":"ocr","params":{"text":"  "}}""",
            """{"recognition":"ocr","params":{"text":123}}""",
        )) {
            assertTrue(bad, rejectsGate(bad, "params.text").contains("非空字符串"))
        }
    }

    /**
     * 补丁是**字段级合并**而不是整节点替换：只覆盖具名字段，`params` 深合并，
     * 其余（next / pre_delay / action…）仍跟上游走。这样上游后续改流程依然生效。
     */
    @Test fun `补丁只覆盖具名字段并深合并 params`() {
        val reg = PipelineRegistry.load(
            mapOf("main.json" to """{
                "empty": {"action":"empty"},
                "error_handler": {"interrupt": []},
                "gate": {"action":"empty","recognition":"template_match",
                         "params":{"template":"details","pre_delay":2,"post_delay":3},
                         "next":["empty"]}
            }"""),
            patches = mapOf(
                "gate" to Json.parseToJsonElement(
                    """{"recognition":"ocr","params":{"text":"Details","mask":[880,100,120,50]}}"""
                ).jsonObject
            ),
        )
        val gate = reg.require("gate")
        assertEquals("ocr", gate.recognition)
        assertEquals("Details", gate.str("text"))
        assertEquals(listOf(880, 100, 120, 50), gate.ints("mask"))
        // 上游的这些字段必须原样保留，否则补丁会悄悄改掉流程时序
        assertEquals(2.0, gate.num("pre_delay"))
        assertEquals(3.0, gate.num("post_delay"))
        assertEquals(listOf("empty"), gate.next)
    }

    /**
     * 全部 19 个补丁节点都必须能被上游流水线接住 —— 这条用真实的补丁内容做回归，
     * 免得改补丁时手写错节点名（素材名与节点名很容易混，例如 clear_all_caches 是
     * 素材名，对应节点其实叫 touch_to_start）。
     */
    @Test fun `随包补丁的节点名全部存在于上游流水线`() {
        val patchText = java.io.File(
            "src/main/assets/limbus-overlay/config/task-patch.json"
        ).takeIf { it.isFile } ?: return
        val patch = Json.parseToJsonElement(patchText.readText()).jsonObject
        // 下划线前缀是注释键；它的值是数组，不过滤会让 jsonObject 抛异常，
        // 继而所有补丁一起静默失效 —— 这是实际差点漏掉的一个 bug
        val nodeNames = patch.keys.filterNot { it.startsWith("_") }
        assertTrue("补丁应包含节点", nodeNames.isNotEmpty())
        for (name in nodeNames) {
            assertTrue("$name 的补丁必须是对象", patch.getValue(name) is JsonObject)
        }
    }

    /** 补丁名打错会静默失效（节点照旧走上游识别），必须在装载时就拒绝 */
    @Test fun `补丁引用不存在的节点时拒绝装载`() {
        val error = assertThrows(IllegalStateException::class.java) {
            PipelineRegistry.load(
                mapOf("main.json" to """{"empty":{"action":"empty"},"error_handler":{"interrupt":[]}}"""),
                patches = mapOf("typo_node" to Json.parseToJsonElement("{}").jsonObject),
            )
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("typo_node"))
    }

    @Test fun unsupportedRecognitionIsRejectedEvenWhenDisabledOrInverse() {
        // Recognizer methods used by actions do not automatically become pipeline modes.
        // "ocr" 已成为受支持的文字判据（见 PipelineNode.RECOGNITION_OCR），故从这里移出
        for (recognition in listOf("future_match", "pyramid_template_match", "precise_template_match", "")) {
            for (enabled in listOf(false, true)) {
                for (inverse in listOf(false, true)) {
                    val message = rejectsGate("""{
                        "recognition":"$recognition", "enable":$enabled, "inverse":$inverse
                    }""", "recognition '$recognition'")
                    assertTrue(message, message.contains("请升级 App"))
                }
            }
        }
    }

    @Test fun allFourImplementedModesAndKnownNodeTypesRemainLoadable() {
        for (recognition in listOf("direct", "template_match", "color_template_match", "feature_match")) {
            for (type in listOf("normal", "basic", "check")) {
                val gate = loadGate("""{
                    "type":"$type", "recognition":"$recognition",
                    "params":{"template":"x","threshold":0.9,"mask":[0,0,1280,720]}
                }""").require("gate")
                assertEquals(type, gate.type)
                assertEquals(recognition, gate.recognition)
            }
        }
    }

    @Test fun unknownNodeTypeCannotSilentlyBecomeNormalRouting() {
        for (type in listOf("future_check", "Check", "")) {
            val message = rejectsGate("""{"type":"$type"}""", "type '$type'")
            assertTrue(message, message.contains("请升级 App"))
        }
    }

    @Test fun matchingModesRequireNonEmptyStringTemplates() {
        val params = listOf(
            "{}", """{"template":null}""", """{"template":""}""", """{"template":"  "}""",
            """{"template":42}""", """{"template":false}""", """{"template":[]}""",
            """{"template":{}}""", """{"template":["x",null]}""", """{"template":["x",42]}""",
            """{"template":["x",""]}""",
        )
        for (recognition in listOf("template_match", "color_template_match", "feature_match")) {
            for (value in params) rejectsGate(
                """{"recognition":"$recognition","inverse":true,"params":$value}""", "params.template",
            )
        }
    }

    @Test fun invalidThresholdCannotSilentlyUseDefaultOrBecomeInverseHit() {
        for (threshold in listOf("null", "true", "{}", "[]", "\"bad\"", "\"NaN\"", "\"Infinity\"", "1e309")) {
            rejectsGate("""{
                "recognition":"template_match","inverse":true,
                "params":{"template":"x","threshold":$threshold}
            }""", "params.threshold")
        }
        for (recognition in listOf("color_template_match", "feature_match")) {
            for (threshold in listOf(-0.1, 1.1)) rejectsGate("""{
                "recognition":"$recognition","params":{"template":"x","threshold":$threshold}
            }""", "params.threshold")
        }
    }

    @Test fun malformedMaskCannotSilentlyWidenRecognitionToFullScreen() {
        for (mask in listOf(
            "null", "{}", "[]", "[0,0,10]", "[0,0,10,10,99]", "[0,0,0,10]", "[0,0,10,-1]",
            "[0,0,1.5,10]", "[0,0,2147483648,10]", "[\"NaN\",0,10,10]", "[false,0,10,10]",
        )) rejectsGate("""{
            "recognition":"template_match","inverse":true,"params":{"template":"x","mask":$mask}
        }""", "params.mask")
    }

    @Test fun ignoredExtensionsAndSupportedParameterFormsArePreserved() {
        val gate = loadGate("""{
            "recognition":"template_match", "future_metadata":{"description":"ignored"},
            "params":{"template":["first","second"],"threshold":"0.9",
                      "mask":[-10.0,0,1300,720],"future_parameter":{"enabled":true}}
        }""").require("gate")
        assertEquals(listOf("first", "second"), gate.templates())
        assertEquals(0.9, gate.num("threshold")!!, 1e-9)
        assertNotNull(gate.params["future_parameter"])
        // Only matching nodes consume these fields; direct actions may use their own schema.
        loadGate("""{"recognition":"direct","params":{"template":{},"threshold":"action-specific","mask":null}}""")
        // Grayscale matching accepts signed correlation thresholds; do not narrow its range.
        loadGate("""{"recognition":"template_match","params":{"template":"x","threshold":-0.5}}""")
    }

    @Test fun checkTargetsCanStillBeSuppliedAfterLoadingUpstream() {
        val loaded = PipelineRegistry.load(LalcV500Fixtures.taskFiles())
        val configured = loaded.withTargetCounts(mapOf("exp_check" to 3, "thread_check" to 0, "mirror_check" to 2))
        assertEquals(null, loaded.require("exp_check").num("target_count"))
        assertEquals(3.0, configured.require("exp_check").num("target_count")!!, 0.0)
        assertEquals(false, configured.require("thread_entry").enable)
        assertEquals(2.0, configured.require("mirror_check").num("target_count")!!, 0.0)
    }

    // ---------- 真实上游用例 ----------

    @Test
    fun loadsRealUpstreamPipeline() {
        val files = LalcV500Fixtures.taskFiles()

        val reg = PipelineRegistry.load(files)

        // 实测 v5.0.0：10 个文件 133 个节点。数量变化说明上游改了流水线，
        // 此时应更新资源包并复核动作实现，而不是放宽断言。
        assertEquals(10, files.size)
        assertEquals(133, reg.size)

        // 上游要求 action 必须同时是已注册节点名，load 已校验；这里确认动作总数
        assertEquals(45, reg.referencedActions().size)

        // JSON 里实际只用到 direct 与 template_match 两种识别
        val recognitions = reg.names().mapNotNull { reg[it]?.recognition }.toSet()
        assertEquals(
            setOf(PipelineNode.RECOGNITION_DIRECT, PipelineNode.RECOGNITION_TEMPLATE_MATCH),
            recognitions,
        )

        // 清单来自同一 commit 的 img Git 树；这里只校验引用，不验证图片像素。
        val referenced = reg.referencedTemplates()
        assertEquals(50, referenced.size)
        val missing = referenced - LalcV500Fixtures.templateNames()
        assertTrue("流水线引用了固定上游版本不存在的模板: $missing", missing.isEmpty())
    }
}
