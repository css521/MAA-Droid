package com.aliothmoon.maadroid.engine.limbus.recognize

import java.io.File

/**
 * 扫描已装好的资源包目录，建立素材索引。
 *
 * 移植上游 `recognize/img_registry.py` 的 `register_images`，三条语义必须一致：
 *
 * 1. **先 general 再语言目录**：`img/general` 先注册，随后 `img/<语言>` 覆盖同名项。
 *    上游对 general 开了重名检查（`repeat_warning=True`）而对语言目录关掉 ——
 *    因为语言目录本来就是要覆盖的。这里照同样的规则：general 内重名视为资源包有问题。
 * 2. **按基名索引**：`a/b/x.png` 的键是 `x`，不含目录也不含扩展名。
 *    所以 `img/zh/x.png` 与 `img/en/x.png` 是同一个 `x` 的两个语言版本。
 * 3. **目录逐层累积打 tag**：`ego_gifts/Burn/a.png` 同时属于 `ego_gifts`
 *    与 `ego_gifts_Burn`。镜牢「按体系倾向饰品」全靠这层展开。
 */
class ResourcePackTemplateIndex private constructor(
    private val files: Map<String, File>,
    private val tags: Map<String, List<String>>,
    /** 标题页按钮独立于游戏内容语言；其余模板仍严格使用所选语言。 */
    val titleAnchors: List<File>,
) : TemplateIndex {

    override fun namesByTag(tag: String): List<String> = tags[tag] ?: emptyList()

    override fun contains(name: String): Boolean = name in files

    /** 素材文件；找不到返回 null，调用方按「识别不中」处理 */
    fun fileOf(name: String): File? = files[name]

    val size: Int get() = files.size

    companion object {

        private const val IMG_DIR = "img"
        private const val GENERAL_DIR = "general"
        private const val PNG_SUFFIX = ".png"

        /**
         * @param resourceDir 资源包根目录（其下应有 img/）
         * @param language 语言目录名（如 "zh" / "en"）；为空则只装 general
         * @param onWarning 收到资源包问题的回调（general 内重名、非 PNG 文件）
         */
        fun load(
            resourceDir: File,
            language: String,
            onWarning: (String) -> Unit = {},
        ): ResourcePackTemplateIndex {
            val imgRoot = File(resourceDir, IMG_DIR)
            val files = LinkedHashMap<String, File>()
            val tags = LinkedHashMap<String, MutableList<String>>()

            val general = File(imgRoot, GENERAL_DIR)
            if (general.isDirectory) {
                scan(general, files, tags, warnOnDuplicate = true, onWarning = onWarning)
            } else {
                onWarning("资源包缺少 $IMG_DIR/$GENERAL_DIR 目录")
            }

            if (language.isNotEmpty()) {
                val langDir = File(imgRoot, language)
                if (langDir.isDirectory) {
                    // 语言目录刻意不查重名 —— 覆盖 general 的同名项就是它的用途
                    scan(langDir, files, tags, warnOnDuplicate = false, onWarning = onWarning)
                } else {
                    onWarning("资源包缺少语言目录 $IMG_DIR/$language，将只使用通用素材")
                }
            }

            val titleAnchors = listOf("en", "zh").flatMap { lang ->
                File(imgRoot, lang).walkTopDown()
                    .filter { it.isFile && it.name == "clear_all_caches.png" }.toList()
            }
            return ResourcePackTemplateIndex(files, tags.mapValues { it.value.toList() }, titleAnchors)
        }

        private fun scan(
            root: File,
            files: MutableMap<String, File>,
            tags: MutableMap<String, MutableList<String>>,
            warnOnDuplicate: Boolean,
            onWarning: (String) -> Unit,
        ) {
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                if (!f.name.lowercase().endsWith(PNG_SUFFIX)) {
                    // 上游遇到非 PNG 直接抛异常；这里降为告警并跳过，
                    // 免得资源包里多一个 .DS_Store 就让整个引擎起不来
                    onWarning("忽略非 PNG 素材: ${f.relativeTo(root).path}")
                    return@forEach
                }
                val base = f.name.dropLast(PNG_SUFFIX.length)
                if (warnOnDuplicate && base in files) {
                    onWarning("素材重名: ${f.relativeTo(root).path}")
                }
                files[base] = f

                // 相对目录逐层累积：["ego_gifts","Burn"] → "ego_gifts"、"ego_gifts_Burn"
                val relDir = f.parentFile?.relativeTo(root)?.path.orEmpty()
                if (relDir.isEmpty() || relDir == ".") return@forEach
                val parts = relDir.replace(File.separatorChar, '/').split('/')
                    .filter { it.isNotEmpty() && it != "." }
                for (depth in 1..parts.size) {
                    val tag = parts.take(depth).joinToString("_")
                    val bucket = tags.getOrPut(tag) { mutableListOf() }
                    if (base !in bucket) bucket += base
                }
            }
        }
    }
}
