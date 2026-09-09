package com.aliothmoon.maadroid.engine.arknights

object ArknightsPackages : Iterable<Map.Entry<String, String>> {
    private val packageName = mapOf(
        "Official" to "com.hypergryph.arknights",
        "Bilibili" to "com.hypergryph.arknights.bilibili",
        "YoStarEN" to "com.YoStarEN.Arknights",
        "YoStarJP" to "com.YoStarJP.Arknights",
        "YoStarKR" to "com.YoStarKR.Arknights",
        "txwy" to "tw.txwy.and.arknights"
    )

    operator fun get(type: String): String? = packageName[type]

    override fun iterator(): Iterator<Map.Entry<String, String>> {
        return packageName.iterator()
    }
}
