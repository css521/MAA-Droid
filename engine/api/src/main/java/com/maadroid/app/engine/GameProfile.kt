package com.maadroid.app.engine

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * 一个游戏方案的静态描述：宿主靠它决定「能做什么、怎么起游戏、屏幕开多大」，
 * 而不需要认识具体引擎。
 *
 * 新游戏在独立模块提供 [EngineProvider]，接入构建并由 App 注册；
 * profile 描述能力，具体的执行、资源与 UI 仍需通过相应契约实现。
 */
interface GameProfile {

    /** 稳定标识，同时用于跨进程取引擎、资源包归属、偏好键前缀。见 `EngineIds` */
    val id: String

    @get:StringRes
    val displayNameRes: Int

    @get:DrawableRes
    val iconRes: Int

    /**
     * 游戏的包名候选（多渠道/多服会有多个）。宿主用它拉起游戏、探测存活、
     * 把飘到别的显示器上的任务拉回虚拟屏。
     */
    val gamePackages: List<String>

    /**
     * 该方案要求的显示规格。方舟与边狱均以 1280x720 横屏作为坐标基准；
     * Android 与桌面布局仍可能不同，模板及导航适配需独立验证。
     */
    val display: DisplaySpec

    /** 该方案需要的资源包（模板图 / 流水线 / 模型 / 语言包），见 [ResourcePackSpec] */
    val resourcePacks: List<ResourcePackSpec>

    /** 引擎支持的能力声明；入口及设备适配仍需宿主接线 */
    val capabilities: Set<Capability>
}

/**
 * @param width 逻辑宽（像素）
 * @param height 逻辑高（像素）
 * @param dpi 虚拟显示器 dpi；过低会让游戏走平板布局，过高会让 UI 元素超出模板尺寸
 */
data class DisplaySpec(val width: Int, val height: Int, val dpi: Int = 320) {
    init {
        require(width > 0 && height > 0) { "invalid display spec: ${width}x$height" }
    }
}

/** 引擎可选能力。宿主按此裁剪 UI，避免为不支持的引擎显示空白入口。 */
enum class Capability {
    /** 支持后台虚拟显示器无界面运行 */
    BACKGROUND_DISPLAY,

    /** 支持定时任务 */
    SCHEDULE,

    /** 支持外部作业/抄作业（方舟的 copilot） */
    COPILOT,

    /** 支持任务链多任务编排；不支持者只能单任务 */
    TASK_CHAIN,

    /** 支持运行中实时截图预览 */
    LIVE_PREVIEW,
}
