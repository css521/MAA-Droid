package com.aliothmoon.maadroid.engine.limbus.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliothmoon.maadroid.engine.limbus.config.LimbusTeamConfig
import com.aliothmoon.maadroid.engine.limbus.config.LimbusWorkspaceConfig
import java.io.File

@Composable
internal fun TeamsPage(config: LimbusWorkspaceConfig, change: (LimbusWorkspaceConfig) -> Unit, editable: Boolean, resources: LimbusCatalogState, root: File?) {
    var slot by rememberSaveable { mutableIntStateOf(0) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var copyTo by rememberSaveable { mutableStateOf(false) }
    val team = config.team(slot)
    val update: (LimbusTeamConfig) -> Unit = { change(config.withTeam(slot, it)) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items((0 until 20).toList()) { index -> FilterChip(selected = slot == index, onClick = { slot = index }, label = { Text("${index + 1} · ${config.team(index).teamName}") }) }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(team.teamName, { update(team.copy(teamName = it)) }, enabled = editable, singleLine = true, label = { Text("队伍名称 · 游戏槽位 ${slot + 1}") }, modifier = Modifier.weight(1f))
            TextButton(onClick = { copyTo = true }, enabled = editable) { Text("复制到") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf(0 to "出战顺序", 1 to "星光与开局", 2 to "饰品", 3 to "技能替换")) { (index, title) ->
                FilterChip(section == index, { section = index }, label = { Text(title) })
            }
        }
        resources.message?.let { message ->
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Hint(message) }
        }
        when (section) {
            0 -> MembersPage(team, update, editable, root)
            1 -> StarsPage(team, update, editable, root)
            2 -> GiftsPage(team, update, editable, resources.catalog, root)
            3 -> SkillsPage(team, update, editable, root)
        }
    }
    if (copyTo && editable) AlertDialog(onDismissRequest = { copyTo = false }, title = { Text("将 ${team.teamName} 复制到") }, text = {
        LazyColumn(Modifier.heightIn(max = 350.dp)) {
            items((0 until 20).filter { it != slot }) { index ->
                TextButton(onClick = {
                    change(config.withTeam(index, team.copy(teamName = config.team(index).teamName)))
                    copyTo = false
                }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text("${index + 1} · ${config.team(index).teamName}") }
            }
        }
    }, confirmButton = { TextButton(onClick = { copyTo = false }) { Text("取消") } })
}

