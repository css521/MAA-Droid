package com.aliothmoon.maadroid.engine.limbus.recognize

/**
 * 队伍编成页的文字判据。
 *
 * 与资源包里 `config/task-patch.json` 的 `*_choose_team` 节点保持一致——那边是流水线的
 * 判据，这里是动作代码里「等页面出现」的判据，判的是同一件事，值必须一致，否则会出现
 * 「流水线认为到了、动作认为没到」这种最难查的分歧。
 *
 * 区域与文字都在真机原帧上验证过：区域外涂黑后重跑 OCR，区域内读出 "Details"
 * 置信度 0.998，且不会误撞别处文字（全屏 OCR 时 dungeon 页的警告句里就有 "enter"，
 * 说明子串匹配必须配区域限定）。
 */
internal object TeamPage {
    const val TEXT = "Details"
    val REGION = Crop(880, 100, 120, 50)
    const val THRESHOLD = 0.5
}
