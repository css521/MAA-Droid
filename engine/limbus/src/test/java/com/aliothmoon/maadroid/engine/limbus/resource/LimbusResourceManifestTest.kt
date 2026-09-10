package com.aliothmoon.maadroid.engine.limbus.resource

import com.aliothmoon.maadroid.engine.ResourceRevision
import com.aliothmoon.maadroid.engine.limbus.fixtures.OnnxInterfaceFixture
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LimbusResourceManifestTest {
    @get:Rule val temp = TemporaryFolder()
    private val revision = ResourceRevision("v5.0.0", "a".repeat(40))
    // A complete 1x1 RGB PNG, including IDAT and CRCs; no native image decoder required.
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC")
    private fun write(path: String, text: String) = File(temp.root, path).also { it.parentFile!!.mkdirs(); it.writeText(text) }
    private fun fixture() {
        write("config/task/main.json", """{"main":{"action":"empty","interrupt":[],"params":{"template":"test"}},"empty":{"action":"empty","interrupt":[]}}""")
        write("config/language/zh/test.json", "{}")
        File(temp.root, "img/general").mkdirs()
        for (lang in listOf("zh", "en")) {
            write("img/$lang/test.png", "").writeBytes(png)
        }
        write("img/general/ego_gifts/test-gift.png", "").writeBytes(png)
        write("img/general/theme_packs/test-pack.png", "").writeBytes(png)
        for (name in listOf("mirror_legend", "skill_icon", "mirror_path")) {
            write("ai/model/$name/best_model.onnx", "").writeBytes(OnnxInterfaceFixture.model())
            write("ai/model/$name/training_config.json", """{"config":{"input_width":10,"input_height":10}}""")
            write("ai/model/$name/${if (name == "mirror_path") "connections" else "classes"}.txt", "0 label")
        }
        write("recognize/models/ch_PP-OCRv5_det_mobile.onnx", "").writeBytes(
            OnnxInterfaceFixture.model(listOf(null, 3, null, null), listOf(null, 1, null, null)))
        write("recognize/models/ch_PP-OCRv5_rec_mobile.onnx", "").writeBytes(
            OnnxInterfaceFixture.model(listOf(null, 3, 48, null), listOf(null, null, 4), "a\nb"))
        write(".upstream-source/task_action/base.py", listOf("empty", "click", "key", "swipe")
            .joinToString("\n") { "@TaskExecution.register('$it')\ndef handler(): pass" })
    }
    private fun finalizePack() = LimbusResourceManifest.finalize(temp.root, revision) { text ->
        val required = Json.parseToJsonElement(text).jsonObject.getValue("required_actions").jsonArray.map { it.jsonPrimitive.content }
        if (required.any { it !in setOf("empty", "click", "key", "swipe") }) "upgrade required" else null
    }
    @Test fun manifestIsDeterministicAndVerifiesEveryFile() {
        fixture()
        finalizePack()
        assertFalse(File(temp.root, ".upstream-source").exists())
        assertNull(LimbusResourceManifest.verify(temp.root) { null })
        val manifest = Json.parseToJsonElement(File(temp.root, "manifest.json").readText()).jsonObject
        assertEquals(revision.commit, manifest.getValue("upstream").jsonObject.getValue("commit").jsonPrimitive.content)
        assertTrue(manifest.getValue("files").jsonArray.all { it.jsonObject.keys.containsAll(listOf("sha256", "size", "path")) })
        write("config/language/zh/test.json", "{\"tampered\":true}")
        assertNotNull(LimbusResourceManifest.verify(temp.root) { null })
    }
    @Test(expected = IllegalArgumentException::class) fun missingOcrIsRejected() {
        fixture()
        File(temp.root, "recognize/models/ch_PP-OCRv5_rec_mobile.onnx").delete()
        finalizePack()
    }
    @Test(expected = IllegalArgumentException::class) fun missingTemplateIsRejected() {
        fixture()
        File(temp.root, "img").deleteRecursively()
        finalizePack()
    }
    @Test(expected = IllegalStateException::class) fun brokenPipelineIsRejected() {
        fixture()
        write("config/task/main.json", """{"main":{"action":"unknown","interrupt":[]}}""")
        finalizePack()
    }
    @Test(expected = IllegalStateException::class) fun newlyDeclaredHandlerIsRejected() {
        fixture()
        write("config/task/new.json", """{"future":{"action":"future","interrupt":[]}}""")
        write(".upstream-source/task_action/new.py", "@TaskExecution.register(\n 'future'\n)\ndef handler(): pass")
        finalizePack()
    }
    @Test(expected = IllegalArgumentException::class) fun unrecognizedRegistrationFailsClosed() {
        LimbusResourceManifest.declaredActions("@TaskExecution.register(action_name)")
    }
    @Test fun readsSingleDoubleAndMultilineDeclarations() {
        assertEquals(setOf("one", "two"), LimbusResourceManifest.declaredActions("@TaskExecution.register('one')\n@TaskExecution . register(\n\"two\",\n)"))
    }
    @Test fun ignoresExamplesInCommentsAndDocstrings() {
        val source = "\"\"\"Example: @TaskExecution.register()\"\"\"\n# @TaskExecution.register(dynamic)\n@TaskExecution.register('empty')"
        assertEquals(setOf("empty"), LimbusResourceManifest.declaredActions(source))
    }

    private fun rejected(message: String) {
        val failure = runCatching { finalizePack() }.exceptionOrNull()
        assertNotNull("Expected rejection: $message", failure)
        assertTrue(failure!!.message, failure.message.orEmpty().contains(message))
        assertFalse(File(temp.root, "manifest.json").exists())
    }

    @Test fun otherLanguageTemplateCannotMaskMissingEnglishTemplate() {
        fixture()
        File(temp.root, "img/en/test.png").delete()
        rejected("en 缺少流水线模板")
    }

    @Test fun commonTemplateCanServeBothLanguages() {
        fixture()
        File(temp.root, "img/zh/test.png").delete()
        File(temp.root, "img/en/test.png").delete()
        write("img/general/test.png", "").writeBytes(png)
        finalizePack()
        assertNull(LimbusResourceManifest.verify(temp.root) { null })
    }

    @Test fun languageValuesMustFollowRuntimeStringContract() {
        fixture()
        write("config/language/zh/test.json", """{"gift":{"text":"invalid"}}""")
        rejected("无效语言条目")
    }

    @Test fun englishTableMayBeAbsentButExistingInvalidTableIsRejected() {
        fixture()
        write("config/language/en/test.json", """{"gift":true}""")
        rejected("无效语言条目")
    }

    @Test fun themeImagesInSubfoldersCannotProduceAnEmptyCatalog() {
        fixture()
        File(temp.root, "img/general/theme_packs/test-pack.png").delete()
        write("img/general/theme_packs/nested/test-pack.png", "").writeBytes(png)
        rejected("缺少主题包图鉴")
    }

    @Test fun missingGiftCatalogIsRejected() {
        fixture()
        File(temp.root, "img/general/ego_gifts").deleteRecursively()
        rejected("缺少饰品图鉴")
    }

    @Test fun pngSignatureAloneAndBrokenChunkCrcAreRejected() {
        fixture()
        val image = File(temp.root, "img/en/test.png")
        for (bytes in listOf(png.copyOf(8), png.copyOf(png.size - 1),
            png.copyOf().also { it[45] = (it[45].toInt() xor 1).toByte() })) {
            image.writeBytes(bytes)
            rejected("无效 PNG 模板 test.png")
        }
    }

    @Test fun modelMustDeclareAnOnnxGraph() {
        fixture()
        write("recognize/models/ch_PP-OCRv5_det_mobile.onnx", "not a model")
        rejected("模型接口无效 ch_PP-OCRv5_det_mobile.onnx")
    }

    @Test fun recognitionDictionaryMustExistAndMatchOutputClasses() {
        fixture()
        for (dictionary in listOf(null, "", "a\nb\nc")) {
            write("recognize/models/ch_PP-OCRv5_rec_mobile.onnx", "").writeBytes(
                OnnxInterfaceFixture.model(listOf(null, 3, 48, null), listOf(null, null, 4), dictionary))
            rejected(if (dictionary.isNullOrEmpty()) "字符表" else "尺寸与引擎配置不匹配")
        }
    }

    @Test fun fixedWidthOcrWouldFailDifferentNavigationOrTextCropSizes() {
        fixture()
        write("recognize/models/ch_PP-OCRv5_rec_mobile.onnx", "").writeBytes(
            OnnxInterfaceFixture.model(listOf(null, 3, 48, 320), listOf(null, null, 4), "a\nb"))
        rejected("可变文字宽度")
    }

    @Test fun classifierOutputMustMatchTheNewLabelTable() {
        fixture()
        write("ai/model/skill_icon/classes.txt", "0 first\n1 second")
        rejected("尺寸与引擎配置不匹配")
        // A coordinated upstream model/config update is valid; do not freeze v5.0.0 dimensions/classes.
        write("ai/model/skill_icon/training_config.json", """{"config":{"input_width":20,"input_height":30}}""")
        write("ai/model/skill_icon/best_model.onnx", "").writeBytes(
            OnnxInterfaceFixture.model(listOf(null, 3, 30, 20), listOf(null, 2)))
        finalizePack()
        assertNull(LimbusResourceManifest.verify(temp.root) { null })
    }

    @Test fun classifierTensorTypeMustMatchFloatRuntimeInput() {
        fixture()
        write("ai/model/skill_icon/best_model.onnx", "").writeBytes(OnnxInterfaceFixture.model(elementType = 10))
        rejected("数据类型或维数不受支持")
    }

    @Test fun auxiliaryModelOutputIsAllowedButCannotReplaceTheConsumedOutput() {
        fixture()
        val modelFile = File(temp.root, "ai/model/skill_icon/best_model.onnx")
        modelFile.writeBytes(OnnxInterfaceFixture.model(graphPrefix = OnnxInterfaceFixture.sequenceOutput("auxiliary")))
        rejected("必须是图像/结果张量")
        modelFile.writeBytes(OnnxInterfaceFixture.model(graphSuffix = OnnxInterfaceFixture.sequenceOutput("auxiliary")))
        finalizePack()
        assertNull(LimbusResourceManifest.verify(temp.root) { null })
    }

    @Test fun detectionHeightMustSupportResizingDifferentAspectRatios() {
        fixture()
        write("recognize/models/ch_PP-OCRv5_det_mobile.onnx", "").writeBytes(
            OnnxInterfaceFixture.model(listOf(null, 3, 736, null), listOf(null, 1, null, null)))
        rejected("可变画幅")
    }

    @Test fun unknownRecognitionCannotActivateEvenWhenInverted() {
        fixture()
        write("config/task/main.json", """{"main":{"action":"empty","recognition":"future_model","inverse":true,"interrupt":[]},"empty":{"action":"empty","interrupt":[]}}""")
        rejected("future_model")
    }
}
