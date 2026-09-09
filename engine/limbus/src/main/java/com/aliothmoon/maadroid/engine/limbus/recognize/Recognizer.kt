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
 * 注意上游这个参数名叫 mask 但语义是**裁剪**（`mask_screenshot` 做的是 crop），
 * 且返回坐标会加回偏移。照抄语义以便流水线参数无需转换。
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

    /** 战斗专用：同一帧内定位、裁剪并分类，标签与中心坐标不可分开取帧。 */
    suspend fun battleSkillIcons(): List<BattleSkillIcon> = emptyList()

    /** 血条识别点（也是长按点）及头像边缘的 EGO 亮度分组。 */
    suspend fun battleSinnerAvatars(): List<BattleSinnerAvatar> = emptyList()

    /** 同一帧的 EGO 卡片与 0% 侵蚀标识。null 表示无法观察，不能当作面板已关闭。 */
    suspend fun battleEgoPanel(): BattleEgoPanel? = null

    /** 灰度模板匹配。[template] 是素材基名（不含扩展名与语言目录） */
    suspend fun templateMatch(
        template: String,
        threshold: Double = 0.85,
        crop: Crop? = null,
        maskTemplate: Crop? = null,
        screenshotScale: Double = 1.0,
    ): List<Match>

    /** 文本检测：返回画面里所有识别到的文本块 */
    suspend fun detectText(crop: Crop? = null, threshold: Double = 0.3): List<TextMatch>

    /** 文本查找：包含目标子串，对应上游 find_text_in_image；threshold 是 OCR 置信度。 */
    suspend fun findText(target: String, crop: Crop? = null, threshold: Double = 0.5): List<TextMatch>

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
