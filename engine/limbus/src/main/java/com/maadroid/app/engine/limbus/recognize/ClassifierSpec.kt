package com.maadroid.app.engine.limbus.recognize

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * 一个 ONNX 分类模型的元数据，全部从资源包里读取而非写死在代码里。
 *
 * 这样上游重训模型（改输入尺寸、加类别）时能靠资源包热更跟随；写死则要发 APK。
 * 上游 v5.0.0 的三个模型：
 *
 * | 模型 | 输入 | 类别 | 形态 |
 * |---|---|---|---|
 * | `mirror_legend` | 130x110 | 8 | 单标签（九宫格节点类型）|
 * | `skill_icon` | 80x80 | 7 | 单标签（拼点优劣势）|
 * | `mirror_path` | 224x224 | 9 | **多标签**（三条路径的连接关系）|
 */
data class ClassifierSpec(
    val name: String,
    val modelFile: File,
    val inputWidth: Int,
    val inputHeight: Int,
    /** 下标 → 标签名 */
    val labels: List<String>,
    /** 多标签模型每位一个阈值；为空则用 [ClassifierMath.DEFAULT_MULTI_LABEL_THRESHOLD] */
    val thresholds: FloatArray?,
    /** 是否多标签（sigmoid 逐位判定）而非单标签（argmax） */
    val multiLabel: Boolean,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val MODEL_FILE = "best_model.onnx"
        private const val CONFIG_FILE = "training_config.json"
        private const val CLASSES_FILE = "classes.txt"
        private const val CONNECTIONS_FILE = "connections.txt"

        /** 上游把模型放在资源包的 `ai/model/<名字>/` 下 */
        const val MODEL_ROOT = "ai/model"

        /**
         * 从资源包目录加载。缺文件或配置不完整返回 null，启动预检据此拒绝执行。
         */
        fun load(resourceDir: File, name: String): ClassifierSpec? {
            val dir = File(File(resourceDir, MODEL_ROOT), name)
            val model = File(dir, MODEL_FILE).takeIf { it.isFile } ?: return null

            val config = File(dir, CONFIG_FILE).takeIf { it.isFile }
                ?.let { runCatching { json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }

            val inner = config?.get("config") as? JsonObject
            val width = (inner?.get("input_width") as? JsonPrimitive)?.intOrNull ?: return null
            val height = (inner?.get("input_height") as? JsonPrimitive)?.intOrNull ?: return null
            // 输入图来自 1280x720 游戏画面，拒绝零尺寸和无法合理分配的元数据。
            if (width !in 1..4096 || height !in 1..4096) return null

            // connections.txt 存在即为多标签模型（上游只有 mirror_path 是这样）
            val connectionsFile = File(dir, CONNECTIONS_FILE)
            val multiLabel = connectionsFile.isFile
            val labelFile = if (multiLabel) connectionsFile else File(dir, CLASSES_FILE)
            val labels = labelFile.takeIf { it.isFile }?.let(::parseLabels) ?: return null
            if (labels.isEmpty()) return null

            // 上游 _load_training_config 取的就是这个键；v5.0.0 实测没有该键，
            // 于是回退到 0.5。这里保持同样的行为而不是硬编码 0.5
            val thresholds = (config?.get("best_thresholds") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
                ?.toFloatArray()
                ?.takeIf { it.size == labels.size }

            return ClassifierSpec(
                name = name,
                modelFile = model,
                inputWidth = width,
                inputHeight = height,
                labels = labels,
                thresholds = thresholds,
                multiLabel = multiLabel,
            )
        }

        /**
         * 解析 `<下标> <名字>` 每行一条的标签文件，按下标就位。
         *
         * 必须按文件里的下标而不是行序放置：模型输出的是下标，行序若与下标不一致
         * （上游手工维护这些文件，不能假定一致）会让所有标签整体错位。
         */
        internal fun parseLabels(file: File): List<String> = parseLabels(file.readLines())

        internal fun parseLabels(lines: List<String>): List<String> {
            val byIndex = HashMap<Int, String>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val sep = trimmed.indexOf(' ')
                if (sep <= 0) continue
                val idx = trimmed.substring(0, sep).toIntOrNull() ?: continue
                byIndex[idx] = trimmed.substring(sep + 1).trim()
            }
            if (byIndex.isEmpty()) return emptyList()
            val max = byIndex.keys.max()
            // 下标不连续说明标签文件本身有问题，宁可判为不可用也不要错位
            if (byIndex.size != max + 1) return emptyList()
            return (0..max).map { byIndex.getValue(it) }
        }
    }
}
