package com.aliothmoon.maadroid.engine.limbus.resource

import com.aliothmoon.maadroid.engine.ResourceRevision
import com.aliothmoon.maadroid.engine.UpstreamArchive
import com.aliothmoon.maadroid.engine.isSafeResourcePath
import com.aliothmoon.maadroid.engine.limbus.config.LimbusLanguage
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineRegistry
import com.aliothmoon.maadroid.engine.limbus.recognize.ClassifierSpec
import com.aliothmoon.maadroid.engine.limbus.recognize.ResourcePackTemplateIndex
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Pure JVM validation; Python is read as declaration text, never loaded as executable code. */
internal object LimbusResourceManifest {
    const val REPOSITORY = "HSLix/LixAssistantLimbusCompany"
    val directories = listOf("config/task", "config/language", "img", "ai/model", "recognize/models")
    private val json = Json { prettyPrint = true }
    private val shaPattern = Regex("[0-9a-f]{64}")
    private val routingActions = setOf(
        "battle_entry", "enter_enkephalin", "error_download_data_confirm", "error_handler",
        "event_entry", "event_entry_dark", "game_language_confirm", "main_drive_confirm",
        "main_window_confirm", "wait_connecting_disappear",
    )
    private val registration = Regex("TaskExecution\\s*\\.\\s*register\\s*\\(")
    private val literalArgument = Regex("""\s*(["'])([A-Za-z_][A-Za-z0-9_]*)\1\s*,?\s*\)""")

    fun finalize(root: File, revision: ResourceRevision, compatibility: (String) -> String?) {
        val inspection = File(root, UpstreamArchive.INSPECTION_DIRECTORY)
        require(inspection.isDirectory) { "缺少上游动作声明，无法校验兼容性" }
        val declarations = sortedSetOf<String>()
        inspection.walkTopDown().filter { it.isFile && it.extension == "py" }.forEach { file ->
            val relative = file.relativeTo(inspection).invariantSeparatorsPath
            if (relative.split('/').any { it.startsWith('.') || it.contains("obsolete") }) return@forEach
            declarations += declaredActions(file.readText())
        }
        require(declarations.containsAll(listOf("empty", "click", "key", "swipe"))) {
            "上游动作声明不完整或注册方式已改变，请升级 App"
        }
        val actions = validateResources(root).referencedActions()
        val required = (actions intersect declarations).sorted()
        val routing = (actions - declarations).sorted()
        require(routing.all { it in routingActions }) {
            "无法确认动作是否为纯路由：${routing.filterNot { it in routingActions }}，请升级 App"
        }
        val files = resourceFiles(root)
        val entries = fileEntries(root, files)
        val manifest = buildJsonObject {
            put("schema_version", 1)
            put("engine", "limbus")
            put("min_engine_version", 1)
            put("revision", contentRevision(entries))
            putJsonObject("upstream") {
                put("repo", REPOSITORY)
                put("tag", revision.tag)
                put("commit", revision.commit)
            }
            put("required_actions", strings(required))
            put("routing_only_actions", strings(routing))
            put("declared_actions", strings(declarations.toList()))
            put("files", JsonArray(entries))
        }
        val text = json.encodeToString(JsonObject.serializer(), manifest)
        compatibility(text)?.let { error(it) }
        check(inspection.deleteRecursively()) { "无法删除临时 Python 源码" }
        // No source files, including accidentally included .py files, may survive activation.
        require(root.walkTopDown().none { it.isFile && it.extension.equals("py", true) })
        File(root, "manifest.json").writeText(text)
    }

