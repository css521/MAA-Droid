package com.maadroid.app.engine.limbus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 资源包布局：打包器收了什么，装载侧就必须落得下什么。
 *
 * 这两侧一旦不一致，文件会「在包里但落不了盘」。而识别失败的表现是**返回空表**
 * 而非报错，所以症状是「某些步骤莫名走兜底分支」，从日志几乎无法溯源 ——
 * 实际就踩过一次：OCR 模型在上游的 `recognize/models/` 下，打包器与
 * `mapZipEntry` 都只认 `ai/model`，两处同时漏掉。
 */
class LimbusPackLayoutTest {

    @Test
    fun `落盘白名单覆盖打包器声明的每个目录`() {
        // 打包器的 include 列表（scripts/pack_engine_resource.py）
        val packed = listOf(
            "config/task/main.json",
            "config/language/zh/x.json",
            "img/general/basic/main_window.png",
            "ai/model/mirror_legend/best_model.onnx",
            "recognize/models/ch_PP-OCRv5_det_mobile.onnx",
        )
        for (entry in packed) {
            assertEquals(
                "打包器会收 $entry，落盘侧必须原样接受",
                entry,
                LimbusResourcePack.mapZipEntry(entry),
            )
        }
    }

    @Test
    fun `清单本身落盘，目录条目与无关文件被忽略`() {
        assertEquals(
            LimbusResourcePack.MANIFEST_NAME,
            LimbusResourcePack.mapZipEntry(LimbusResourcePack.MANIFEST_NAME),
        )
        // 目录条目落盘会建出空目录
        assertNull(LimbusResourcePack.mapZipEntry("config/"))
        assertNull(LimbusResourcePack.mapZipEntry("recognize/"))
        // 上游仓库里的其它东西（Python 源码等）不该进资源目录
        assertNull(LimbusResourcePack.mapZipEntry("recognize/rapid_ocr.py"))
        assertNull(LimbusResourcePack.mapZipEntry("main.py"))
        assertNull(LimbusResourcePack.mapZipEntry("obsolete_resources/ai/model/x.onnx"))
    }

    /**
     * 有真实产物时，直接拿它对账 —— 比手写条目更能挡住「打包器改了、落盘侧没跟」。
     * 产物由 `python scripts/pack_engine_resource.py --engine limbus …` 生成，
     * 不存在时跳过。
     */
    @Test
    fun `真实资源包的每个条目都能落盘`() {
        val zip = File("/tmp/limbus-pack").listFiles { f -> f.extension == "zip" }
            ?.firstOrNull()
        org.junit.Assume.assumeTrue("未找到实跑产出的资源包，跳过", zip != null)

        ZipFile(zip!!).use { zf ->
            val entries = zf.entries().toList().filterNot { it.isDirectory }
            assertTrue("资源包不应为空", entries.isNotEmpty())

            val dropped = entries.map { it.name }
                .filter { LimbusResourcePack.mapZipEntry(it) == null }
            assertTrue("这些条目在包里却落不了盘: ${dropped.take(10)}", dropped.isEmpty())

            // OCR 模型必须真的在包里 —— 漏了它 OCR 永远拿不到模型
            assertNotNull(
                "资源包应带上 OCR 模型",
                entries.firstOrNull { "ch_PP-OCRv5_det" in it.name },
            )
            assertNotNull(
                entries.firstOrNull { "ch_PP-OCRv5_rec" in it.name },
            )
        }
    }
}
