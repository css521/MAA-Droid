package com.aliothmoon.maadroid.engine.limbus.recognize

/**
 * 识别结果。坐标是模板中心在 1280x720 逻辑坐标系里的位置，与上游 LALC 返回的
 * `(center_x, center_y, score)` 三元组对应。
 */
data class Match(val x: Int, val y: Int, val score: Double)

/** OCR 结果：文本 + 包围盒中心 + 置信度 */
data class TextMatch(val text: String, val x: Int, val y: Int, val score: Double)

/**
 * 裁剪区域，对应上游的 `mask=[x, y, w, h]`。
 *
 * 模板匹配用 `mask_screenshot` 裁剪后加回坐标偏移；OCR 用 `fill_mask_screenshot`
 * 保留整帧并涂黑区域外。两者都返回原始画面的坐标。
 */
data class Crop(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 识别器。方法集按上游实测调用量排布，不是等量的：
 *
 * | 能力 | 流水线 JSON | 动作代码 | 合计 |
 * |---|---|---|---|
 * | [templateMatch] | 77 | 51 | **128** —— 主力，唯一出现在 JSON 里的识别 |
 * | [detectText] / [findText] | 0 | 22 | **22** —— 第二支柱，饰品名/主题卡包/队伍名靠它 |
 * | [classify] | 0 | 3 | 3 —— mirror_legend / mirror_path / skill_icon 三个 ONNX |
 * | 其余四个 | 0 | 各 1 | 4 —— 末位实现即可 |
 *
 * 所以实现顺序是 templateMatch → OCR → NN → 冷门匹配器，而不是平摊。
 */
interface Recognizer {

    /** Current-frame language evidence. Missing navigation or unclear OCR remains uncertain. */
    suspend fun observeGameLanguage(): GameLanguageObservation = GameLanguageObservation.Uncertain

    /** Android 队伍页的独立正向证据。坐标是页内锚点，不能当作 Details 按钮点击。 */
    suspend fun observeTeamSelection(): Match? = null

    /**
     * 队伍页是否已出现 —— 读 Details 按钮的**文字**，不看素材。
     *
     * 上游用 `templateMatch("details")` 判这件事，但那张素材来自 Steam 客户端，
     * 在安卓真帧的真实按钮处只有 0.485（全图峰值 0.739 还落在卡牌美术上）。
     * 而两个客户端显示的是同一串文字：真帧上 OCR 读出 "Details" 置信度 1.00。
     *
     * 与 `config/task-patch.json` 里那几个 choose_team 节点用的是同一组文字与区域，
     * 改动时两处要一起改（区域常量见 [TeamPage]）。
     */
    suspend fun teamPageVisible(): Boolean =
        findText(TeamPage.TEXT, TeamPage.REGION, TeamPage.THRESHOLD).isNotEmpty()

    /** 只返回确认过的标题页开始位置，不返回清理缓存按钮的位置。 */
    suspend fun titleScreenStart(): Match? =
        TitleScreenDetector.fromAnchor(templateMatch("clear_all_caches"))
            ?: TitleScreenDetector.fromText(detectText(TitleScreenDetector.textRegion, 0.7))

    /** 战斗专用：同一帧内定位、裁剪并分类，标签与中心坐标不可分开取帧。 */
    suspend fun battleSkillIcons(): List<BattleSkillIcon> = emptyList()

    /** 血条识别点（也是长按点）及头像边缘的 EGO 亮度分组。 */
    suspend fun battleSinnerAvatars(): List<BattleSinnerAvatar> = emptyList()

    /** 同一帧的 EGO 卡片与 0% 侵蚀标识。null 表示无法观察，不能当作面板已关闭。 */
    suspend fun battleEgoPanel(): BattleEgoPanel? = null

    /**
     * 灰度模板匹配。[template] 是素材基名（不含扩展名与语言目录）
     *
     * [onMiss] 在相关图峰值不足 [threshold] 时回调 `(峰值, 峰值中心x, 峰值中心y)`。
     * **不要在这一层把它打成日志**：路由本身就依赖「没命中就试下一个候选」，
     * 未命中是常态，逐次输出会淹掉日志。峰值应当上传到流水线，
     * 由它在「所有真候选都落空」这个决策点一次性汇总。
     */
    suspend fun templateMatch(
        template: String,
        threshold: Double = 0.85,
        crop: Crop? = null,
        maskTemplate: Crop? = null,
        screenshotScale: Double = 1.0,
        onMiss: ((Double, Int, Int) -> Unit)? = null,
    ): List<Match>

    /** 文本检测：[crop] 外涂黑，保留整帧尺寸，返回原画面坐标。 */
    suspend fun detectText(crop: Crop? = null, threshold: Double = 0.3): List<TextMatch>

    /** 文本查找：包含目标子串，对应上游 find_text_in_image；threshold 是 OCR 置信度。 */
    suspend fun findText(target: String, crop: Crop? = null, threshold: Double = 0.5): List<TextMatch>

    /** 经验卡片专用：可识别 Android OCR 丢失前导零的完整 STAGE 标签。 */
    suspend fun findExpStage(stage: String): List<TextMatch> =
        findText(stage, crop = ExpStageQuery.REGION)

    /** 纺锤难度专用：可识别 Android OCR 把编号认成字母的 Lv 标签（实测 60 → G0）。 */
    suspend fun findThreadStage(stage: String): List<TextMatch> =
        findText(stage, crop = ThreadStageQuery.REGION)

    /**
     * 主动采集当前帧。用于动作代码里那些不走 templateMatch 的界面（例如九宫格选星光），
     * 采集点在 templateMatch 里触发不到它们。[tag] 用作文件名，便于识别界面。
     */
    suspend fun dumpFrame(tag: String) {}

    /** 邮箱专用观察；未知结果不能作为空邮箱或领取完成的证据。 */
    suspend fun observeMailbox(): MailboxObservation? =
        MailboxDetector.fromText(detectText(MailboxDetector.region, .7))

    /**
     * 单标签 ONNX 分类：每个区域一个标签。
     * [model] 取 `mirror_legend`（九宫格节点类型）或 `skill_icon`（拼点优劣势）。
     * 传空 [regions] 表示由实现取该模型的约定区域（见 MirrorRegions）。
     */
    suspend fun classify(model: String, regions: List<Crop>): List<String>

    /**
     * 多标签 ONNX 分类：每个区域**一组**激活标签。
     *
     * 单独一个方法而不是复用 [classify]：`mirror_path` 一次给出三条路径各自连到
     * 哪些节点（9 个连接位 sigmoid 逐位判定），返回的是一组而不是一个，
     * 塞进 `List<String>` 会分不清「每区域一个」还是「一个区域多个」。
     */
    suspend fun classifyMultiLabel(model: String, regions: List<Crop>): List<List<String>>

    // ---- 以下四个上游各只有一处调用，优先级最低 ----

    /** 带颜色的模板匹配 */
    suspend fun colorTemplateMatch(template: String, threshold: Double = 0.7, crop: Crop? = null): List<Match>

    /** 特征点匹配，抗缩放 */
    suspend fun featureMatch(template: String, threshold: Double = 0.7, crop: Crop? = null): List<Match>

    /** 金字塔多尺度模板匹配 */
    suspend fun pyramidTemplateMatch(template: String, threshold: Double = 0.7, crop: Crop? = null): List<Match>

    /** 严格模板匹配（更高精度、更慢） */
    suspend fun preciseTemplateMatch(template: String, threshold: Double = 0.7, crop: Crop? = null): List<Match>
}
