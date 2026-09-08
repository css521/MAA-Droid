package com.aliothmoon.maadroid.common.i18n

/**
 * 带可本地化原因的异常。
 *
 * 抛出点通常在非 UI 层（拿不到 Context），而展示点在 UI 层 —— 用 [UiText] 承载原因
 * 才能让同一个异常在切换语言后显示对应文案。
 */
open class LocalizedException(val uiText: UiText, cause: Throwable? = null) : Exception(cause)
