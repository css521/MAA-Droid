package com.aliothmoon.maadroid.engine.limbus.recognize

import com.aliothmoon.maadroid.engine.Frame
import com.aliothmoon.maadroid.engine.FrameSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
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
    private val classifier: OnnxClassifier? = null,
    private val ocr: com.aliothmoon.maadroid.engine.limbus.recognize.ocr.PpOcrEngine? = null,
    private val onLog: (String) -> Unit = {},
) : Recognizer {

    private val templateCache = HashMap<Pair<String, Boolean>, Mat?>()

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
            if (crop != null && region == null) return emptyList()
            val work = if (region == null) screen else Mat(screen, region.toRect())
            try {
                // maskTemplate 是对**模板**取子区域，用于「只比对卡包左上角那块」
                val templateRegion = maskTemplate?.clampTo(tpl.cols(), tpl.rows())
                if (maskTemplate != null && templateRegion == null) return emptyList()
                val effectiveTpl = templateRegion?.let { Mat(tpl, it.toRect()) } ?: tpl
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

    /**
     * 文本检测 + 识别。上游用量第二（22 处）：饰品名、主题卡包、队伍人数、现金都靠它。
     *
     * [crop] 语义与模板匹配一致 —— 是**裁剪**，返回坐标会加回偏移。
     * OCR 不可用时返回空表（等价于「识别不中」），依赖文字的步骤会走兜底分支。
     */
    override suspend fun detectText(crop: Crop?, threshold: Double): List<TextMatch> {
        val engine = ocr ?: run {
            warnOnce("OCR", "OCR 不可用，依赖文字识别的步骤将走兜底分支")
            return emptyList()
        }
        val frame = frames.grab() ?: return emptyList()
        val screen = frame.toMat()
        try {
            val region = crop?.clampTo(screen.cols(), screen.rows())
            if (crop != null && region == null) return emptyList()
            val work = if (region == null) screen else Mat(screen, region.toRect())
            try {
                val offsetX = region?.x ?: 0
                val offsetY = region?.y ?: 0
                return engine.detect(work)
                    .filter { it.confidence >= threshold }
                    .map {
                        TextMatch(
                            text = it.text,
                            x = it.centerX + offsetX,
                            y = it.centerY + offsetY,
                            score = it.confidence.toDouble(),
                        )
                    }
            } finally {
                if (work !== screen) work.release()
            }
        } finally {
            screen.release()
        }
    }

    /**
     * 在检测结果里找目标文本，对应上游 `find_text_in_image`。
     *
     * 用**包含**而非相等：OCR 常把周围的标点或临近文字一起框进来，
     * 要求相等会让绝大多数查找失败。[threshold] 是置信度下限，不是相似度。
     */
    override suspend fun findText(target: String, crop: Crop?, threshold: Double): List<TextMatch> {
        if (target.isEmpty()) return emptyList()
        return detectText(crop, threshold).filter { target in it.text }
    }

    /**
     * 单标签分类。[regions] 为空时按模型取约定区域：`mirror_legend` 取九宫格
     * 六格（见 [MirrorRegions.LEGEND_NODES]）。
     */
    override suspend fun classify(model: String, regions: List<Crop>): List<String> {
        val cls = classifier ?: run {
            warnOnce("NN:$model", "分类器未初始化")
            return emptyList()
        }
        val spec = cls.specOf(model) ?: run {
            warnOnce("NN:$model", "分类模型 $model 不在资源包内")
            return emptyList()
        }
        val frame = frames.grab() ?: return emptyList()
        val screen = frame.toMat()
        try {
            val images = if (regions.isEmpty() && model == MODEL_MIRROR_LEGEND) {
                MirrorRegions.prepareLegendInputs(screen, spec.inputWidth, spec.inputHeight)
            } else {
                regions.mapNotNull { r ->
                    val area = MirrorRegions.clamp(r, screen.cols(), screen.rows())
                        ?: return@mapNotNull null
                    val sub = Mat(screen, Rect(area.x, area.y, area.width, area.height))
                    try {
                        MirrorRegions.toRgbBytes(sub, spec.inputWidth, spec.inputHeight)
                    } finally {
                        sub.release()
                    }
                }
            }
            if (images.isEmpty()) return emptyList()
            return cls.classify(model, images)
        } finally {
            screen.release()
        }
    }

    /**
     * 多标签分类。`mirror_path` 需要先把六个节点区域涂掉再整块裁剪缩放
     * （上游的训练期增广，推理时也照做），故 [regions] 一般传空由此处准备。
     */
    override suspend fun classifyMultiLabel(model: String, regions: List<Crop>): List<List<String>> {
        val cls = classifier ?: run {
            warnOnce("NN:$model", "分类器未初始化")
            return emptyList()
        }
        val spec = cls.specOf(model) ?: run {
            warnOnce("NN:$model", "分类模型 $model 不在资源包内")
            return emptyList()
        }
        val frame = frames.grab() ?: return emptyList()
        val screen = frame.toMat()
        try {
            val image = if (regions.isEmpty() && model == MODEL_MIRROR_PATH) {
                MirrorRegions.preparePathInput(screen, spec.inputWidth, spec.inputHeight)
            } else {
                val r = regions.firstOrNull() ?: return emptyList()
                val area = MirrorRegions.clamp(r, screen.cols(), screen.rows()) ?: return emptyList()
                val sub = Mat(screen, Rect(area.x, area.y, area.width, area.height))
                try {
                    MirrorRegions.toRgbBytes(sub, spec.inputWidth, spec.inputHeight)
                } finally {
                    sub.release()
                }
            }
            if (image == null) return emptyList()
            return cls.classifyMultiLabel(model, listOf(image))
        } finally {
            screen.release()
        }
    }

    override suspend fun battleSkillIcons(): List<BattleSkillIcon> {
        val cls = classifier ?: return emptyList()
        val spec = cls.specOf("skill_icon") ?: return emptyList()
        val templates = listOf("skill_blunt", "skill_pierce", "skill_slash")
            .map { templateOf(it, color = true) ?: return emptyList() }
        val frame = frames.grab() ?: return emptyList()
        if (frame.width != 1280 || frame.height != 720) return emptyList()
        val screen = frame.toMat()
        try {
            val area = BattlePerception.SKILL_AREA
            val strip = Mat(screen, area.toRect())
            val anchors = try {
                templates.flatMap { BattleImageOps.skillAnchors(strip, it) }
            } finally {
                strip.release()
            }
            val winRateX = templateOf("win_rate")?.let {
                TemplateMatcher.match(screen, it, 0.85).firstOrNull()?.x
            } ?: 1280
            val regions = BattlePerception.skillRegions(anchors, winRateX)
            val images = regions.map { region ->
                val tile = BattleImageOps.paddedCrop(screen, region)
                try {
                    MirrorRegions.toRgbBytes(tile, spec.inputWidth, spec.inputHeight) ?: return emptyList()
                } finally {
                    tile.release()
                }
            }
            if (images.isEmpty()) return emptyList()
            return BattlePerception.bindSkills(regions, cls.classify("skill_icon", images))
        } finally {
            screen.release()
        }
    }

    override suspend fun battleSinnerAvatars(): List<BattleSinnerAvatar> {
        val frame = frames.grab() ?: return emptyList()
        if (frame.width != 1280 || frame.height != 720) return emptyList()
        val screen = frame.toMat()
        try {
            return BattleImageOps.sinnerAvatars(screen)
        } finally {
            screen.release()
        }
    }

    override suspend fun battleEgoPanel(): BattleEgoPanel? {
        val detail = templateOf("ego_details") ?: return null
        val corrode = templateOf("to_corrode_0%") ?: return null
        val winRate = templateOf("win_rate") ?: return null
        val frame = frames.grab() ?: return null
        if (frame.width != 1280 || frame.height != 720) return null
        val screen = frame.toMat()
        try {
            val area = BattlePerception.EGO_AREA
            val strip = Mat(screen, area.toRect())
            try {
                return BattleEgoPanel(
                    TemplateMatcher.match(strip, detail, 0.85, offsetY = area.y),
                    TemplateMatcher.match(strip, corrode, 0.85, offsetY = area.y),
                    TemplateMatcher.match(screen, winRate, 0.85).isNotEmpty(),
                )
            } finally {
                strip.release()
            }
        } finally {
            screen.release()
        }
    }

    override suspend fun colorTemplateMatch(template: String, threshold: Double, crop: Crop?):
            List<Match> = advancedMatch(AdvancedTemplateMatcher.Mode.COLOR, template, threshold, crop)

    override suspend fun featureMatch(template: String, threshold: Double, crop: Crop?):
            List<Match> = advancedMatch(AdvancedTemplateMatcher.Mode.FEATURE, template, threshold, crop)

    override suspend fun pyramidTemplateMatch(template: String, threshold: Double, crop: Crop?):
            List<Match> = advancedMatch(AdvancedTemplateMatcher.Mode.PYRAMID, template, threshold, crop)

    override suspend fun preciseTemplateMatch(template: String, threshold: Double, crop: Crop?):
            List<Match> = advancedMatch(AdvancedTemplateMatcher.Mode.PRECISE, template, threshold, crop)

    /** 四种匹配共用取帧、彩色模板缓存和裁剪坐标还原。 */
    private suspend fun advancedMatch(
        mode: AdvancedTemplateMatcher.Mode,
        template: String,
        threshold: Double,
        crop: Crop?,
    ): List<Match> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val tpl = templateOf(template, color = true) ?: return emptyList()
        val frame = frames.grab() ?: return emptyList()
        val screen = frame.toMat()
        try {
            val region = crop?.clampTo(screen.cols(), screen.rows())
            if (crop != null && region == null) return emptyList()
            val work = if (region == null) screen else Mat(screen, region.toRect())
            try {
                return if (mode == AdvancedTemplateMatcher.Mode.PYRAMID) {
                    AdvancedTemplateMatcher.pyramidMatch(work, tpl, threshold,
                        region?.x ?: 0, region?.y ?: 0, checkActive = { context.ensureActive() })
                        .map { it.asMatch() }
                } else {
                    AdvancedTemplateMatcher.match(mode, work, tpl, threshold, region?.x ?: 0, region?.y ?: 0)
                }
            } finally {
                if (work !== screen) work.release()
            }
        } finally {
            screen.release()
        }
    }

    private val warned = HashSet<String>()

    /** 同一种缺失只提示一次，否则识别循环会把日志刷爆 */
    private fun warnOnce(key: String, message: String) {
        if (warned.add(key)) onLog(message)
    }

    private fun templateOf(name: String, color: Boolean = false): Mat? = templateCache.getOrPut(name to color) {
        val f = templateFileOf(name)
        if (f == null || !f.isFile) {
            onLog("素材缺失: $name")
            return@getOrPut null
        }
        // 统一用 BGR2GRAY：imread 的灰度解码与屏幕的 cvtColor 存在取整差异，
        // CLAHE 会放大该差异，甚至使原样截取的模板也达不到严格阈值。
        val mat = Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_COLOR)
        if (mat.empty()) {
            onLog("素材解码失败: $name")
            mat.release()
            return@getOrPut null
        }
        if (color) mat else {
            val gray = Mat()
            try {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
                gray
            } catch (failure: Throwable) {
                gray.release()
                throw failure
            } finally {
                mat.release()
            }
        }
    }

    /** 释放缓存的模板与推理会话。引擎停止或换语言时调用 */
    fun release() {
        templateCache.values.forEach { it?.release() }
        templateCache.clear()
        warned.clear()
        classifier?.release()
        ocr?.close()
    }

    private companion object {

        const val MODEL_MIRROR_LEGEND = "mirror_legend"
        const val MODEL_MIRROR_PATH = "mirror_path"

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
            val w = ((x.toLong() + this.width).coerceIn(0, width.toLong()) - x0).toInt()
            val h = ((y.toLong() + this.height).coerceIn(0, height.toLong()) - y0).toInt()
            return if (w <= 0 || h <= 0) null else Crop(x0, y0, w, h)
        }

        fun Crop.toRect() = Rect(x, y, width, height)
    }
}
