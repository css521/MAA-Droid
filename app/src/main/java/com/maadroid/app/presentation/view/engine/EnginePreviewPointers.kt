package com.maadroid.app.presentation.view.engine

import com.maadroid.app.engine.EngineDeviceSession
import kotlin.math.min

/** Compose pointer IDs are not Android contact IDs. Reserve 0..7 for the pipeline. */
internal class EnginePreviewPointers {
    private val contacts = mutableMapOf<Long, Int>()

    fun acquire(pointerId: Long): Int {
        contacts[pointerId]?.let { return it }
        val slot = EngineDeviceSession.MANUAL_CONTACTS.firstOrNull { it !in contacts.values } ?: return -1
        contacts[pointerId] = slot
        return slot
    }

    fun find(pointerId: Long): Int = contacts[pointerId] ?: -1
    fun release(pointerId: Long): Int = contacts.remove(pointerId) ?: -1
}

internal data class EnginePreviewPoint(val x: Int, val y: Int, val inside: Boolean)

/** Ignore downs in letterboxing; clamp a drag/up outside the picture to its nearest edge. */
internal fun enginePreviewPoint(
    x: Float,
    y: Float,
    viewWidth: Int,
    viewHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
): EnginePreviewPoint? {
    if (!x.isFinite() || !y.isFinite() || viewWidth <= 0 || viewHeight <= 0 ||
        displayWidth <= 0 || displayHeight <= 0) return null
    val scale = min(viewWidth.toFloat() / displayWidth, viewHeight.toFloat() / displayHeight)
    val left = (viewWidth - displayWidth * scale) / 2
    val top = (viewHeight - displayHeight * scale) / 2
    val px = (x - left) / scale
    val py = (y - top) / scale
    return EnginePreviewPoint(
        px.toInt().coerceIn(0, displayWidth - 1),
        py.toInt().coerceIn(0, displayHeight - 1),
        px >= 0 && px < displayWidth && py >= 0 && py < displayHeight,
    )
}
