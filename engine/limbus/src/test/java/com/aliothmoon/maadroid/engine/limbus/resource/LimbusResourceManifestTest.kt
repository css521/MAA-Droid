package com.aliothmoon.maadroid.engine.limbus.resource

import com.aliothmoon.maadroid.engine.ResourceRevision
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LimbusResourceManifestTest {
    @get:Rule val temp = TemporaryFolder()
    private val revision = ResourceRevision("v5.0.0", "a".repeat(40))
    private fun write(path: String, text: String) = File(temp.root, path).also { it.parentFile!!.mkdirs(); it.writeText(text) }
    private fun fixture() {
        write("config/task/main.json", """{"main":{"action":"empty","interrupt":[],"params":{"template":"test"}},"empty":{"action":"empty","interrupt":[]}}""")
        write("config/language/zh/test.json", "{}")
        for (lang in listOf("zh", "en")) {
            write("img/$lang/test.png", "").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        }
        for (name in listOf("mirror_legend", "skill_icon", "mirror_path")) {
            write("ai/model/$name/best_model.onnx", "binary-model-fixture")
            write("ai/model/$name/training_config.json", """{"config":{"input_width":10,"input_height":10}}""")
            write("ai/model/$name/${if (name == "mirror_path") "connections" else "classes"}.txt", "0 label")
        }
        for (name in listOf("det", "rec")) write("recognize/models/ch_PP-OCRv5_${name}_mobile.onnx", "binary-ocr-fixture")
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
}
