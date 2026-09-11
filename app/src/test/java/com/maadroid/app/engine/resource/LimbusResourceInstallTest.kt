package com.maadroid.app.engine.resource

import com.maadroid.app.engine.ResourceRevision
import com.maadroid.app.engine.limbus.LimbusResourcePack
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the real pack's contract validation through staging and activation, without inference. */
class LimbusResourceInstallTest {
    @get:Rule val temp = TemporaryFolder()
    private val pack = LimbusResourcePack
    private val installer = AtomicResourceInstaller()
    private val firstRevision = ResourceRevision("v5.0.0", "a".repeat(40))
    private val secondRevision = ResourceRevision("v5.1.0", "b".repeat(40))

    @Test fun unknownRecognitionWithInverseCannotReplaceTheActivePack() {
        assertRejectedUpdate("不支持的 recognition 'future_model'") { entries ->
            entries.rewriteJson("config/task/main.json") { pipeline ->
                val main = pipeline.getValue("main").jsonObject
                JsonObject(pipeline + ("main" to JsonObject(main + mapOf(
                    "recognition" to JsonPrimitive("future_model"),
                    "inverse" to JsonPrimitive(true),
                ))))
            }
        }
    }

    @Test fun changedMailRouteCannotReplaceTheActivePack() {
        assertRejectedUpdate("邮件流水线节点 confirm_reward 已变化", "请升级 App") { entries ->
            entries.rewriteJson("config/task/mail.json") { pipeline ->
                val reward = pipeline.getValue("confirm_reward").jsonObject
                // Still a valid node reference; the Android mail guard must reject the route.
                JsonObject(pipeline + ("confirm_reward" to JsonObject(reward +
                    ("next" to JsonArray(listOf(JsonPrimitive("exit_mailbox")))))))
            }
        }
    }

    @Test fun disabledMailWithChangedClaimTargetCannotReplaceTheActivePack() {
        assertRejectedUpdate("邮件流水线节点 claim_mail 已变化", "请升级 App") { entries ->
            entries.rewriteJson("config/task/mail.json") { pipeline ->
                val open = pipeline.getValue("check_and_get_mails").jsonObject
                val claim = pipeline.getValue("claim_mail").jsonObject
                val params = claim.getValue("params").jsonObject
                JsonObject(pipeline + mapOf(
                    "check_and_get_mails" to JsonObject(open + ("enable" to JsonPrimitive(false))),
                    "claim_mail" to JsonObject(claim + ("params" to JsonObject(params +
                        ("target" to JsonArray(listOf(JsonPrimitive(900), JsonPrimitive(300))))))),
                ))
            }
        }
    }

    @Test fun chineseTemplateCannotHideAMissingEnglishTemplate() {
        assertRejectedUpdate("en 缺少流水线模板") { entries ->
            assertNotNull(entries.remove(SOURCE_PREFIX + "img/en/test.png"))
            assertTrue(SOURCE_PREFIX + "img/zh/test.png" in entries)
            assertTrue(SOURCE_PREFIX + "img/en/unreferenced.png" in entries)
        }
    }

    @Test fun nonStringLanguageValueCannotReplaceTheActivePack() {
        assertRejectedUpdate("无效语言条目") { entries ->
            entries.rewriteJson("config/language/zh/test.json") { table ->
                JsonObject(table + ("test" to JsonPrimitive(false)))
            }
        }
    }

    @Test fun additionalSkillLabelWithoutAModelUpdateCannotReplaceTheActivePack() {
        assertRejectedUpdate("尺寸与引擎配置不匹配") { entries ->
            val classes = SOURCE_PREFIX + "ai/model/skill_icon/classes.txt"
            entries[classes] = (entries.getValue(classes).toString(Charsets.UTF_8).trimEnd() +
                "\n1 second\n").toByteArray(Charsets.UTF_8)
        }
    }

    @Test fun truncatedOnnxCannotReplaceTheActivePack() {
        assertRejectedUpdate("模型接口无效 ch_PP-OCRv5_det_mobile.onnx") { entries ->
            val model = SOURCE_PREFIX + "recognize/models/ch_PP-OCRv5_det_mobile.onnx"
            entries[model] = entries.getValue(model).let { it.copyOf(it.size - 1) }
        }
    }

