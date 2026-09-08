package com.aliothmoon.maadroid.utils.i18n

open class LocalizedException(val uiText: UiText, cause: Throwable? = null) : Exception(cause)
