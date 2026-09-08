package com.aliothmoon.maadroid.data.notification.provider

import com.aliothmoon.maadroid.common.i18n.UiText

sealed interface NotificationSendResult {
    data object Success : NotificationSendResult
    data class Failed(val message: UiText) : NotificationSendResult
    data class Transient(val message: UiText) : NotificationSendResult
}
