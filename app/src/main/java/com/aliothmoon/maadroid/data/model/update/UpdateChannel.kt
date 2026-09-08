package com.aliothmoon.maadroid.data.model.update

import androidx.annotation.StringRes
import com.aliothmoon.maadroid.R

enum class UpdateChannel(
    val value: String,
    @param:StringRes val resId: Int
) {
    STABLE("stable", R.string.update_channel_stable),
    BETA("beta", R.string.update_channel_beta)
}
