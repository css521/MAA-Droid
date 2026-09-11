package com.maadroid.app.engine

/** Native linkage errors are recoverable startup failures; VM-fatal errors must still propagate. */
internal fun Throwable.isRecoverableEngineFailure(): Boolean = this is Exception || this is LinkageError
