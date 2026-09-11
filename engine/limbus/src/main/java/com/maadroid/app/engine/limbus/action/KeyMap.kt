package com.maadroid.app.engine.limbus.action

import android.view.KeyEvent

/**
 * LALC 键名 → Android keycode。
 *
 * LALC 用 pydirectinput 的名字（"enter" / "esc" / "p"），
 * AALC 用 AALC key_list 确认了边狱 Android 端认这些 keycode。
 */
object KeyMap {

    private val map = mapOf(
        "enter" to KeyEvent.KEYCODE_ENTER,
        "esc" to KeyEvent.KEYCODE_ESCAPE,
        "escape" to KeyEvent.KEYCODE_ESCAPE,
        "p" to KeyEvent.KEYCODE_P,
        "space" to KeyEvent.KEYCODE_SPACE,
        "tab" to KeyEvent.KEYCODE_TAB,
        "backspace" to KeyEvent.KEYCODE_DEL,
    )

    fun resolve(name: String): Int =
        map[name.lowercase()] ?: throw IllegalArgumentException("未知按键名: $name")
}
