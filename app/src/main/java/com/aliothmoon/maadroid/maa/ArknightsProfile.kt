package com.aliothmoon.maadroid.maa

import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.constant.DefaultDisplayConfig
import com.aliothmoon.maadroid.constant.Packages
import com.aliothmoon.maadroid.engine.Capability
import com.aliothmoon.maadroid.engine.DisplaySpec
import com.aliothmoon.maadroid.engine.GameProfile
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.remote.EngineIds

/**
 * 明日方舟游戏方案。
 *
 * 把既有的散落常量（Packages、DefaultDisplayConfig）收敛成一份方案声明，让宿主
 * 与边狱一视同仁地对待方舟 —— 这是「同一应用操控两个游戏」在应用层的前提。
 *
 * 随 maa 包一起归入 engine-arknights 模块。
 */
object ArknightsProfile : GameProfile {

    override val id: String = EngineIds.ARKNIGHTS

    override val displayNameRes: Int = R.string.arknights_game_name

    override val iconRes: Int = R.drawable.ic_maa_logo

    /** 六个渠道服。宿主用它探测装了哪个客户端 */
    override val gamePackages: List<String> = Packages.map { it.value }

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