    fun verify(root: File, compatibility: (String) -> String?): String? = try {
        val text = File(root, "manifest.json").readText()
        compatibility(text)?.let { error(it) }
        val manifest = json.parseToJsonElement(text).jsonObject
        require(manifest["engine"]?.jsonPrimitive?.content == "limbus") { "资源包引擎不匹配" }
        val upstream = manifest.getValue("upstream").jsonObject
        require(upstream.getValue("repo").jsonPrimitive.content == REPOSITORY) { "资源上游不匹配" }
        ResourceRevision(upstream.getValue("tag").jsonPrimitive.content, upstream.getValue("commit").jsonPrimitive.content)
        val entries = manifest.getValue("files").jsonArray.map { it.jsonObject }
        require(entries.isNotEmpty()) { "资源清单为空" }
        val paths = entries.map { it.getValue("path").jsonPrimitive.content }
        require(paths == paths.sorted() && paths.distinct().size == paths.size) { "资源清单路径重复或未排序" }
        require(paths.all(::isResourceFile)) { "资源清单包含非法路径" }
        val actual = root.walkTopDown().onEnter {
            require(!Files.isSymbolicLink(it.toPath())) { "资源目录不能是符号链接" }; true
        }.filter { it.isFile }.map {
            require(!Files.isSymbolicLink(it.toPath())) { "资源文件不能是符号链接" }
            it.relativeTo(root).invariantSeparatorsPath
        }.filter { it != "manifest.json" }.sorted().toList()
        require(paths == actual) { "资源文件缺失或包含清单外文件" }
        for (entry in entries) {
            val path = entry.getValue("path").jsonPrimitive.content
            val hash = entry.getValue("sha256").jsonPrimitive.content
            val size = entry.getValue("size").jsonPrimitive.longOrNull
            require(shaPattern.matches(hash) && size != null && size >= 0) { "文件清单无效：$path" }
            val file = File(root, path)
            require(file.length() == size && sha256(file) == hash) { "资源校验失败：$path" }
        }
        require(manifest.getValue("revision").jsonPrimitive.content == contentRevision(entries)) { "资源 revision 不匹配" }
        val actions = validateResources(root).referencedActions()
        val declared = stringList(manifest, "declared_actions").toSet()
        val required = stringList(manifest, "required_actions").toSet()
        val routing = stringList(manifest, "routing_only_actions").toSet()
        require(required == actions.intersect(declared) && routing == actions - declared && routing.all { it in routingActions }) {
            "流水线动作与兼容清单不匹配，请升级 App"
        }
        null
    } catch (e: Exception) {
        e.message ?: "资源包验证失败"
    }

    internal fun declaredActions(source: String): Set<String> = registration.findAll(codeOutsideStrings(source)).map { call ->
        val argument = literalArgument.find(source, call.range.last + 1)
        require(argument != null && argument.range.first == call.range.last + 1) {
            "上游使用了无法静态确认的动作注册方式，请升级 App"
        }
        argument.groupValues[2]
    }.toSet()

    /** Preserve offsets while excluding comments/docstrings (the upstream documents register()). */
    private fun codeOutsideStrings(source: String): String {
        val code = source.toCharArray()
        var index = 0
        fun blank(from: Int, to: Int) {
            for (i in from until to) if (code[i] != '\n' && code[i] != '\r') code[i] = ' '
        }
        while (index < source.length) {
            val start = index
            when (val char = source[index]) {
                '#' -> {
                    while (index < source.length && source[index] != '\n') index++
                    blank(start, index)
                }
                '\'', '"' -> {
                    val delimiter = if (source.startsWith("$char$char$char", index)) "$char$char$char" else "$char"
                    index += delimiter.length
                    var closed = false
                    while (index < source.length) {
                        if (source[index] == '\\') { index = (index + 2).coerceAtMost(source.length); continue }
                        if (source.startsWith(delimiter, index)) { index += delimiter.length; closed = true; break }
                        index++
                    }
                    require(closed) { "上游 Python 字符串未闭合，无法校验动作声明" }
                    blank(start, index)
                }
                else -> index++
            }
        }
        return String(code)
    }

    fun stringList(obj: JsonObject, field: String): List<String> {
        val array = obj[field] as? JsonArray ?: error("资源清单缺少 $field 数组")
        return array.map {
            val value = it as? JsonPrimitive
            require(value != null && value.isString && value.content.isNotBlank()) { "$field 包含无效动作" }
            value.content
        }.also { require(it.distinct().size == it.size) { "$field 包含重复动作" } }
    }

