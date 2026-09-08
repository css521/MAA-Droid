package com.aliothmoon.maadroid.engine.limbus.recognize

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * ONNX 分类器推理。会话按模型名缓存 —— 建会话要解析整个模型文件，
 * 镜牢每选一次节点就要跑两个模型，每次重建会明显卡顿。
 *
 * 与 OpenCV 一样只在 App 进程可用（onnxruntime 的 Java 绑定在 `app_process` 里
 * 加载不可靠），这也是边狱引擎留在 App 进程的原因之一。
 */
class OnnxClassifier(
    private val resourceDir: File,
    private val onLog: (String) -> Unit = {},
) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<String, OrtSession?>()
    private val specs = HashMap<String, ClassifierSpec?>()

    fun specOf(model: String): ClassifierSpec? = specs.getOrPut(model) {
        ClassifierSpec.load(resourceDir, model).also {
            if (it == null) onLog("分类模型 $model 不可用（资源包缺文件或配置不完整）")
        }
    }

    /**
     * 单标签分类：每张输入图一个标签。用于 `mirror_legend`（节点类型）与
     * `skill_icon`（拼点优劣势）。
     *
     * @param images 已按模型输入尺寸准备好的 RGB 字节，每张长度 `w*h*3`
     */
    fun classify(model: String, images: List<ByteArray>): List<String> {
        val spec = specOf(model) ?: return emptyList()
        if (spec.multiLabel) {
            onLog("$model 是多标签模型，应调用 classifyMultiLabel")
            return emptyList()
        }
        return images.map { rgb ->
            val logits = infer(spec, rgb) ?: return emptyList()
            spec.labels.getOrElse(ClassifierMath.argmax(logits)) { UNKNOWN_LABEL }
        }
    }

    /**
     * 多标签分类：每张输入图一组激活标签。用于 `mirror_path` —— 它一次给出三条
     * 路径各自连到哪些节点，所以返回的是「一组」而不是「一个」。
     */
    fun classifyMultiLabel(model: String, images: List<ByteArray>): List<List<String>> {
        val spec = specOf(model) ?: return emptyList()
        return images.map { rgb ->
            val logits = infer(spec, rgb) ?: return emptyList()
            ClassifierMath.activeIndices(logits, spec.thresholds)
                .map { spec.labels.getOrElse(it) { UNKNOWN_LABEL } }
        }
    }

    private fun infer(spec: ClassifierSpec, rgb: ByteArray): FloatArray? {
        val session = sessionOf(spec) ?: return null
        return runCatching {
            val input = ClassifierMath.toNchw(rgb, spec.inputWidth, spec.inputHeight)
            val shape = longArrayOf(1, 3, spec.inputHeight.toLong(), spec.inputWidth.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                val inputName = session.inputNames.first()
                session.run(mapOf(inputName to tensor)).use { result ->
                    val out = result[0].value
                    // 输出形状是 [1, num_classes]
                    @Suppress("UNCHECKED_CAST")
                    (out as Array<FloatArray>)[0]
                }
            }
        }.onFailure { onLog("分类模型 ${spec.name} 推理失败: ${it.message}") }.getOrNull()
    }

    private fun sessionOf(spec: ClassifierSpec): OrtSession? = sessions.getOrPut(spec.name) {
        runCatching { env.createSession(spec.modelFile.absolutePath) }
            .onFailure { onLog("分类模型 ${spec.name} 加载失败: ${it.message}") }
            .getOrNull()
    }

    fun release() {
        sessions.values.forEach { runCatching { it?.close() } }
        sessions.clear()
        specs.clear()
    }

    private companion object {
        const val UNKNOWN_LABEL = "unknown"
    }
}
