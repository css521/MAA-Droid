package com.aliothmoon.maadroid.domain.notification

import android.os.Build

object OemLivePolicy {
    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", true)
}