    @Test fun truncatedPngCannotReplaceTheActivePack() {
        assertRejectedUpdate("无效 PNG 模板 test.png") { entries ->
            val image = SOURCE_PREFIX + "img/en/test.png"
            entries[image] = entries.getValue(image).let { it.copyOf(it.size - 1) }
        }
    }

    @Test fun corruptPngChunkCrcCannotReplaceTheActivePack() {
        assertRejectedUpdate("PNG 数据块校验失败") { entries ->
            val image = SOURCE_PREFIX + "img/en/test.png"
            entries[image] = entries.getValue(image).copyOf().also {
                // Change IEND's CRC, then write a valid ZIP CRC around the corrupted PNG bytes.
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
        }
    }

    @Test fun nestedThemePackImageCannotReplaceTheActivePack() {
        assertRejectedUpdate("缺少主题包图鉴") { entries ->
            entries.moveEntry("img/general/theme_packs/test-pack.png", "img/general/theme_packs/nested/test-pack.png")
        }
    }

    @Test fun compatibleMailLanguageAndTemplateUpdateActivatesTheSecondRevision() {
        val entries = fixtureEntries()
        val target = installFirstRevision(entries)
        val before = snapshot(target)
        val previousContentRevision = pack.readInstalledVersion(target)
        entries.rewriteJson("config/language/zh/test.json") { JsonObject(mapOf("updated-test" to JsonPrimitive("新版文字"))) }
        entries.rewriteJson("config/task/main.json") { pipeline ->
            val main = pipeline.getValue("main").jsonObject
            val params = main.getValue("params").jsonObject
            JsonObject(pipeline + ("main" to JsonObject(main + ("params" to
                JsonObject(params + ("template" to JsonPrimitive("updated-test")))))))
        }
        entries.rewriteJson("config/task/mail.json") { pipeline ->
            val open = pipeline.getValue("check_and_get_mails").jsonObject
            val params = open.getValue("params").jsonObject
            JsonObject(pipeline + ("check_and_get_mails" to JsonObject(open + mapOf(
                "enable" to JsonPrimitive(false),
                "desc" to JsonPrimitive("Updated upstream mail description"),
                "rate_limit" to JsonPrimitive(1.5),
                "params" to JsonObject(params + ("threshold" to JsonPrimitive(0.85))),
                "future_ui_hint" to JsonObject(mapOf("label" to JsonPrimitive("Mail"))),
            ))))
        }
        // A different complete 1x1 RGB image, without game assets or native decoding.
        val mailTemplate = "img/general/no_mail_in_storage.png"
        val updatedTemplate = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNgYGAAAAAEAAH2FzhVAAAAAElFTkSuQmCC",
        )
        assertFalse(updatedTemplate.contentEquals(entries.getValue(SOURCE_PREFIX + mailTemplate)))
        entries[SOURCE_PREFIX + mailTemplate] = updatedTemplate
        for (language in listOf("en", "zh")) {
            entries.moveEntry("img/$language/test.png", "img/$language/updated-test.png")
        }
        val phases = mutableListOf<ResourceInstallPhase>()

        installer.install(archive(entries), target, pack, secondRevision, phaseChanged = phases::add)

        assertEquals(verificationPhases + ResourceInstallPhase.ACTIVATING, phases)
        assertRevision(secondRevision, target)
        assertNotEquals(previousContentRevision, pack.readInstalledVersion(target))
        assertNotEquals(before, snapshot(target))
        assertArrayEquals(entries.getValue(SOURCE_PREFIX + "config/language/zh/test.json"),
            File(target, "config/language/zh/test.json").readBytes())
        assertArrayEquals(entries.getValue(SOURCE_PREFIX + "config/task/main.json"),
            File(target, "config/task/main.json").readBytes())
        // Forced enable and Android routing/markers are validation-only, never saved to disk.
        for (path in listOf("config/task/mail.json", "config/task/mail-support.json", mailTemplate)) {
            assertArrayEquals(path, entries.getValue(SOURCE_PREFIX + path), File(target, path).readBytes())
        }
        for (language in listOf("en", "zh")) {
            assertFalse(File(target, "img/$language/test.png").exists())
            assertArrayEquals(entries.getValue(SOURCE_PREFIX + "img/$language/updated-test.png"),
                File(target, "img/$language/updated-test.png").readBytes())
        }
        assertNull(pack.verifyInstalledFiles(target))
        assertOnlyActiveDirectoryRemains(target)
    }

