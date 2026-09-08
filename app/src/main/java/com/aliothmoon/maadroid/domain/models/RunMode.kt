package com.aliothmoon.maadroid.domain.models

import com.aliothmoon.maadroid.constant.DisplayMode

enum class RunMode(
    val displayMode: Int
) {
    FOREGROUND(DisplayMode.PRIMARY),

    BACKGROUND(DisplayMode.BACKGROUND)
}
