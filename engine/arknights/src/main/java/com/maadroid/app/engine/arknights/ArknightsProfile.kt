package com.maadroid.app.engine.arknights

import com.maadroid.app.constant.DefaultDisplayConfig
import com.maadroid.app.engine.Capability
import com.maadroid.app.engine.DisplaySpec
import com.maadroid.app.engine.GameProfile
import com.maadroid.app.engine.ResourcePackSpec
import com.maadroid.app.engine.arknights.resource.MaaResourcePack
import com.maadroid.app.remote.EngineIds

/**
 * 明日方舟游戏方案。
 *
 * 由方舟引擎声明客户端、显示规格与资源包，供宿主读取。
 */
object ArknightsProfile : GameProfile {

    override val id: String = EngineIds.ARKNIGHTS

    override val displayNameRes: Int = R.string.arknights_game_name

    override val iconRes: Int = R.drawable.ic_arknights_logo

    /** 六个渠道服。宿主用它探测装了哪个客户端 */
    override val gamePackages: List<String> = ArknightsPackages.map { it.value }

    /**
     * 1280x720 / dpi 160。dpi 与边狱（320）不同是刻意的：方舟在 160 下是手机布局，
     * 而 MAA 的模板正是按该布局截取的；调高会切到平板布局导致模板失配。
     */
    override val display: DisplaySpec = DisplaySpec(
        width = DefaultDisplayConfig.WIDTH,
        height = DefaultDisplayConfig.HEIGHT,
        dpi = DefaultDisplayConfig.DPI,
    )

    override val resourcePacks: List<ResourcePackSpec> = listOf(MaaResourcePack)

    /** 方舟有抄作业生态（copilot），边狱没有 —— 宿主据此裁剪入口 */
    override val capabilities: Set<Capability> = setOf(
        Capability.BACKGROUND_DISPLAY,
        Capability.SCHEDULE,
        Capability.COPILOT,
        Capability.TASK_CHAIN,
        Capability.LIVE_PREVIEW,
    )
}
