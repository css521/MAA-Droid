package com.maadroid.app.presentation.state

import androidx.annotation.StringRes
import com.maadroid.app.common.i18n.UiText
import com.maadroid.app.common.i18n.uiTextOf

sealed interface UiEffect {

    data class Toast(val message: UiText, val long: Boolean = false) : UiEffect

    companion object {
        fun toast(@StringRes resId: Int, vararg args: Any?, long: Boolean = false) =
            Toast(uiTextOf(resId, *args), long)

        fun toast(message: String, long: Boolean = false) =
            Toast(UiText.Dynamic(message), long)

        fun toast(message: UiText, long: Boolean = false) =
            Toast(message, long)
    }
}
