package com.aliothmoon.maadroid.engine.limbus.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 素材索引的语义验证。
 *
 * 三条被验的语义都来自上游 img_registry，且都**错了不会报错、只会识别不中**：
 * 语言目录覆盖 general、按基名索引、目录逐层累积打 tag。
 */
class ResourcePackTemplateIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun png(path: String, content: String = "x") {
        val f = File(tmp.root, path)
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    @Test
    fun `按基名索引且不含目录与扩展名`() {
        png("img/general/basic/main_window.png")
        val idx = ResourcePackTemplateIndex.load(tmp.root, "")
        assertTrue("main_window" in idx)
        assertFalse("不该按带目录的名字索引", "basic/main_window" in idx)
        assertFalse("不该带扩展名", "main_window.png" in idx)
    }

    @Test
    fun `目录逐层累积打 tag`() {
        png("img/general/ego_gifts/Burn/burning_branch.png")
        val idx = ResourcePackTemplateIndex.load(tmp.root, "")
        assertEquals(listOf("burning_branch"), idx.namesByTag("ego_gifts"))
        assertEquals(listOf("burning_branch"), idx.namesByTag("ego_gifts_Burn"))
        assertTrue("不存在的 tag 返回空表", idx.namesByTag("ego_gifts_Bleed").isEmpty())
    }

    @Test
    fun `语言目录覆盖 general 的同名素材`() {
        png("img/general/confirm.png", "general")
        png("img/zh/confirm.png", "zh")
        val idx = ResourcePackTemplateIndex.load(tmp.root, "zh")
        // 同一个基名，取到的必须是语言目录那份
        assertEquals("zh", idx.fileOf("confirm")!!.readText())
        assertEquals("只有一个基名，不该算成两个素材", 1, idx.size)
    }

    @Test
    fun `不指定语言时只装 general`() {
        png("img/general/confirm.png", "general")
        png("img/zh/confirm.png", "zh")
        val idx = ResourcePackTemplateIndex.load(tmp.root, "")
        assertEquals("general", idx.fileOf("confirm")!!.readText())
    }

    @Test
    fun `general 内重名会告警`() {
        png("img/general/a/dup.png")
        png("img/general/b/dup.png")
        val warnings = mutableListOf<String>()
        ResourcePackTemplateIndex.load(tmp.root, "", warnings::add)
        assertTrue("general 内重名说明资源包有问题", warnings.any { "重名" in it })
    }

    @Test
    fun `语言目录覆盖 general 不算重名`() {
        png("img/general/confirm.png")
        png("img/zh/confirm.png")
        val warnings = mutableListOf<String>()
        ResourcePackTemplateIndex.load(tmp.root, "zh", warnings::add)
        assertTrue("覆盖是语言目录的用途，不该告警: $warnings",
            warnings.none { "重名" in it })
    }

    @Test
    fun `非 PNG 文件被跳过而不是让引擎起不来`() {
        png("img/general/ok.png")
        png("img/general/.DS_Store")
        val warnings = mutableListOf<String>()
        val idx = ResourcePackTemplateIndex.load(tmp.root, "", warnings::add)
        assertTrue("ok" in idx)
        assertEquals(1, idx.size)
        assertTrue(warnings.any { "非 PNG" in it })
    }

    @Test
    fun `缺少 img 目录时告警而非抛异常`() {
        val warnings = mutableListOf<String>()
        val idx = ResourcePackTemplateIndex.load(tmp.root, "zh", warnings::add)
        assertEquals(0, idx.size)
        assertNull(idx.fileOf("anything"))
        assertTrue(warnings.isNotEmpty())
    }

    @Test fun `标题页提供两种语言的锚点但不改变游戏模板与标签`() {
        png("img/zh/ui/clear_all_caches.png", "zh")
        png("img/en/ui/clear_all_caches.png", "en")
        val idx = ResourcePackTemplateIndex.load(tmp.root, "zh")
        assertEquals("zh", idx.fileOf("clear_all_caches")!!.readText())
        assertEquals(listOf("en", "zh"), idx.titleAnchors.map { it.readText() })
        assertEquals(listOf("clear_all_caches"), idx.namesByTag("ui"))
        assertEquals(1, idx.size)
    }
}

/**
 * 对着**真实上游素材树**跑索引。
 *
 * 合成 fixture 只能证明逻辑自洽，证明不了「和上游那 622 个文件对得上」。
 * 期望值是用 Python 照 img_registry 的规则独立算出来的（见提交说明），
 * 两条实现给出同一组数字才说明移植没跑偏。
 *
 * 上游 clone 不在时自动跳过 —— CI 与他人的机器上没有它，不该因此变红。
 */
class ResourcePackTemplateIndexUpstreamTest {

    private val upstream = File("/Users/css521/project/java/LixAssistantLimbusCompany/lalc_backend")

    @Test
    fun `真实素材树的基名数与 tag 展开数与上游一致`() {
        org.junit.Assume.assumeTrue("未找到上游 clone，跳过", File(upstream, "img/general").isDirectory)

        val warnings = mutableListOf<String>()
        val idx = ResourcePackTemplateIndex.load(upstream, "zh", warnings::add)

        // 622 个 PNG 但只有 562 个唯一基名：zh 与 en 下的同名文件是语言变体
        assertEquals("唯一基名数应与上游一致", 562, idx.size)
        assertTrue("上游 general 内不应有重名: $warnings", warnings.none { "重名" in it })

        // 镜牢按体系挑饰品全靠这层展开
        assertEquals("ego_gifts 总数", 332, idx.namesByTag("ego_gifts").size)
        assertEquals(24, idx.namesByTag("ego_gifts_Burn").size)
        assertEquals(30, idx.namesByTag("ego_gifts_Bleed").size)
        assertEquals(34, idx.namesByTag("ego_gifts_Rupture").size)
        assertEquals(83, idx.namesByTag("ego_gifts_Keywordless").size)
    }

    @Test
    fun `流水线引用的模板在真实素材树里全部存在`() {
        org.junit.Assume.assumeTrue("未找到上游 clone，跳过", File(upstream, "img/general").isDirectory)

        val idx = ResourcePackTemplateIndex.load(upstream, "zh")
        val taskDir = File(upstream, "config/task")
        val referenced = taskDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .flatMap { f ->
                Regex(""""template"\s*:\s*(?:"([^"]+)"|\[([^]]*)])""")
                    .findAll(f.readText())
                    .flatMap { m ->
                        val single = m.groupValues[1]
                        if (single.isNotEmpty()) sequenceOf(single)
                        else Regex(""""([^"]+)"""").findAll(m.groupValues[2]).map { it.groupValues[1] }
                    }
            }.toSortedSet()

        assertTrue("应能从流水线里解析出模板引用", referenced.isNotEmpty())
        val missing = referenced.filterNot { it in idx }
        assertTrue("流水线引用了素材树里没有的模板: $missing", missing.isEmpty())
    }
}
