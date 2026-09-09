package com.aliothmoon.maadroid.engine.arknights.resource

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MaaOcrResourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun upstreamOnnxOnlyIsNotReadyForAndroid() {
        val resource = tmp.newFolder()
        for (pack in listOf("PaddleOCR", "PaddleCharOCR")) {
            write(resource, "$pack/det/inference.onnx")
            write(resource, "$pack/rec/inference.onnx")
            write(resource, "$pack/rec/keys.txt")
        }
        val missing = MaaOcrResources.missingFiles(resource)
        assertEquals(8, missing.size)
        assertTrue(missing.all { ".ncnn." in it })
    }

    @Test
    fun checksBundledLanguageModelsWithoutRequiringItsOwnDetector() {
        val resource = tmp.newFolder()
        writeBaseModels(resource)
        val lang = "global/YoStarJP/resource/PaddleOCR/rec"
        write(resource, "$lang/keys.txt")
        write(resource, "$lang/rec.ncnn.param")
        write(resource, "$lang/rec.ncnn.bin", "")
        assertEquals(listOf("$lang/rec.ncnn.bin"), MaaOcrResources.missingFiles(resource))
        write(resource, "$lang/rec.ncnn.bin")
        assertTrue(MaaOcrResources.missingFiles(resource).isEmpty())
    }

    @Test
    fun removingAWholeBasePackDoesNotPassTheReadinessCheck() {
        val resource = tmp.newFolder()
        writeBaseModels(resource)
        assertTrue(MaaOcrResources.missingFiles(resource).isEmpty())
        File(resource, "PaddleCharOCR").deleteRecursively()
        assertEquals(5, MaaOcrResources.missingFiles(resource).size)
    }

    private fun writeBaseModels(resource: File) {
        for (pack in listOf("PaddleOCR", "PaddleCharOCR")) {
            for (kind in listOf("det", "rec")) {
                write(resource, "$pack/$kind/$kind.ncnn.param")
                write(resource, "$pack/$kind/$kind.ncnn.bin")
                if (kind == "rec") write(resource, "$pack/$kind/keys.txt")
            }
        }
    }

    private fun write(resource: File, path: String, content: String = "model") {
        File(resource, path).apply {
            parentFile!!.mkdirs()
            writeText(content)
        }
    }
}
