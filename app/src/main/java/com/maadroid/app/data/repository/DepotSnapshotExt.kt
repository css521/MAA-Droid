package com.maadroid.app.data.repository

import com.maadroid.app.data.model.toolbox.DepotItem
import com.maadroid.app.data.resource.ItemInfo

/**
 * 将持久化 map 转为展示/导出用列表，排序与识别回调一致：
 * 游戏 sortId 升序，查不到的靠后并按 id 兜底。
 */
fun DepotSnapshot.toSortedItems(itemMap: Map<String, ItemInfo>): List<DepotItem> =
    items.map { (id, count) -> DepotItem(id, count) }
        .sortedWith(
            compareBy(
                { itemMap[it.id]?.sortId ?: Int.MAX_VALUE },
                { it.id },
            )
        )
