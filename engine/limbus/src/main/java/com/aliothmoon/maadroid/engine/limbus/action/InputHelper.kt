package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.InputSink

/**
 * 输入便利方法。上游 LALC 的 input_handler 提供 click / swipe / key_press / long_press，
 * 这里对 InputSink 做同样的封装。坐标都是 1280x720 逻辑坐标。
 */
object InputHelper {

    fun click(input: InputSink, x: Int, y: Int) {
        input.touchDown(x, y)
        input.touchUp(x, y)
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

    fun swipe(input: InputSink, x1: Int, y1: Int, x2: Int, y2: Int, steps: Int = 8) {
        input.touchDown(x1, y1)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val cx = (x1 + (x2 - x1) * t).toInt()
            val cy = (y1 + (y2 - y1) * t).toInt()
            input.touchMove(cx, cy)
        }
        input.touchUp(x2, y2)
    }

    fun keyPress(input: InputSink, keyName: String) {
        val code = KeyMap.resolve(keyName)
        input.keyDown(code)
        input.keyUp(code)
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
        input.touchDown(x, y)
        delay(durationSec)
        input.touchUp(x, y)
    }
}
