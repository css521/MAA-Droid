package com.maadroid.app.engine.limbus.action

import com.maadroid.app.engine.InputSink
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

    /** 抬手前原地驻留时长。约 11 帧（60fps），足够让 ScrollRect 的速度衰减到 0。 */
    private const val SWIPE_SETTLE_MILLIS = 180L

    /**
     * 坐标覆盖表：`"原x,原y"` → `新x to 新y`。
     *
     * 上游 LALC 的坐标全部取自 Steam 客户端，安卓客户端的 UI 布局有系统性偏移
     * （实测星光九宫格 dx≈+25, dy≈+80）。这个表让**不改任何调用点**就能修正坐标：
     * 全部 53 处 `click(ctx.input, x, y)` 都过 [click]，在这里查表即可。
     *
     * 为什么用原坐标当 key 而不是给每处起名：104 处硬编码分散在 4 个动作文件里，
     * 逐个起名并改调用点是大改动；而绝大多数坐标在安卓上本来就是对的
     * （EXP / Thread 链路已真机跑通），只有少数需要修正。
     *
     * 由 [LimbusEngine] 在 prepare 时从内嵌常量装入；将来可改为从资源包读，
     * 那时改坐标就不用重新打 285MB 的包。
     */
    @Volatile
    private var overrides: Map<String, Pair<Int, Int>> = emptyMap()

    fun applyCoordinateOverrides(table: Map<String, Pair<Int, Int>>) {
        overrides = table
    }

    private fun resolve(x: Int, y: Int): Pair<Int, Int> =
        overrides["$x,$y"] ?: (x to y)

    suspend fun click(input: InputSink, x: Int, y: Int) {
        val (px, py) = resolve(x, y)
        withRelease({ input.touchUp(px, py) }) {
            input.touchDown(px, py)
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

    /**
     * 默认 500ms：起终点各保持 100ms，中间均匀移动；步数增加时仍保留帧间隔。
     *
     * [settleMillis] 是抬手前**在终点原地持续发 touchMove** 的时长，用来消掉惯性滑动。
     *
     * 为什么非得原地发事件、光 delay 不行：Unity 的 ScrollRect 从每帧的指针位置差算速度，
     * 抬手时速度不为零就按惯性继续滚。原来末尾只有一个 `delay(100ms)`，那段时间没有任何
     * 新事件，速度追踪器停留在最后那个高值 —— 于是照样 fling。真机实测：队伍侧栏手指走
     * 216px（6 行），列表实际走了约 341px（9.4 行），**1.58 倍**；把步数从 8 加到 40
     * 也没用，因为 40 步匀速仍是 337px/s，抬手瞬间速度照样不为零。
     *
     * 默认开着：自动化想要的是"拖到哪就停在哪"，没有哪条流程需要惯性续滑。
     * 需要快速甩到列表尽头（越界会被自动夹住，无害）时传 0 换取速度。
     */
    suspend fun swipe(
        input: InputSink,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        steps: Int = 8,
        settleMillis: Long = SWIPE_SETTLE_MILLIS,
    ) {
        require(steps > 0) { "Swipe steps must be positive" }
        require(settleMillis >= 0) { "Swipe settle must not be negative" }
        @Suppress("NAME_SHADOWING") val (x1, y1) = resolve(x1, y1)
        @Suppress("NAME_SHADOWING") val (x2, y2) = resolve(x2, y2)
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
            var settled = 0L
            while (settled < settleMillis) {
                delay(MIN_MOVE_INTERVAL_MILLIS)
                settled += MIN_MOVE_INTERVAL_MILLIS
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
