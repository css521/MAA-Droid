package com.aliothmoon.maadroid.engine.limbus

import com.aliothmoon.maadroid.engine.Capability
import com.aliothmoon.maadroid.engine.DisplaySpec
import com.aliothmoon.maadroid.engine.GameProfile
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.remote.EngineIds

/**
 * 边狱公司（Limbus Company）游戏方案。
 */
object LimbusProfile : GameProfile {

    override val id: String = EngineIds.LIMBUS

    override val displayNameRes: Int = R.string.limbus_game_name

    override val iconRes: Int = R.drawable.ic_limbus

    /**
     * 官方 Android 客户端包名。多渠道包若出现，在此追加。
     */
    override val gamePackages: List<String> = listOf("com.ProjectMoon.LimbusCompany")

    /**
     * 1280x720 横屏。
     *
     * 这个数字不是随手取的：上游 LALC 的目标客户区就是 1280x720
     * （input/game_window.py 的 set_window_to_top 默认值），全部 622 张模板都按此尺寸截取。
     * 而 AALC 的素材树只按 UI 主题与语言分目录、无 pc/android 之分却同时驱动 Steam 端与
     * 模拟器内的 Android 端 —— 说明边狱两端共用一套 UI 布局，只差分辨率缩放。
     * 因此强制虚拟显示器到 1280x720 后，上游 PC 端模板可直接用于 Android 客户端。
     */
    override val display: DisplaySpec = DisplaySpec(width = 1280, height = 720, dpi = 320)

    override val resourcePacks: List<ResourcePackSpec> = listOf(LimbusResourcePack)

    /**
     * 暂不声明 COPILOT（边狱没有抄作业生态）。
     * LIVE_PREVIEW 与 BACKGROUND_DISPLAY 复用宿主既有能力，与引擎无关。
     */
    override val capabilities: Set<Capability> = setOf(
        Capability.BACKGROUND_DISPLAY,
        Capability.SCHEDULE,
        Capability.TASK_CHAIN,
        Capability.LIVE_PREVIEW,
    )
}
