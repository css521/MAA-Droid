package com.maadroid.app.presentation.view.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PreviewSurfaceLifecycleTest {
    private class SurfaceToken(var valid: Boolean = true) {
        override fun equals(other: Any?) = other is SurfaceToken
        override fun hashCode() = 0
    }

    private class Fixture {
        val attached = mutableListOf<SurfaceToken>()
        val detached = mutableListOf<SurfaceToken>()
        val lifecycle = PreviewSurfaceLifecycle<SurfaceToken>(
            isValid = { it.valid },
            onAttach = { attached += it },
            onDetach = { detached += it },
        )
    }

    @Test fun retainsSurfaceUntilPageBecomesVisible() {
        val f = Fixture()
        val surface = SurfaceToken()
        f.lifecycle.surfaceAvailable(surface)
        assertEquals(0, f.attached.size)
        f.lifecycle.setVisible(true)
        assertSame(surface, f.attached.single())
    }

    @Test fun repeatedCallbacksDoNotReattach() {
        val f = Fixture()
        val surface = SurfaceToken()
        f.lifecycle.setVisible(true)
        f.lifecycle.surfaceAvailable(surface)
        f.lifecycle.surfaceAvailable(surface)
        f.lifecycle.setVisible(true)
        assertEquals(1, f.attached.size)
        assertEquals(0, f.detached.size)
    }

    @Test fun lateDestroyUsesIdentityAndPreservesReplacement() {
        val f = Fixture()
        val old = SurfaceToken()
        val replacement = SurfaceToken()
        f.lifecycle.setVisible(true)
        f.lifecycle.surfaceAvailable(old)
        f.lifecycle.surfaceAvailable(replacement)
        f.lifecycle.surfaceDestroyed(old)
        assertEquals(2, f.attached.size)
        assertSame(replacement, f.attached.last())
        assertEquals(1, f.detached.size)
        assertSame(old, f.detached.single())
        f.lifecycle.dispose()
        assertSame(replacement, f.detached.last())
    }

    @Test fun pageStopDetachesAndResumeReusesTheSameSurface() {
        val f = Fixture()
        val surface = SurfaceToken()
        f.lifecycle.setVisible(true)
        f.lifecycle.surfaceAvailable(surface)
        f.lifecycle.setVisible(false)
        assertSame(surface, f.detached.single())
        f.lifecycle.setVisible(true)
        assertEquals(2, f.attached.size)
        assertSame(surface, f.attached.last())
    }

    @Test fun disposalDetachesOnceAndIgnoresLateCallbacks() {
        val f = Fixture()
        val surface = SurfaceToken()
        f.lifecycle.setVisible(true)
        f.lifecycle.surfaceAvailable(surface)
        f.lifecycle.dispose()
        f.lifecycle.surfaceDestroyed(surface)
        f.lifecycle.dispose()
        f.lifecycle.surfaceAvailable(SurfaceToken())
        f.lifecycle.setVisible(true)
        assertEquals(1, f.attached.size)
        assertEquals(1, f.detached.size)
        assertSame(surface, f.detached.single())
    }

    @Test fun invalidSurfaceIsNotAttachedOnResume() {
        val f = Fixture()
        val surface = SurfaceToken()
        f.lifecycle.setVisible(true)
        f.lifecycle.surfaceAvailable(surface)
        f.lifecycle.setVisible(false)
        surface.valid = false
        f.lifecycle.setVisible(true)
        assertEquals(1, f.attached.size)
        assertEquals(1, f.detached.size)
    }
}
