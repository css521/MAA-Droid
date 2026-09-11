package com.maadroid.app.domain.models

import com.maadroid.app.constant.DisplayMode

enum class RunMode(
    val displayMode: Int
) {
    FOREGROUND(DisplayMode.PRIMARY),

    BACKGROUND(DisplayMode.BACKGROUND)
}
