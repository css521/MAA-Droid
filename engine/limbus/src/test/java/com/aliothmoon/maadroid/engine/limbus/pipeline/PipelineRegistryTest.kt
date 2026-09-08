package com.aliothmoon.maadroid.engine.limbus.pipeline

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 流水线装配的契约测试。
 *
 * 分两类：
 * - 合成用例：不依赖外部仓库，保证装配规则（重名、断引用、error 节点 interrupt 清空、
 *   默认 interrupt）在 CI 上始终被校验。
 * - 真实上游用例：本地存在 LALC 克隆时，直接吃它 config/task 下的 JSON，
 *   证明骨架能吃下真实数据而不是只对得上我们自己造的样本。
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

    // ---------- 真实上游用例 ----------

    @Test
    fun loadsRealUpstreamPipeline() {
        val dir = upstreamTaskDir()
        assumeTrue("未找到本地 LALC 克隆，跳过真实上游用例", dir != null)
        val files = dir!!.listFiles { f -> f.extension == "json" }!!
            .associate { it.name to it.readText() }

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

        // 模板引用必须都能在素材里找到
        val imgRoot = File(dir.parentFile.parentFile, "img")
        if (imgRoot.isDirectory) {
            val have = imgRoot.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() == "png" }
                .map { it.nameWithoutExtension }
                .toSet()
            val missing = reg.referencedTemplates() - have
            assertTrue("流水线引用了不存在的模板: $missing", missing.isEmpty())
        }
    }

    /** 本地 LALC 克隆的 config/task 目录；找不到则返回 null（CI 上跳过） */
    private fun upstreamTaskDir(): File? = listOf(
        "../../LixAssistantLimbusCompany/lalc_backend/config/task",
        "../LixAssistantLimbusCompany/lalc_backend/config/task",
        System.getProperty("user.home") + "/project/java/LixAssistantLimbusCompany/lalc_backend/config/task",
    ).map(::File).firstOrNull { it.isDirectory }
}
