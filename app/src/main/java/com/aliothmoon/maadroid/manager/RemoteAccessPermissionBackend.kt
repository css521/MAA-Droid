package com.aliothmoon.maadroid.manager

import com.aliothmoon.maadroid.domain.models.RemoteBackend

fun interface RemoteAccessStateListener {
    fun onStateChanged(backend: RemoteBackend)
}

interface RemoteAccessPermissionBackend {
    val backend: RemoteBackend

    fun isAvailable(): Boolean

    fun isGranted(): Boolean

    suspend fun requestPermission(): Boolean

    fun addStateListener(listener: RemoteAccessStateListener)

    fun removeStateListener(listener: RemoteAccessStateListener)
}