    private fun assertRejectedUpdate(vararg reasons: String, change: (MutableMap<String, ByteArray>) -> Unit) {
        val entries = fixtureEntries()
        val target = installFirstRevision(entries)
        val before = snapshot(target)
        val manifestBytes = pack.manifestFile(target).readBytes()
        val contentRevision = pack.readInstalledVersion(target)
        change(entries)
        val phases = mutableListOf<ResourceInstallPhase>()

        val failure = runCatching {
            installer.install(archive(entries), target, pack, secondRevision, phaseChanged = phases::add)
        }.exceptionOrNull()

        assertNotNull("Expected rejection: ${reasons.toList()}", failure)
        for (reason in reasons) {
            assertTrue("Expected '$reason', got: $failure", failure!!.message.orEmpty().contains(reason))
        }
        assertEquals(verificationPhases, phases)
        assertFalse(ResourceInstallPhase.ACTIVATING in phases)
        assertEquals("Every installed directory and file must remain unchanged", before, snapshot(target))
        assertArrayEquals(manifestBytes, pack.manifestFile(target).readBytes())
        assertEquals(contentRevision, pack.readInstalledVersion(target))
        assertRevision(firstRevision, target)
        assertNull(pack.verifyInstalledFiles(target))
        assertOnlyActiveDirectoryRemains(target)
    }

    private fun installFirstRevision(entries: Map<String, ByteArray>): File =
        File(temp.newFolder(), pack.relativeRoot).also { target ->
            installer.install(archive(entries), target, pack, firstRevision)
            assertRevision(firstRevision, target)
            assertNotNull(pack.readInstalledVersion(target))
            assertNull(pack.verifyInstalledFiles(target))
            assertOnlyActiveDirectoryRemains(target)
        }

    private fun assertRevision(expected: ResourceRevision, target: File) {
        val upstream = Json.parseToJsonElement(pack.manifestFile(target).readText())
            .jsonObject.getValue("upstream").jsonObject
        assertEquals(pack.upstreamArchive.repository, upstream.getValue("repo").jsonPrimitive.content)
        assertEquals(expected.tag, upstream.getValue("tag").jsonPrimitive.content)
        assertEquals(expected.commit, upstream.getValue("commit").jsonPrimitive.content)
    }

    private fun assertOnlyActiveDirectoryRemains(target: File) {
        // Also rejects leaked .staging-* or .previous directories after either outcome.
        assertEquals(listOf(target.name), target.parentFile!!.list()!!.sorted())
    }

    /** Byte lists give structural equality; null records directories, including empty ones. */
    private fun snapshot(target: File): Map<String, List<Byte>?> = target.walkTopDown().associate { file ->
        file.relativeTo(target).invariantSeparatorsPath to if (file.isDirectory) null else file.readBytes().toList()
    }

    private fun fixtureEntries(): MutableMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(requireNotNull(javaClass.getResourceAsStream(FIXTURE)) { "Missing classpath fixture: $FIXTURE" }).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        // Keep img/en present when only test.png is removed. Both revisions start with this
        // unrelated image, so that regression changes only the referenced English template.
        entries[SOURCE_PREFIX + "img/en/unreferenced.png"] = entries.getValue(SOURCE_PREFIX + "img/en/test.png").copyOf()
        return entries
    }

    private fun archive(entries: Map<String, ByteArray>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun MutableMap<String, ByteArray>.rewriteJson(path: String, change: (JsonObject) -> JsonObject) {
        val entry = SOURCE_PREFIX + path
        this[entry] = change(Json.parseToJsonElement(getValue(entry).toString(Charsets.UTF_8)).jsonObject)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun MutableMap<String, ByteArray>.moveEntry(from: String, to: String) {
        check(SOURCE_PREFIX + to !in this)
        this[SOURCE_PREFIX + to] = requireNotNull(remove(SOURCE_PREFIX + from))
    }

    companion object {
        private const val FIXTURE = "/fixtures/limbus-resource-contract/source.zip"
        private const val SOURCE_PREFIX = "fixture/lalc_backend/"
        private val verificationPhases = listOf(ResourceInstallPhase.VERIFYING_ARCHIVE,
            ResourceInstallPhase.EXTRACTING, ResourceInstallPhase.VERIFYING_FILES)
    }
}
