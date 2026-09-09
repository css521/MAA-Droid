package com.aliothmoon.maadroid.engine

/** Synchronous startup breadcrumbs, persisted by the host before entering native code. */
fun interface EngineDiagnosticSink {
    fun record(phase: String, detail: String)
}
