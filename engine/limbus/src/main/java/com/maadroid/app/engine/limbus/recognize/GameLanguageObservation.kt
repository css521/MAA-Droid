package com.maadroid.app.engine.limbus.recognize

/** Failure to read a small/transitioning label is not evidence of another game language. */
sealed interface GameLanguageObservation {
    data object Confirmed : GameLanguageObservation
    data class Mismatch(val configured: String, val detected: String) : GameLanguageObservation
    data object Uncertain : GameLanguageObservation
}
