package com.aliothmoon.maadroid.presentation.view.engine

/**
 * UI-thread ownership of a borrowed Surface. Identity matters: a late callback from an old
 * view must not detach its replacement. The SurfaceHolder, not this class, releases surfaces.
 */
internal class PreviewSurfaceLifecycle<T : Any>(
    private val isValid: (T) -> Boolean,
    private val onAttach: (T) -> Unit,
    private val onDetach: (T) -> Unit,
) {
    private var surface: T? = null
    private var attached: T? = null
    private var visible = false
    private var disposed = false

    fun surfaceAvailable(value: T) {
        if (!disposed) {
            surface = value
            update()
        }
    }

    fun surfaceDestroyed(value: T) {
        if (surface === value) {
            surface = null
            update()
        }
    }

    fun setVisible(value: Boolean) {
        visible = value
        update()
    }

    fun dispose() {
        disposed = true
        surface = null
        update()
    }

    private fun update() {
        val target = surface?.takeIf { !disposed && visible && isValid(it) }
        if (attached !== target) {
            val previous = attached
            attached = null
            if (previous != null) onDetach(previous)
            attached = target
            if (target != null) onAttach(target)
        }
    }
}
