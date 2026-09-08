package com.aliothmoon.maadroid.engine.limbus.recognize

/**
 * 素材索引，对应上游 `recognize/img_registry.py` 的 `IMG_REGISTRY` + `TAG_REGISTRY`。
 *
 * 两条语义必须照抄，否则镜牢的饰品筛选会静默失效：
 *
 * 1. **按基名索引**：`img/zh/x.png` 与 `img/en/x.png` 是同一个基名 `x` 的语言变体，
 *    靠加载语言目录时后者覆盖前者，而不是两个不同素材。资源包不可按基名去重。
 * 2. **按目录逐层打 tag**：`img/general/ego_gifts/Burn/a.png` 同时属于 tag
 *    `ego_gifts` 与 `ego_gifts_Burn`。上游 `mirror_team_ego_gift_styles` 配的是
 *    体系名（Burn/Bleed/…），要靠 `ego_gifts_<体系>` 展开成具体饰品名单
 *    （实测 12 个体系共 332 张）。
 */
interface TemplateIndex {

    /** 该 tag 下的全部素材基名；tag 不存在时返回空表 */
    fun namesByTag(tag: String): List<String>

    /** 是否存在此基名的素材 */
    operator fun contains(name: String): Boolean
}

/** 空索引，供不涉及 tag 的单测与降级路径使用 */
object EmptyTemplateIndex : TemplateIndex {
    override fun namesByTag(tag: String): List<String> = emptyList()
    override fun contains(name: String): Boolean = false
}
