package com.maadroid.app.constant

import com.maadroid.app.engine.arknights.ArknightsPackages

/** 宿主兼容入口，客户端映射由方舟引擎维护。 */
object Packages : Iterable<Map.Entry<String, String>> {
    operator fun get(type: String): String? = ArknightsPackages[type]

    override fun iterator(): Iterator<Map.Entry<String, String>> {
        return ArknightsPackages.iterator()
    }
}
