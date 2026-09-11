package com.maadroid.app.engine.limbus.recognize

import com.maadroid.app.engine.Frame
import com.maadroid.app.engine.FrameSource
import com.maadroid.app.engine.limbus.recognize.ocr.OcrImageOps
import com.maadroid.app.engine.limbus.recognize.ocr.OcrTextQuery
import com.maadroid.app.engine.limbus.recognize.ocr.TextBox
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.core.CvType
import org.opencv.core.Core
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
    private val ocr: com.maadroid.app.engine.limbus.recognize.ocr.PpOcrEngine? = null,
    private val onLog: (String) -> Unit = {},
    private val titleAnchorFiles: List<File> = emptyList(),
    private val gameLanguage: String = "zh",
    private val onInfo: (String) -> Unit = {},
    private val onDiagnostic: (String, String) -> Unit = { _, _ -> },
    /** 开发模式采集底片的落盘目录；null 表示不采集 */
    private val captureDir: File? = null,
) : Recognizer {

    private val templateCache = HashMap<Pair<String, Boolean>, Mat?>()

    // ---- 开发模式：采集素材底片 ----
    //
    // 适配安卓的真实阻塞是缺「这台设备的真帧」。上游素材来自 Steam 客户端，在安卓上
    // 大面积失配；而判断某张素材该改成什么，必须看识别器实际参与匹配的那一帧。
    // 不能用聊天/截图渠道传来的图片当依据——那条链路会磨掉小素材依赖的细节，
    // 系统性压低所有匹配分数（实测 main_drive_no_text 在这种图片上只有 0.555，
    // 而同一时刻设备日志显示它识别成功）。
    //
    // 采集而非「失败时落盘」：要裁素材的界面也包括现在还正常的那些，而失败帧
    // 往往是转场中间的糊图，不适合当素材源。

    /**
     * 已采集过的素材名。首次访问时把目录里已有的文件名装进来，
     * 这样重启 App、重跑任务都不会把同一个界面再采一遍。
     */
    private val knownTags: MutableSet<String> by lazy {
        val existing = captureDir?.listFiles { f -> f.isFile && f.extension == "png" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
        capturedCount = existing.size
        existing.toMutableSet()
    }

    private var capturedCount = 0

    /**
     * 采集一帧底片。画面与上一张差异不大时跳过，避免走一遍主页就写下几千张重复。
     *
     * 命名带上当时正在识别的素材名：导出后据此就能分清哪张是队伍页、哪张是关卡页，
     * 裁素材时不必猜。
     */
    private fun captureFrame(screen: Mat, seq: Long, tag: String) {
        val base = captureDir ?: return
        if (capturedCount >= MAX_CAPTURES) return
        val safeTag = tag.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48)
        // 按素材名去重，**且跨运行生效**（knownTags 惰性装载目录里已有的文件名）。
        // 只靠帧间差不够：识别器每次 connect 都重建，计数与指纹归零，于是每跑一次任务
        // 就把开头那几屏重采一遍——实测 5 次运行采了 78 张，其中只有 17 个不同界面。
        //
        // 刻意不再叠加帧间差判断：那会让素材名在「画面与上次相同」时被白白消耗掉，
        // 之后该素材真正所在的界面就永远采不到了。素材名本身就是够好的主键，
        // 同一屏被多个素材名各存一份也无害——反而能看出那一屏在找哪些判据。
        if (safeTag in knownTags) return
        try {
            if (!base.isDirectory && !base.mkdirs()) return
            val file = File(base, "$safeTag.png")
            if (!Imgcodecs.imwrite(file.absolutePath, screen)) return
            // 只有真的写成功才登记，否则这个素材名会被永久跳过
            knownTags += safeTag
            capturedCount++
            // 绝对路径必须进日志：导出器万一没收集到这棵树，也能照路径手动取
            onDiagnostic("frame.capture", "第 $capturedCount 张 frame=$seq ${file.absolutePath}")
        } catch (e: Throwable) {
            onDiagnostic("frame.capture", "采集失败: ${e.message}")
        }
    }

    override suspend fun dumpFrame(tag: String) {
        val frame = frames.grab() ?: return
        val screen = frame.toMat()
        try {
            captureFrame(screen, frame.seq, tag)
        } finally {
            screen.release()
        }
    }

    override suspend fun observeTeamSelection(): Match? {
        if (gameLanguage != "en") return null
        val frame = frames.grab() ?: return null
        val screen = frame.toMat()
        val coroutine = currentCoroutineContext()
        try {
            return TeamSelectionDetector.detect(screen, ocr,
                checkActive = { coroutine.ensureActive() },
                onCandidates = { onDiagnostic("team.ocr", "frame=${frame.seq} $it") },
            ).also { match ->
                onDiagnostic("team.observe", "frame=${frame.seq} size=${frame.width}x${frame.height} team=${match != null}")
                if (match != null && warned.add("android_team")) onInfo("已识别 Android 队伍选择页")
            }
        } finally { screen.release() }
    }

    override suspend fun observeGameLanguage(): GameLanguageObservation {
        val frame = frames.grab() ?: return GameLanguageObservation.Uncertain
        val screen = frame.toMat()
        try {
            val selected = templateOf("main_drive_with_text")
            if (selected != null && TemplateMatcher.match(screen, selected, .85).isNotEmpty()) {
                return GameLanguageObservation.Confirmed
            }
            val drive = templateOf("main_drive_no_text") ?: return GameLanguageObservation.Uncertain
            val coroutine = currentCoroutineContext()
            return AndroidHomeNavigation.observeLanguage(screen, drive, gameLanguage, ocr) { coroutine.ensureActive() }
        } finally { screen.release() }
    }

    override suspend fun titleScreenStart(): Match? {
        if (titleAnchorFiles.isNotEmpty()) {
            val frame = frames.grab() ?: return null
            val screen = frame.toMat()
            try {
                for (file in titleAnchorFiles) {
                    currentCoroutineContext().ensureActive()
                    val anchor = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR)
                    try {
                        if (!anchor.empty()) {
                            TitleScreenDetector.fromAnchor(TemplateMatcher.match(screen, anchor, 0.85))
                                ?.let { return it }
                        }
                    } finally {
                        anchor.release()
                    }
                }
            } finally {
                screen.release()
            }
        }
        return super<Recognizer>.titleScreenStart()
    }

    // ---- 帧复用：路由里连续多个 templateMatch 共享同一帧 ----
    //
    // probe 遍历 7 个候选 = 7 次 grab + 7 次 toMat + 7 次 CLAHE+模糊，
    // 实测每步 100ms 级别，累积到 3~5 秒（对比 MAA 方舟 1~2 秒）。
    // 但这些调用间隔只有几毫秒，虚拟显示器根本来不及刷新——取到的是同一帧。
    // 所以缓存上一帧的 Mat，seq 相同时直接复用。
    //
    // seq 由 bridge 递增，每次内容更新才变；两个不同帧的 seq 必不同。
    // 如果 bridge 实现不递增，退化成每次取新帧（原行为），不会错。

    private var cachedFrame: Mat? = null
    private var cachedFrameSeq: Long = -1

    private suspend fun grabScreen(): Pair<Mat, Long>? {
        val frame = frames.grab() ?: return null
        val seq = frame.seq
        val cached = cachedFrame
        if (cached != null && seq == cachedFrameSeq) {
            return cached to seq
        }
        cachedFrame?.release()
        val screen = frame.toMat()
        cachedFrame = screen
        cachedFrameSeq = seq
        return screen to seq
    }

    override suspend fun templateMatch(
        template: String,
        threshold: Double,
        crop: Crop?,
        maskTemplate: Crop?,
        screenshotScale: Double,
        onMiss: ((Double, Int, Int) -> Unit)?,
    ): List<Match> {
        val tpl = templateOf(template) ?: return emptyList()
        val (screen, seq) = grabScreen() ?: return emptyList()

        // screen 由 grabScreen 的缓存管理生命周期，不在这里 release
        captureFrame(screen, seq, template)
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
                val matches = TemplateMatcher.match(
                    screen = work,
                    template = effectiveTpl,
                    threshold = threshold,
                    offsetX = region?.x ?: 0,
                    offsetY = region?.y ?: 0,
                    screenshotScale = screenshotScale,
                    onMiss = onMiss,
                )
                if (matches.isEmpty()) reportFrameGeometryOnce(screen, seq, template)
                if (matches.isNotEmpty() || crop != null || maskTemplate != null || screenshotScale != 1.0 ||
                    !AndroidHomeNavigation.supports(template)) return matches

                val drive = templateOf("main_drive_no_text") ?: return emptyList()
                val coroutine = currentCoroutineContext()
                val mobile = AndroidHomeNavigation.match(
                    screen, template, tpl, drive, threshold, gameLanguage, ocr,
                    checkActive = { coroutine.ensureActive() },
                    frameSeq = seq,
                ) ?: return emptyList()
                if (warned.add("android_home:$template")) {
                    onInfo("已识别 Android 主页导航 $template，位置=${mobile.x},${mobile.y}")
                }
                return listOf(mobile)
            } finally {
                if (effectiveTpl !== tpl) effectiveTpl.release()
            }
        } finally {
            if (work !== screen) work.release()
        }
    }

    /**
     * 文本检测 + 识别。上游用量第二（22 处）：饰品名、主题卡包、队伍人数、现金都靠它。
     *
     * [crop] 对应上游 OCR 的 fill_mask_screenshot：保留整帧，仅涂黑区域外的像素。
     * 裁成窄图会被检测模型的短边规则过度放大，也会改变文字的识别尺寸。
     * OCR 不可用时返回空表（等价于「识别不中」），依赖文字的步骤会走兜底分支。
     */
    /**
     * 读区域内文字；**读不到就自动放宽区域重试一次**。
     *
     * 为什么需要：13 处 `detectText(Crop(...))` 的坐标全部来自上游 LALC 的 Windows
     * 客户端，安卓上没有一个经过真帧验证。饰品名那次实测出的失败模式很典型——
     * 区域边界正好切在文字上沿（文字在 y175-202，区域是 y180-220），OCR 只拿到半行，
     * 读出 "cmeraiu ciytra" 这类乱码；把区域放宽 12px 后同一帧三个名字全部读对
     * （conf 0.96/0.99/0.98）。
     *
     * 逐个区域拿真帧去量是没完的（多数界面的帧还采不到），所以在这里兜住：
     * 空结果时按 [OCR_RETRY_PAD] 四向放宽再读一次。放宽只在**读不到时**发生，
     * 正常路径零额外开销；放宽后可能多读到相邻文字，由调用方的匹配逻辑筛掉
     * （它们本来就在做模糊匹配或前缀比对）。
     */
    override suspend fun detectText(crop: Crop?, threshold: Double): List<TextMatch> {
        val first = readText(crop, threshold, query = null)
        if (first.isNotEmpty() || crop == null) return first
        val widened = crop.widened(OCR_RETRY_PAD)
        if (widened == crop) return first
        val retry = readText(widened, threshold, query = null)
        if (retry.isNotEmpty()) {
            onDiagnostic(
                "ocr.widen",
                "原区域读不到内容，放宽 ${OCR_RETRY_PAD}px 后读到 ${retry.size} 段：" +
                    "$crop → $widened",
            )
        }
        return retry
    }

    private suspend fun readText(
        crop: Crop?, threshold: Double, query: OcrTextQuery?,
        diagnosticPhase: String = "ocr.find",
        accepts: (String) -> Boolean = { query == null || query.matches(it) },
    ): List<TextMatch> {
        val engine = ocr ?: run {
            warnOnce("OCR", "OCR 不可用，依赖文字识别的步骤将走兜底分支")
            return emptyList()
        }
        val frame = frames.grab() ?: run {
            if (query?.isNumber == true) onDiagnostic(diagnosticPhase, "target=${query.target.take(32)} frame=unavailable crop=$crop")
            return emptyList()
        }
        val screen = frame.toMat()
        try {
            val region = crop?.clampTo(screen.cols(), screen.rows())
            if (crop != null && region == null) return emptyList()
            val work = if (region == null) screen else OcrImageOps.maskedFrame(screen, region.toRect())
            try {
                fun matching(boxes: List<TextBox>) = boxes.filter {
                    it.confidence >= threshold && accepts(it.text)
                }.map { TextMatch(it.text, it.centerX, it.centerY, it.confidence.toDouble()) }

                val primary = engine.detect(work)
                var matches = matching(primary)
                var color: List<TextBox>? = null
                if (matches.isEmpty() && query?.isNumber == true) {
                    currentCoroutineContext().ensureActive()
                    // 手机上的金色窄数字经 CLAHE 后有时会丢失前导零。保持同一帧、同一掩码
                    // 和置信度，再以原色识别一次；查询规则由调用方提供，不改变名称 OCR。
                    color = engine.detect(work, enhanceContrast = false)
                    matches = matching(color)
                }
                if (query?.isNumber == true) {
                    fun describe(boxes: List<TextBox>) = boxes.take(10).joinToString("; ") {
                        "${it.text.take(36).replace('\n', ' ')}@${it.centerX},${it.centerY}:${(it.confidence * 100).toInt()}%"
                    }
                    onDiagnostic(diagnosticPhase, "target=${query.target.take(32)} frame=${frame.seq} " +
                        "size=${frame.width}x${frame.height} stride=${frame.stride} crop=$crop threshold=$threshold " +
                        "enhanced=[${describe(primary)}]" + (color?.let { " color=[${describe(it)}]" } ?: "") +
                        " hits=${matches.size}")
                }
                return matches
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
     * 名称用包含匹配；纯数字保留前导零并检查数字边界。[threshold] 是置信度下限。
     */
    override suspend fun findText(target: String, crop: Crop?, threshold: Double): List<TextMatch> {
        if (target.isEmpty()) return emptyList()
        return readText(crop, threshold, OcrTextQuery(target))
    }

    override suspend fun findExpStage(stage: String): List<TextMatch> =
        readText(ExpStageQuery.REGION, .5, OcrTextQuery(stage), "ocr.exp_stage", ExpStageQuery(stage)::matches)

    override suspend fun findThreadStage(stage: String): List<TextMatch> =
        readText(
            ThreadStageQuery.REGION, .5, OcrTextQuery(stage),
            "ocr.thread_stage", ThreadStageQuery(stage)::matches,
        )

    override suspend fun observeMailbox(): MailboxObservation? {
        val frame = frames.grab() ?: return null
        val screen = frame.toMat()
        val coroutine = currentCoroutineContext()
        try {
            return MailboxDetector.detect(screen, ocr,
                onCandidates = { onDiagnostic("mail.ocr", "frame=${frame.seq} $it") },
                checkActive = { coroutine.ensureActive() },
            ).also {
                onDiagnostic("mail.observe", "frame=${frame.seq} size=${frame.width}x${frame.height} " +
                    "mailbox=${it != null} empty=${it?.empty} close=${it?.close}")
            }
        } finally { screen.release() }
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
        // 这里有五个各自独立的失败原因，过去它们产生完全相同的空结果，调用方只能打出
        // 「未取得技能图标分类」——分不清是模型没装、素材缺失、帧尺寸不对，还是
        // SKILL_AREA 这个上游 PC 坐标在手机上框错了位置。每种的修法都不一样，
        // 所以每个出口都要说明白自己是谁。
        val cls = classifier ?: run {
            onDiagnostic("battle.skill", "分类器未初始化"); return emptyList()
        }
        val spec = cls.specOf("skill_icon") ?: run {
            onDiagnostic("battle.skill", "缺少 skill_icon 模型元数据"); return emptyList()
        }
        val templateNames = listOf("skill_blunt", "skill_pierce", "skill_slash")
        val templates = ArrayList<Mat>(templateNames.size)
        for (name in templateNames) {
            val tpl = templateOf(name, color = true) ?: run {
                onDiagnostic("battle.skill", "缺少彩色素材 $name（资源包未包含或语言目录不匹配）")
                return emptyList()
            }
            templates += tpl
        }
        val frame = frames.grab() ?: run {
            onDiagnostic("battle.skill", "取帧失败"); return emptyList()
        }
        if (frame.width != 1280 || frame.height != 720) {
            onDiagnostic("battle.skill", "帧尺寸非 1280x720，实际 ${frame.width}x${frame.height}")
            return emptyList()
        }
        val screen = frame.toMat()
        try {
            val area = BattlePerception.SKILL_AREA
            val strip = Mat(screen, area.toRect())
            // 逐个素材记锚点数：全为 0 说明 SKILL_AREA 框错或素材匹配不上，
            // 只有个别为 0 则是那一种技能类型的素材问题
            val perTemplate = LinkedHashMap<String, Int>()
            val anchors = try {
                templateNames.zip(templates).flatMap { (name, tpl) ->
                    BattleImageOps.skillAnchors(strip, tpl).also { perTemplate[name] = it.size }
                }
            } finally {
                strip.release()
            }
            val winRate = templateOf("win_rate")?.let {
                TemplateMatcher.match(screen, it, 0.85).firstOrNull()?.x
            }
            val winRateX = winRate ?: 1280
            val regions = BattlePerception.skillRegions(anchors, winRateX)
            if (regions.isEmpty()) {
                onDiagnostic(
                    "battle.skill",
                    "frame=${frame.seq} 未框出技能区域 area=$area 锚点=$perTemplate " +
                        "win_rate=${winRate ?: "未命中(按 1280 处理)"}",
                )
                return emptyList()
            }
            val images = regions.map { region ->
                val tile = BattleImageOps.paddedCrop(screen, region)
                try {
                    MirrorRegions.toRgbBytes(tile, spec.inputWidth, spec.inputHeight) ?: run {
                        onDiagnostic("battle.skill", "区域转张量失败 region=$region")
                        return emptyList()
                    }
                } finally {
                    tile.release()
                }
            }
            onDiagnostic(
                "battle.skill",
                "frame=${frame.seq} 技能区域 ${regions.size} 个 锚点=$perTemplate " +
                    "win_rate=${winRate ?: "未命中(按 1280 处理)"}",
            )
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

    /** 已上报过的内容区几何，按结果去重；全黑帧不计入 */
    private val reportedGeometry = HashSet<String>()

    /**
     * 首次出现模板未命中时，把**识别器实际拿到的那一帧**的几何量出来，输出一次。
     *
     * 存在的理由：此前判断画面几何只能靠预览截图，而预览有自身的缩放和黑边，
     * 拿它量像素连续得出过两个错误结论。这里直接在参与匹配的 Mat 上量：
     * 非黑像素的外接框就是游戏内容区。
     *
     * - 内容区 = `0,0 1280x720` → 满幅，素材失配与几何无关，应查素材本身或阈值
     * - 内容区 `x>0` 或 `width<1280` → 左右有黑边（宽度方向被letterbox）
     * - 内容区 `y>0` 或 `height<720` → 上下有黑边
     * 任一方向有黑边，都意味着 UI 整体缩放且偏移，562 张 PC 素材会集体失配，
     * 正确修法是一个全局缩放/偏移，而不是逐张重截。
     *
     * 只报一次：几何在一次运行内不会变，逐次输出会淹掉日志。
     */
    private fun reportFrameGeometryOnce(screen: Mat, seq: Long, template: String) {
        val gray = Mat()
        val binary = Mat()
        val points = Mat()
        try {
            if (screen.channels() == 1) screen.copyTo(gray)
            else Imgproc.cvtColor(screen, gray, Imgproc.COLOR_BGR2GRAY)
            // 阈值取 8 而不是 0：黑边并非纯黑，编码与色彩转换会留下个位数残值
            Imgproc.threshold(gray, binary, 8.0, 255.0, Imgproc.THRESH_BINARY)
            Core.findNonZero(binary, points)
            // 全黑帧不上报也不占额度：启动/加载期画面本来就是黑的，上一版把唯一一次
            // 上报机会耗在了 frame=118 的加载黑屏上（节点 server_error_occurred_try_again），
            // 真正有内容的帧再也不报。
            if (points.empty()) return
            val box = Imgproc.boundingRect(points)
            val shape = "${box.x},${box.y} ${box.width}x${box.height}"
            // 按几何去重：同一形状只报一次，不同形状（转场、弹窗）各报一次
            if (!reportedGeometry.add(shape)) return
            onDiagnostic(
                "frame.geometry",
                "frame=$seq 未命中=$template 帧=${screen.cols()}x${screen.rows()} 内容区=$shape",
            )
        } catch (e: Throwable) {
            onDiagnostic("frame.geometry", "量测失败: ${e.message}")
        } finally {
            gray.release(); binary.release(); points.release()
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
        cachedFrame?.release()
        cachedFrame = null
        cachedFrameSeq = -1
        templateCache.values.forEach { it?.release() }
        templateCache.clear()
        warned.clear()
        classifier?.release()
        ocr?.close()
    }

    private companion object {
        /**
         * OCR 读不到内容时的区域放宽量。
         *
         * 12px 是从饰品名那次实测反推的：文字在 y175-202，上游区域 y180-220，
         * 上沿被切 5px 就足以让 OCR 读出乱码。取 12px 留出余量又不至于吞进相邻行
         * （界面上相邻文字行的间距普遍 25px 以上）。
         */
        const val OCR_RETRY_PAD = 12


        /** 开发模式采集帧数上限，避免写满存储 */
        const val MAX_CAPTURES = 60

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
