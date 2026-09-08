package com.aliothmoon.maadroid.engine.limbus.recognize

import com.aliothmoon.maadroid.engine.Frame
import com.aliothmoon.maadroid.engine.FrameSource
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * 边狱识别器：以模板匹配为主力，跑在 App 进程。
 *
 * 帧从提权进程经共享内存过来（[FrameSource]），像素是 **BGR 三通道**，正好是
 * OpenCV 的原生通道序，构造 Mat 不需要转换。
 *
 * 每次识别取一帧新的，与上游 `input_handler.capture_screenshot()` 的语义一致 ——
 * 上游每个识别点都重新截图，动作逻辑依赖「看到的是当下的画面」。
 *
 * 模板 Mat 有缓存：128 处模板匹配里同一个模板会被反复用，每次从磁盘解码 PNG
 * 会明显拖慢识别。缓存按基名，与 [TemplateIndex] 的索引口径一致。
 */
class LimbusRecognizer(
    private val frames: FrameSource,
    private val index: TemplateIndex,
    private val templateFileOf: (String) -> File?,
    private val onLog: (String) -> Unit = {},
) : Recognizer {

    private val templateCache = HashMap<String, Mat?>()

    override suspend fun templateMatch(
        template: String,
        threshold: Double,
        crop: Crop?,
        maskTemplate: Crop?,
        screenshotScale: Double,
    ): List<Match> {
        val tpl = templateOf(template) ?: return emptyList()
        val frame = frames.grab() ?: return emptyList()

        val screen = frame.toMat()
        try {
            // crop 语义是裁剪，返回坐标要加回偏移（照抄上游的 mask 语义）
            val region = crop?.clampTo(screen.cols(), screen.rows())
            val work = if (region == null) screen else Mat(screen, region.toRect())
            try {
                // maskTemplate 是对**模板**取子区域，用于「只比对卡包左上角那块」
                val effectiveTpl = maskTemplate
                    ?.clampTo(tpl.cols(), tpl.rows())
                    ?.let { Mat(tpl, it.toRect()) }
                    ?: tpl
                try {
                    return TemplateMatcher.match(
                        screen = work,
                        template = effectiveTpl,
                        threshold = threshold,
                        offsetX = region?.x ?: 0,
                        offsetY = region?.y ?: 0,
                        screenshotScale = screenshotScale,
                    )
                } finally {
                    if (effectiveTpl !== tpl) effectiveTpl.release()
                }
            } finally {
                if (work !== screen) work.release()
            }
        } finally {
            screen.release()
        }
    }

    // ---- 以下识别方式尚未实现 ----
    //
    // 都返回空表（等价于「识别不中」）而不是抛异常：流水线遇到识别不中会走
    // next 的下一个候选或 interrupt，是有定义的行为；抛异常则会中断整条任务链。
    // 每个都留一条日志，让用户知道是能力缺失而不是画面不对。

    /**
     * OCR。上游用量第二（22 处），饰品名、主题卡包、队伍人数、现金都靠它，
     * 缺了会让镜牢的商店与跨层选饰品退化到只能走模板兜底分支。
     * 计划走 onnxruntime-android + PP-OCRv4 mobile det/rec，模型随资源包下发。
     */
    override suspend fun detectText(crop: Crop?, threshold: Double): List<TextMatch> {
        warnOnce("OCR", "OCR 尚未接入，依赖文字识别的步骤将走兜底分支")
        return emptyList()
    }

    override suspend fun findText(target: String, crop: Crop?, threshold: Double): List<TextMatch> {
        warnOnce("OCR", "OCR 尚未接入，依赖文字识别的步骤将走兜底分支")
        return emptyList()
    }

    /** 三个 ONNX 分类器：mirror_legend / mirror_path / skill_icon */
    override suspend fun classify(model: String, regions: List<Crop>): List<String> {
        warnOnce("NN:$model", "分类器 $model 尚未接入")
        return emptyList()
    }

    override suspend fun colorTemplateMatch(template: String, threshold: Double, crop: Crop?):
            List<Match> = fallbackToGray("color_template_match", template, threshold, crop)

    override suspend fun featureMatch(template: String, threshold: Double, crop: Crop?):
            List<Match> = fallbackToGray("feature_match", template, threshold, crop)

    override suspend fun pyramidTemplateMatch(template: String, threshold: Double, crop: Crop?):
            List<Match> = fallbackToGray("pyramid_template_match", template, threshold, crop)

    override suspend fun preciseTemplateMatch(template: String, threshold: Double, crop: Crop?):
            List<Match> = fallbackToGray("precise_template_match", template, threshold, crop)

    /**
     * 四个冷门匹配器暂以灰度模板匹配代替（上游各只有一处调用）。
     *
     * 这是**近似**而非等价，故留日志：precise 会比灰度更严、pyramid 抗缩放、
     * color 看颜色、feature 抗形变。用灰度顶上时结果可能偏松或偏紧，
     * 但比返回空表更接近可用 —— 这四处上游都是「找到就点、找不到就跳过」的形态。
     */
    private suspend fun fallbackToGray(
        kind: String,
        template: String,
        threshold: Double,
        crop: Crop?,
    ): List<Match> {
        warnOnce(kind, "$kind 尚未实现，暂以灰度模板匹配近似")
        return templateMatch(template, threshold, crop)
    }

    private val warned = HashSet<String>()

    /** 同一种缺失只提示一次，否则识别循环会把日志刷爆 */
    private fun warnOnce(key: String, message: String) {
        if (warned.add(key)) onLog(message)
    }

    private fun templateOf(name: String): Mat? = templateCache.getOrPut(name) {
        val f = templateFileOf(name)
        if (f == null || !f.isFile) {
            onLog("素材缺失: $name")
            return@getOrPut null
        }
        // IMREAD_GRAYSCALE：模板匹配全程走灰度，早转省一次转换
        val mat = Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        if (mat.empty()) {
            onLog("素材解码失败: $name")
            mat.release()
            return@getOrPut null
        }
        mat
    }

    /** 释放缓存的模板。引擎停止或换语言时调用 */
    fun release() {
        templateCache.values.forEach { it?.release() }
        templateCache.clear()
        warned.clear()
    }

    private companion object {

        /** BGR 帧 → OpenCV Mat。按 stride 逐行拷贝，容忍将来引入行对齐填充 */
        fun Frame.toMat(): Mat {
            val mat = Mat(height, width, CvType.CV_8UC3)
            val rowBytes = width * 3
            val row = ByteArray(rowBytes)
            val buf = buffer.duplicate()
            for (y in 0 until height) {
                buf.position(y * stride)
                buf.get(row, 0, rowBytes)
                mat.put(y, 0, row)
            }
            return mat
        }

        fun Crop.clampTo(width: Int, height: Int): Crop? {
            val x0 = x.coerceIn(0, width)
            val y0 = y.coerceIn(0, height)
            val w = this.width.coerceAtMost(width - x0)
            val h = this.height.coerceAtMost(height - y0)
            return if (w <= 0 || h <= 0) null else Crop(x0, y0, w, h)
        }

        fun Crop.toRect() = Rect(x, y, width, height)
    }
}
