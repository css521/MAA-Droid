package com.maadroid.app.engine.limbus.pipeline

/** Execution facts for task accounting; observing them does not change routing. */
interface PipelineObserver {
    /** Includes action aliases, so task entries do not need a native action backend. */
    fun onNodeEntered(name: String) {}

    /** The check action has run and its shared counter is about to control routing. */
    fun onCheckEvaluated(name: String, disableNode: String?, count: Int, target: Int) {}
}
