package com.aliothmoon.maadroid.engine.limbus.resource

import com.aliothmoon.maadroid.engine.limbus.recognize.ClassifierSpec
import com.aliothmoon.maadroid.engine.limbus.recognize.ocr.CtcDecoder
import com.aliothmoon.maadroid.engine.limbus.recognize.ocr.OnnxMetadata
import com.aliothmoon.maadroid.engine.limbus.recognize.ocr.OcrGeometry
import java.io.File

/** Checks declared model interfaces before activation; native loading/inference is still required at connect. */
internal object LimbusModelContract {
    fun validateOcr(root: File) {
        val directory = File(root, "recognize/models")
        val det = inspect(File(directory, "ch_PP-OCRv5_det_mobile.onnx"))
        val recFile = File(directory, "ch_PP-OCRv5_rec_mobile.onnx")
        val rec = inspect(recFile)
        val detInput = requireShape(det.inputs.single(), listOf(1, 3, null, null), "OCR 检测输入")
        require(detInput.dimensions.takeLast(2).all { it == null }) {
            "OCR 检测模型不支持可变画幅，请升级 App 或修复资源"
        }
        requireShape(det.outputs.first(), listOf(1, 1, null, null), "OCR 检测输出")
        val recInput = requireShape(rec.inputs.single(), listOf(1, 3, OcrGeometry.REC_HEIGHT.toLong(), null), "OCR 识别输入")
        require(recInput.dimensions[3] == null) { "OCR 识别模型不支持可变文字宽度，请升级 App 或修复资源" }

        val characters = OnnxMetadata.read(recFile, "character")?.let(CtcDecoder::parseCharacters)
        require(!characters.isNullOrEmpty()) { "OCR 识别模型缺少有效字符表，请修复资源" }
        val classCount = CtcDecoder(characters).classCount.toLong()
        val recOutput = requireShape(rec.outputs.first(), listOf(1, null, classCount), "OCR 识别输出")
        require(recOutput.dimensions.last() == classCount) {
            "OCR 字符表与模型输出类别不匹配，请升级 App 或修复资源"
        }
    }

    fun validateClassifier(spec: ClassifierSpec) {
        val model = inspect(spec.modelFile)
        requireShape(model.inputs.single(), listOf(1, 3, spec.inputHeight.toLong(), spec.inputWidth.toLong()), "${spec.name} 输入")
        val output = requireShape(model.outputs.first(), listOf(1, spec.labels.size.toLong()), "${spec.name} 输出")
        require(output.dimensions.last() == spec.labels.size.toLong()) {
            "${spec.name} 标签表与模型输出类别不匹配，请升级 App 或修复资源"
        }
    }

    private fun inspect(file: File): OnnxMetadata.ModelInterface = try {
        OnnxMetadata.readModelInterface(file).also {
            require(it.inputs.size == 1) { "需要一个图像输入，实际为 ${it.inputs.size}" }
        }
    } catch (failure: Exception) {
        throw IllegalArgumentException("模型接口无效 ${file.name}：${failure.message}", failure)
    }

    private fun requireShape(value: OnnxMetadata.ValueInfo, expected: List<Long?>, role: String): OnnxMetadata.Tensor {
        val tensor = requireNotNull(value.tensor) { "$role 必须是图像/结果张量，请升级 App 或修复资源" }
        require(tensor.elementType == 1L && tensor.dimensions.size == expected.size) {
            "$role 的数据类型或维数不受支持，请升级 App 或修复资源"
        }
        require(tensor.dimensions.zip(expected).all { (actual, required) ->
            actual == null || actual > 0 && (required == null || actual == required)
        }) { "$role 的尺寸与引擎配置不匹配：${tensor.dimensions}，请升级 App 或修复资源" }
        return tensor
    }
}
