package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer

/**
 * PP-OCRv5 检测 + 识别。
 *
 * 模型随资源包下发（`recognize/models/` 下 det 4.6 MB + rec 15.9 MB），
 * **字符表嵌在 rec 模型的 `metadata_props["character"]` 里**，不必另取字典 ——
 * 这也消掉了「字典与模型版本对不上导致识别结果乱码」这一类风险。
 *
 * 装载时会核对字符表组装后的类别数与模型输出层维度是否一致；不一致即拒绝启用 OCR
 * 并明确报出两个数字。宁可不做 OCR，也不要输出乱码 —— 乱码经模糊匹配去买饰品，
 * 会买错东西。
 */
class PpOcrEngine private constructor(
    private val env: OrtEnvironment,
    private val detSession: OrtSession,
    private val recSession: OrtSession,
    private val decoder: CtcDecoder,
    private val onLog: (String) -> Unit,
) {

    /**
     * 检测并识别画面中的文本。
     *
     * @param screenBgr 整屏或已裁剪的 BGR 图
     * @return 合并后的文本块，坐标相对传入图像的左上角
     */
    fun detect(screenBgr: Mat): List<TextBox> {
        val probMap = runDetection(screenBgr) ?: return emptyList()
        try {
            val boxes = DbDetector.boxesFrom(probMap, screenBgr.cols(), screenBgr.rows())
            if (boxes.isEmpty()) return emptyList()

            val recognized = recognize(screenBgr, boxes)
            return TextMerge.merge(recognized)
        } finally {
            probMap.release()
        }
    }

    /** 概率图；失败返回 null */
    private fun runDetection(screenBgr: Mat): Mat? {
        val (inW, inH) = OcrGeometry.detInputSize(screenBgr.cols(), screenBgr.rows())
        val resized = Mat()
        try {
            Imgproc.resize(screenBgr, resized, Size(inW.toDouble(), inH.toDouble()))
            val rgb = Mat()
            try {
                Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
                val input = normalizeToNchw(rgb, OcrGeometry.DET_MEAN, OcrGeometry.DET_STD)
                val shape = longArrayOf(1, 3, inH.toLong(), inW.toLong())

                return runCatching {
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
                        detSession.run(mapOf(detSession.inputNames.first() to t)).use { res ->
                            // 输出 [1, 1, H, W]
                            @Suppress("UNCHECKED_CAST")
                            val out = res[0].value as Array<Array<Array<FloatArray>>>
                            val plane = out[0][0]
                            val map = Mat(plane.size, plane[0].size, CvType.CV_32F)
                            for (y in plane.indices) map.put(y, 0, plane[y])
                            map
                        }
                    }
                }.onFailure { onLog("OCR 检测推理失败: ${it.message}") }.getOrNull()
            } finally {
                rgb.release()
            }
        } finally {
            resized.release()
        }
    }

    /** 逐框裁图识别。上游按批推理，这里逐个跑 —— 框数通常个位数，简单优先 */
    private fun recognize(screenBgr: Mat, boxes: List<DetBox>): List<TextBox> {
        val result = ArrayList<TextBox>(boxes.size)
        val batchWidth = OcrGeometry.recBatchWidth(boxes.map { it.width to it.height })

        for (b in boxes) {
            if (b.width <= 0 || b.height <= 0) continue
            val roi = runCatching {
                Mat(screenBgr, Rect(b.left, b.top, b.width, b.height))
            }.getOrNull() ?: continue
            try {
                val text = recognizeOne(roi, batchWidth) ?: continue
                if (text.text.isEmpty()) continue
                result += TextBox(
                    text = text.text,
                    left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                    confidence = text.confidence,
                )
            } finally {
                roi.release()
            }
        }
        return result
    }

    private fun recognizeOne(roiBgr: Mat, batchWidth: Int): OcrText? {
        val resizedW = OcrGeometry.recResizedWidth(roiBgr.cols(), roiBgr.rows(), batchWidth)
        val resized = Mat()
        try {
            Imgproc.resize(
                roiBgr, resized,
                Size(resizedW.toDouble(), OcrGeometry.REC_HEIGHT.toDouble()),
            )
            val rgb = Mat()
            try {
                Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
                // 右侧补零到批宽（上游 padding_im）
                val input = normalizeToNchw(
                    rgb, OcrGeometry.REC_MEAN, OcrGeometry.REC_STD,
                    padToWidth = batchWidth,
                )
                val shape = longArrayOf(1, 3, OcrGeometry.REC_HEIGHT.toLong(), batchWidth.toLong())

                return runCatching {
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
                        recSession.run(mapOf(recSession.inputNames.first() to t)).use { res ->
                            // 输出 [1, time, classes]
                            @Suppress("UNCHECKED_CAST")
                            val out = res[0].value as Array<Array<FloatArray>>
                            val steps = out[0]
                            val classes = decoder.classCount
                            val flat = FloatArray(steps.size * classes)
                            for (t2 in steps.indices) {
                                steps[t2].copyInto(flat, t2 * classes)
                            }
                            decoder.decode(flat, steps.size)
                        }
                    }
                }.onFailure { onLog("OCR 识别推理失败: ${it.message}") }.getOrNull()
            } finally {
                rgb.release()
            }
        } finally {
            resized.release()
        }
    }

    /**
     * RGB Mat → NCHW，按 `(v/255 - mean) / std` 归一化，可右侧补零到 [padToWidth]。
     *
     * 注意 mean/std 是 0.5 而**不是** ImageNet 那组 —— 两个 OCR 模型与三个分类器
     * 用的不是同一套归一化，混用会让检测整体失准。
     */
    private fun normalizeToNchw(
        rgb: Mat,
        mean: Float,
        std: Float,
        padToWidth: Int = rgb.cols(),
    ): FloatArray {
        val h = rgb.rows()
        val w = rgb.cols()
        val outW = maxOf(padToWidth, w)
        val plane = outW * h
        val out = FloatArray(3 * plane)
        val row = ByteArray(w * 3)
        for (y in 0 until h) {
            rgb.get(y, 0, row)
            for (x in 0 until w) {
                for (c in 0 until 3) {
                    val v = (row[x * 3 + c].toInt() and 0xFF) / 255f
                    out[c * plane + y * outW + x] = (v - mean) / std
                }
            }
        }
        return out
    }

    fun close() {
        runCatching { detSession.close() }
        runCatching { recSession.close() }
    }

    companion object {
        private const val MODEL_DIR = "recognize/models"
        private const val DET_MODEL = "ch_PP-OCRv5_det_mobile.onnx"
        private const val REC_MODEL = "ch_PP-OCRv5_rec_mobile.onnx"
        private const val CHARACTER_METADATA_KEY = "character"

        /**
         * 缺少模型或字符表时返回 null。原生加载失败保留异常堆栈，由宿主报告并导出。
         */
        fun load(resourceDir: File, onLog: (String) -> Unit = {}): PpOcrEngine? {
            val dir = File(resourceDir, MODEL_DIR)
            val detFile = File(dir, DET_MODEL)
            val recFile = File(dir, REC_MODEL)
            if (!detFile.isFile || !recFile.isFile) {
                onLog("资源包缺少 OCR 模型（$MODEL_DIR），请修复边狱资源")
                return null
            }

            var openedDet: OrtSession? = null
            var openedRec: OrtSession? = null
            var loaded = false
            return try {
                val env = OrtEnvironment.getEnvironment()
                val det = env.createSession(detFile.absolutePath).also { openedDet = it }
                val rec = env.createSession(recFile.absolutePath).also { openedRec = it }

                val characters = rec.metadata.customMetadata[CHARACTER_METADATA_KEY]
                    ?.let(CtcDecoder::parseCharacters)
                if (characters.isNullOrEmpty()) {
                    onLog("rec 模型未内嵌字符表，无法解码，已停用 OCR")
                    return null
                }

                val decoder = CtcDecoder(characters)
                // 核对字符表与模型输出层是否对得上。不一致时识别结果会整体错位成乱码，
                // 而乱码经模糊匹配去买饰品会买错东西，故宁可停用
                val outputClasses = rec.outputInfo.values.firstOrNull()
                    ?.info?.let { it as? ai.onnxruntime.TensorInfo }
                    ?.shape?.lastOrNull()?.toInt()
                if (outputClasses != null && outputClasses > 0 &&
                    outputClasses != decoder.classCount
                ) {
                    onLog(
                        "OCR 字符表与模型不匹配（字符表 ${decoder.classCount} 类，" +
                            "模型输出 $outputClasses 类），已停用 OCR 以免输出乱码"
                    )
                    return null
                }

                PpOcrEngine(env, det, rec, decoder, onLog).also { loaded = true }
            } catch (failure: Exception) {
                throw IllegalStateException("OCR 模型加载失败: ${failure.message}", failure)
            } finally {
                if (!loaded) {
                    runCatching { openedRec?.close() }
                    runCatching { openedDet?.close() }
                }
            }
        }
    }
}