    private fun validateResources(root: File): PipelineRegistry {
        directories.forEach { dir ->
            require(File(root, dir).walkTopDown().any { it.isFile }) { "缺少资源目录：$dir" }
        }
        val pipelineFiles = File(root, "config/task").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }.associate { it.name to it.readText() }
        val pipeline = PipelineRegistry.load(pipelineFiles)
        val templates = File(root, "img").walkTopDown().filter { it.isFile && it.extension.equals("png", true) }.toList()
        templates.forEach(PngTemplateContract::validate)
        require(File(root, "img/general").isDirectory) { "缺少通用模板目录 img/general" }
        require(File(root, "img/general/ego_gifts").walkTopDown().any { it.isFile && it.extension == "png" }) {
            "缺少饰品图鉴 img/general/ego_gifts"
        }
        require(File(root, "img/general/theme_packs").listFiles().orEmpty().any { it.isFile && it.extension == "png" }) {
            "缺少主题包图鉴 img/general/theme_packs，请升级 App 或修复资源目录"
        }
        for (language in listOf("zh", "en")) {
            require(File(root, "img/$language").isDirectory) { "缺少模板语言目录 img/$language" }
            val index = ResourcePackTemplateIndex.load(root, language)
            val missing = pipeline.referencedTemplates().filterNot { index.contains(it) }
            require(missing.isEmpty()) { "$language 缺少流水线模板：$missing" }
            // Use the same value/type rules as execution. English may have no translation table.
            LimbusLanguage.load(root, language)
        }
        listOf("ch_PP-OCRv5_det_mobile.onnx", "ch_PP-OCRv5_rec_mobile.onnx").forEach {
            requireModel(File(root, "recognize/models/$it"))
        }
        LimbusModelContract.validateOcr(root)
        listOf("mirror_legend", "skill_icon", "mirror_path").forEach { name ->
            val spec = requireNotNull(ClassifierSpec.load(root, name)) { "分类模型或元数据不完整：$name" }
            require(spec.inputWidth > 0 && spec.inputHeight > 0 && spec.labels.none { it.isBlank() }) { "分类模型元数据无效：$name" }
            require(spec.multiLabel == (name == "mirror_path")) { "分类模型标签类型不匹配：$name" }
            requireModel(spec.modelFile)
            LimbusModelContract.validateClassifier(spec)
        }
        return pipeline
    }

    private fun prefix(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
        val bytes = ByteArray(limit)
        var size = 0
        while (size < limit) {
            val count = input.read(bytes, size, limit - size)
            if (count < 0) break
            size += count
        }
        bytes.copyOf(size)
    }

    private fun requireModel(file: File) {
        require(file.isFile && file.length() > 0) { "缺少必需模型：${file.name}" }
        require(!prefix(file, 80).toString(Charsets.UTF_8).startsWith("version https://git-lfs.github.com/spec")) {
            "模型仍是 Git LFS 指针：${file.name}"
        }
    }

    private fun isResourceFile(path: String): Boolean = isSafeResourcePath(path) &&
        !path.endsWith(".py", true) && directories.any { path.startsWith("$it/") }

    private fun resourceFiles(root: File): List<File> = root.walkTopDown().filter { it.isFile &&
        isResourceFile(it.relativeTo(root).invariantSeparatorsPath)
    }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()

    private fun fileEntries(root: File, files: List<File>): List<JsonObject> = files.map { file ->
        buildJsonObject {
            put("path", file.relativeTo(root).invariantSeparatorsPath)
            put("sha256", sha256(file))
            put("size", file.length())
        }
    }

    // Same digest contract as scripts/pack_engine_resource.py: sorted path:sha256, no final newline.
    private fun contentRevision(entries: List<JsonObject>): String = digest(
        entries.joinToString("\n") { "${it.getValue("path").jsonPrimitive.content}:${it.getValue("sha256").jsonPrimitive.content}" }.toByteArray()
    )
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