@Composable
private fun MembersPage(team: LimbusTeamConfig, update: (LimbusTeamConfig) -> Unit, editable: Boolean, root: File?) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Hint("按点击顺序出战 · 已选 ${team.selectedMembers.size}/12")
                TextButton(onClick = { update(team.copy(selectedMembers = emptyList())) }, enabled = editable) { Text("清空选择") }
            }
        }
        items(LimbusWorkspaceConfig.SINNERS.chunked(3)) { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.forEach { sinner ->
                    val order = team.selectedMembers.indexOf(sinner)
                    Surface(onClick = { update(team.copy(selectedMembers = if (order >= 0) team.selectedMembers - sinner else team.selectedMembers + sinner)) }, enabled = editable,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
                        border = BorderStroke(if (order >= 0) 2.dp else 1.dp, if (order >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        color = if (order >= 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            LimbusArtwork(sinnerPath(sinner), root, Modifier.height(68.dp).fillMaxWidth())
                            Text(sinnerLabels.getValue(sinner), style = MaterialTheme.typography.labelLarge)
                            Text(if (order >= 0) "${order + 1} · 已选择" else "未选择", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        if (team.selectedMembers.isNotEmpty()) item { Hint(team.selectedMembers.mapIndexed { i, name -> "${i + 1}. ${sinnerLabels[name]}" }.joinToString(" → ")) }
        item {
            Section("队伍流派") {
                Choices(styleLabels.entries.map { it.key to it.value }, team.selectedTeamStyleType, editable) { update(team.copy(selectedTeamStyleType = it)) }
                Toggle("商店全体治疗", team.shopHealAll, editable) { update(team.copy(shopHealAll = it)) }
            }
        }
    }
}

@Composable
private fun StarsPage(team: LimbusTeamConfig, update: (LimbusTeamConfig) -> Unit, editable: Boolean, root: File?) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Section("启程 E.G.O 饰品顺序") {
                Hint("点击已有顺位移除，再按需要的顺序选择 1、2、3。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { number ->
                        val index = team.initialEgoGifts.indexOf(number)
                        FilterChip(index >= 0, { update(team.copy(initialEgoGifts = if (index >= 0) team.initialEgoGifts - number else team.initialEgoGifts + number)) }, enabled = editable, label = { Text("饰品 $number${if (index >= 0) " · 第 ${index + 1} 顺位" else ""}") })
                    }
                }
            }
        }
        item { Text("初始星光", style = MaterialTheme.typography.titleMedium); Hint("编号与 LALC 上游一致；选择基础、+ 或 ++ 强化等级。") }
        items((0..9).toList()) { index ->
            val id = "$index"
            Section {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LimbusArtwork("img/general/mirror/stars/mirror_${id.padStart(2, '0')}.png", root, Modifier.size(58.dp))
                    Column(Modifier.weight(1f)) {
                        Toggle("星光 $index", team.mirrorStarEnabled[id] == true, editable) { update(team.copy(mirrorStarEnabled = team.mirrorStarEnabled + (id to it), mirrorStarValues = team.mirrorStarValues + (id to (team.mirrorStarValues[id] ?: id)))) }
                        Choices(listOf(id to "基础", "$id+" to "+", "$id++" to "++"), team.mirrorStarValues[id] ?: id, editable) { update(team.copy(mirrorStarValues = team.mirrorStarValues + (id to it))) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GiftsPage(team: LimbusTeamConfig, update: (LimbusTeamConfig) -> Unit, editable: Boolean, catalog: LimbusCatalog, root: File?) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    var onlyConfigured by rememberSaveable { mutableStateOf(false) }
    val rows = catalog.gifts.filter { item ->
        (filter.isEmpty() || item.style == filter) && (search.isBlank() || item.name.contains(search, true) || item.title.contains(search, true)) &&
            (!onlyConfigured || team.giftName2Status[item.name] in listOf("Allow List", "Block List"))
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Section("首选饰品流派") {
                Hint("自动选取该流派饰品；单件排除的优先级最高。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    styleLabels.forEach { (key, title) ->
                        val selected = key in team.selectedPreferEgoGiftTypes
                        FilterChip(selected, { update(team.copy(selectedPreferEgoGiftTypes = if (selected) team.selectedPreferEgoGiftTypes - key else team.selectedPreferEgoGiftTypes + key)) }, enabled = editable, label = { Text(title) })
                    }
                }
            }
        }
        if (catalog.gifts.isEmpty()) {
            item { Hint("请先下载包含饰品图鉴的边狱资源，再配置单件饰品。已有允许 / 排除设置已保留。") }
            return@LazyColumn
        }
        item { InputField("搜索饰品名称", search, true) { search = it } }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(onlyConfigured, { onlyConfigured = !onlyConfigured }, label = { Text("只看已配置") }) }
                items(listOf("" to "全部流派") + styleLabels.entries.map { it.key to it.value }) { (id, title) -> FilterChip(filter == id, { filter = id }, label = { Text(title) }) }
            }
        }
        if (rows.isEmpty()) item { Hint("没有匹配的饰品") }
        items(rows, key = { it.name }) { item ->
            Section {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LimbusArtwork(item.path, root, Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) { Text(item.title, style = MaterialTheme.typography.titleSmall); Hint(item.name) }
                }
                Choices(listOf("Default" to "随流派", "Allow List" to "优先选取", "Block List" to "排除"), team.giftName2Status[item.name] ?: "Default", editable) { status ->
                    update(team.copy(giftName2Status = if (status == "Default") team.giftName2Status - item.name else team.giftName2Status + (item.name to status)))
                }
            }
        }
    }
}

@Composable
private fun SkillsPage(team: LimbusTeamConfig, update: (LimbusTeamConfig) -> Unit, editable: Boolean, root: File?) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Hint("每位罪人独立设置替换优先级，越靠上越优先。只在启用“替换技能与购买饰品”时生效。") }
        items(LimbusWorkspaceConfig.SINNERS) { sinner ->
            val order = team.skillReplacementOrders[sinner] ?: LimbusWorkspaceConfig.DEFAULT_SKILLS
            val enabled = team.skillReplacementEnabled[sinner] == true
            Section {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LimbusArtwork(sinnerPath(sinner), root, Modifier.size(42.dp))
                    Box(Modifier.weight(1f)) { Toggle(sinnerLabels.getValue(sinner), enabled, editable) { update(team.copy(skillReplacementEnabled = team.skillReplacementEnabled + (sinner to it))) } }
                }
                if (enabled) order.forEachIndexed { index, pair ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. 技能 ${pair.first()} → 技能 ${pair.last()}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        fun move(to: Int) {
                            val next = order.toMutableList().apply { add(to, removeAt(index)) }
                            update(team.copy(skillReplacementOrders = team.skillReplacementOrders + (sinner to next)))
                        }
                        TextButton(onClick = { move(index - 1) }, enabled = editable && index > 0) { Text("上移") }
                        TextButton(onClick = { move(index + 1) }, enabled = editable && index < order.lastIndex) { Text("下移") }
                    }
                }
            }
        }
    }
}

private fun sinnerPath(name: String) = "img/general/sinners/${if (name == "Ryoshu") "RyoShu" else name}.png"
