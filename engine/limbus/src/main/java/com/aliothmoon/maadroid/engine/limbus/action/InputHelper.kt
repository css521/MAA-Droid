package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.InputSink
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 输入便利方法。上游 LALC 的 input_handler 提供 click / swipe / key_press / long_press，
 * 这里对 InputSink 做同样的封装。坐标都是 1280x720 逻辑坐标。
 */
object InputHelper {

    private const val PRESS_MILLIS = 100L
    private const val SWIPE_MOTION_MILLIS = 300L
    private const val MIN_MOVE_INTERVAL_MILLIS = 16L

    suspend fun click(input: InputSink, x: Int, y: Int) {
        withRelease({ input.touchUp(x, y) }) {
            input.touchDown(x, y)
            delay(PRESS_MILLIS)
        }
    }

    suspend fun clickRepeat(
        input: InputSink,
        x: Int,
        y: Int,
        repeat: Int,
        intervalSec: Double,
        delay: suspend (Double) -> Unit,
    ) {
        click(input, x, y)
        for (i in 1 until repeat) {
            if (intervalSec > 0) delay(intervalSec)
            click(input, x, y)
        }
    }

    /** 默认 500ms：起终点各保持 100ms，中间均匀移动；步数增加时仍保留帧间隔。 */
    suspend fun swipe(input: InputSink, x1: Int, y1: Int, x2: Int, y2: Int, steps: Int = 8) {
        require(steps > 0) { "Swipe steps must be positive" }
        val motionMillis = maxOf(SWIPE_MOTION_MILLIS, steps.toLong() * MIN_MOVE_INTERVAL_MILLIS)
        val intervalMillis = motionMillis / steps
        val remainder = motionMillis % steps
        var x = x1
        var y = y1
        withRelease({ input.touchUp(x, y) }) {
            input.touchDown(x1, y1)
            delay(PRESS_MILLIS)
            for (i in 1..steps) {
                delay(intervalMillis + if (i <= remainder) 1 else 0)
                val t = i.toDouble() / steps
                // 注入失败也可能已送达设备；在最后尝试的位置释放，避免跳到终点。
                x = (x1 + (x2 - x1) * t).toInt()
                y = (y1 + (y2 - y1) * t).toInt()
                input.touchMove(x, y)
            }
            delay(PRESS_MILLIS)
        }
    }

    suspend fun keyPress(input: InputSink, keyName: String) {
        val code = KeyMap.resolve(keyName)
        withRelease({ input.keyUp(code) }) {
            input.keyDown(code)
            delay(PRESS_MILLIS)
        }
    }

    suspend fun keyRepeat(
        input: InputSink,
        keyName: String,
        repeat: Int,
        intervalSec: Double,
        delay: suspend (Double) -> Unit,
    ) {
        keyPress(input, keyName)
        for (i in 1 until repeat) {
            if (intervalSec > 0) delay(intervalSec)
            keyPress(input, keyName)
        }
    }

    suspend fun longPress(
        input: InputSink,
        x: Int,
        y: Int,
        durationSec: Double,
        delay: suspend (Double) -> Unit,
    ) {
        withRelease({ input.touchUp(x, y) }) {
            input.touchDown(x, y)
            delay(durationSec)
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun withRelease(release: () -> Unit, action: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        var failure: Throwable? = null
        try {
            // DOWN 传输抛错时也可能已注入，仍须尝试 UP。
            action()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                // InputSink 的 UP 为同步调用，已取消的协程也会执行这段清理。
                release()
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
    }
}
