package com.maadroid.app.data.notification.provider

interface NotificationProvider {
    val id: String
    suspend fun send(title: String, content: String): NotificationSendResult
}
