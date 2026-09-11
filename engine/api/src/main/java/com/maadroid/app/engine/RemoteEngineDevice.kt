package com.maadroid.app.engine

import android.os.IBinder

/** Optional device capability for engines whose service runs in the remote process. */
interface RemoteEngineDevice : DeviceHandle {
    /** Display metadata; reading it must not initialize an engine or load native libraries. */
    val displaySpec: DisplaySpec

    /** The owned session's display, or an explicitly supplied legacy display. */
    val displayId: Int

    /**
     * Resolve an engine service while this handle and its session are active.
     * Returns null when the remote process has no service for [engineId].
     * A closed handle or expired session must reject the lookup.
     */
    fun engineService(engineId: String): IBinder?
}
