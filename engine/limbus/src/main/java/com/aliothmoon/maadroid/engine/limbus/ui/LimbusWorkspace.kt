package com.aliothmoon.maadroid.engine.limbus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aliothmoon.maadroid.engine.EngineResources
import com.aliothmoon.maadroid.engine.EngineWorkspace
import com.aliothmoon.maadroid.engine.limbus.LimbusResourcePack
import com.aliothmoon.maadroid.ui.components.MaaSurfaceCard
import com.aliothmoon.maadroid.ui.components.TaskListDetailScaffold
import com.aliothmoon.maadroid.ui.components.TaskSelectionRow
import com.aliothmoon.maadroid.ui.components.TaskPanelTabs
import com.aliothmoon.maadroid.ui.components.TaskLogPanel
import com.aliothmoon.maadroid.ui.components.TaskLogLine
import com.aliothmoon.maadroid.ui.components.LocalTaskLogColors
import com.aliothmoon.maadroid.engine.limbus.config.*
import kotlinx.serialization.json.*
import java.io.File

/** 页面和配置都属于引擎；宿主不用认识队伍、罪人或镜牢。 */
object LimbusWorkspace : EngineWorkspace {
    override fun initialConfig(enabled: Map<String, Boolean>, taskParams: Map<String, String>) =
        LimbusWorkspaceConfig.migrate(enabled, taskParams).encode()
    override fun selectedTasks(configJson: String) = LimbusWorkspaceConfig.decode(configJson).selectedTasks()
    override fun validate(configJson: String) = runCatching { LimbusWorkspaceConfig.decode(configJson).validationError() }
        .getOrElse { "无法读取边狱配置：${it.message}" }

    @Composable
    override fun Content(configJson: String, onConfigChange: (String) -> Unit, editable: Boolean, logs: List<String>, resources: EngineResources) {
        val result = remember(configJson) { runCatching { LimbusWorkspaceConfig.decode(configJson) } }
        val config = result.getOrNull()
        if (config == null) {
            Text("配置读取失败，请检查导入文件：${result.exceptionOrNull()?.message}", Modifier.padding(16.dp))
        } else {
            WorkspaceContent(config, { if (editable) onConfigChange(it.encode()) }, editable, logs, resources.directory(LimbusResourcePack))
        }
    }
}

@Composable
private fun WorkspaceContent(config: LimbusWorkspaceConfig, change: (LimbusWorkspaceConfig) -> Unit, editable: Boolean, logs: List<String>, root: File?) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val resources by rememberLimbusCatalog(root)
    var transfer by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var clearToast by remember { mutableStateOf<String?>(null) }
    clearToast?.let { msg ->
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(msg) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            clearToast = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        TaskPanelTabs(
            labels = listOf("任务", "队伍", "卡包", "日志"),
            selectedIndex = page,
            onSelected = { page = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Box {
                TextButton(onClick = { more = true }, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(0.dp)) { Text("更多") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("导入 / 导出配置") }, onClick = { more = false; transfer = true })
                    DropdownMenuItem(text = { Text("清理采集帧") }, onClick = {
                        more = false
                        // 采集帧存在 Maa/debug/limbus/frames/，清掉后下次运行会重新采集全部界面
                        val frames = root?.let { java.io.File(it, "debug/limbus/frames") }
                        val count = frames?.listFiles()?.count { it.isFile && it.extension == "png" } ?: 0
                        frames?.listFiles()?.forEach { if (it.isFile && it.extension == "png") it.delete() }
                        clearToast = "已清理 $count 张采集帧"
                    })
                }
            }
        }
        CompositionLocalProvider(LocalLimbusResourceRevision provides resources.revision) {
            Box(Modifier.weight(1f)) {
                when (page) {
                    0 -> TasksPage(config, change, editable, onTeams = { page = 1 })
                    1 -> TeamsPage(config, change, editable, resources, root)
                    2 -> PacksPage(config, change, editable, resources, root)
                    3 -> LogsPage(logs)
                }
            }
        }
    }
    if (transfer) ConfigTransferDialog(config, editable, { change(it); transfer = false }, { transfer = false })
}

internal val taskLabels = linkedMapOf(
    "Daily Lunacy Purchase" to "狂气兑换脑啡肽", "Mail" to "领取邮件", "E.G.O" to "战斗中使用 E.G.O",
    "EXP" to "经验副本", "Thread" to "纺锤副本", "Mirror" to "镜牢", "Reward" to "领取奖励", "At Last" to "运行结束",
)
internal val sinnerLabels = LimbusWorkspaceConfig.SINNERS.zip(listOf("李箱", "浮士德", "堂吉诃德", "良秀", "默尔索", "鸿璐", "希斯克利夫", "以实玛利", "罗佳", "辛克莱", "奥提斯", "格里高尔")).toMap()
internal val styleLabels = LimbusWorkspaceConfig.STYLES.zip(listOf("流血", "烧伤", "破裂", "呼吸", "震颤", "打击", "突刺", "斩击", "充能", "沉沦", "泛用")).toMap()

@Composable
private fun TasksPage(config: LimbusWorkspaceConfig, change: (LimbusWorkspaceConfig) -> Unit, editable: Boolean, onTeams: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf("EXP") }
    TaskListDetailScaffold(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        wrapDetailInCard = true,
        taskList = { listModifier ->
            Column(
                listModifier.widthIn(max = 132.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TaskSelectionRow("游戏语言", selected == "language", { selected = "language" })
                taskLabels.forEach { (taskKey, title) ->
                    val task = config.task(taskKey)
                    TaskSelectionRow(
                        label = when (taskKey) {
                            "Daily Lunacy Purchase" -> "兑换脑啡肽"
                            "E.G.O" -> "使用 E.G.O"
                            else -> title
                        },
                        selected = selected == taskKey,
                        onSelected = { selected = taskKey },
                        checked = task.enabled,
                        onCheckedChange = { change(config.withTask(taskKey, task.copy(enabled = it))) },
                        editable = editable,
                    )
                }
            }
        },
    ) {
        key(selected) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selected == "language") {
                    Text("游戏语言", style = MaterialTheme.typography.titleSmall)
                    Choices(listOf("en" to "英文", "zh" to "简体中文"), config.language, editable) { change(config.copy(language = it)) }
                    Hint("必须与游戏内显示语言一致；界面语言不影响识别。")
                } else {
                    TaskSettings(selected, config, change, editable, onTeams)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.TaskSettings(taskKey: String, config: LimbusWorkspaceConfig, change: (LimbusWorkspaceConfig) -> Unit, editable: Boolean, onTeams: () -> Unit) {
    val task = config.task(taskKey)
    val update: (LimbusTaskConfig) -> Unit = { change(config.withTask(taskKey, it)) }
    Text(taskLabels.getValue(taskKey), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    when (taskKey) {
        "EXP", "Thread", "Mirror" -> {
            NumberField("执行次数", task.count, 1..999, editable) { update(task.copy(count = it)) }
            if (taskKey != "Mirror") {
                Choices(listOf("Enter" to "进入战斗", "Skip Battle" to "跳过战斗"), task.string("luxcavationMode", "Enter"), editable) { update(task.with("luxcavationMode", JsonPrimitive(it))) }
                val stageKey = if (taskKey == "EXP") "expStage" else "threadStage"
                InputField("关卡", task.string(stageKey, if (taskKey == "EXP") "09" else "60"), editable) { update(task.with(stageKey, JsonPrimitive(it))) }
                Hint("填写游戏内关卡编号；经验本保留前导零，如 09。")
            } else MirrorSettings(task, update, editable)
            Text("使用队伍（按选择顺序轮换）", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items((1..20).toList()) { slot ->
                    val order = task.teams.indexOf(slot)
                    FilterChip(selected = order >= 0, onClick = { update(task.copy(teams = if (order >= 0) task.teams - slot else task.teams + slot)) }, enabled = editable,
                        label = { Text("${if (order >= 0) "${order + 1}. " else ""}${config.team(slot - 1).teamName}") })
                }
            }
            TextButton(onClick = onTeams) { Text("编辑队伍与出战顺序 →") }
        }
        "Daily Lunacy Purchase" -> {
            NumberField("每日兑换目标次数", task.count, 0..10, editable) { update(task.copy(count = it)) }
            Hint("会消耗游戏内狂气；已兑换次数计入每日目标。默认关闭。")
        }
        "At Last" -> Choices(listOf("nothing" to "保持游戏打开", "close_game" to "结束游戏"), task.string("action", "nothing"), editable) { update(task.with("action", JsonPrimitive(it))) }
        "E.G.O" -> Hint("战斗识别到危险状态时启用 E.G.O。")
        "Mail", "Reward" -> Hint("启用后自动领取，无需额外配置。")
    }
}

@Composable
private fun MirrorSettings(task: LimbusTaskConfig, update: (LimbusTaskConfig) -> Unit, editable: Boolean) {
    Choices(listOf("normal" to "普通镜牢", "hard" to "困难镜牢"), task.string("mirror_mode", "normal"), editable) { update(task.with("mirror_mode", JsonPrimitive(it))) }
    NumberField("保留用于强化的货币", task.number("stopPurchaseGiftMoney", 600), 0..99999, editable) { update(task.with("stopPurchaseGiftMoney", JsonPrimitive(it))) }
    listOf("enable_fuse_ego_gifts" to "融合饰品", "enable_replace_skill_purchase_ego_gifts" to "替换技能与购买饰品", "enable_enhance_ego_gifts" to "强化饰品", "accept_reward" to "领取镜牢奖励").forEach { (key, title) ->
        Toggle(title, task.flag(key, true), editable) { update(task.with(key, JsonPrimitive(it))) }
    }
    var weightsExpanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { weightsExpanded = !weightsExpanded }) { Text(if (weightsExpanded) "收起路线权重" else "设置路线权重") }
    if (weightsExpanded) {
        val scores = task.params["node_scores"] as? JsonObject ?: LimbusWorkspaceConfig.defaultNodeScores()
        listOf("event" to "事件", "regular_encounter" to "普通战斗", "elite_encounter" to "精英战斗", "focused_encounter" to "集中战斗", "abnormality_encounter" to "异想体战斗", "shop" to "商店", "boss_encounter" to "首领").forEach { (id, title) ->
            val key = "node_$id"
            NumberField(title, (scores[key] as? JsonPrimitive)?.intOrNull ?: 0, -100..100, editable) { update(task.with("node_scores", JsonObject(scores + (key to JsonPrimitive(it))))) }
        }
        Hint("权重越高越优先；这是 LALC 路径评分配置。")
    }
}

@Composable
internal fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    MaaSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable internal fun Hint(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable internal fun Toggle(title: String, checked: Boolean, editable: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, change, enabled = editable)
    }
}
@Composable internal fun Choices(items: List<Pair<String, String>>, value: String, editable: Boolean, change: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (key, title) -> FilterChip(selected = value == key, onClick = { change(key) }, enabled = editable, label = { Text(title) }) }
    }
}
@Composable internal fun InputField(title: String, value: String, editable: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(title) }, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
}
@Composable internal fun NumberField(title: String, value: Int, range: IntRange, editable: Boolean, change: (Int) -> Unit) {
    var raw by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(raw, { text ->
        raw = text
        text.toIntOrNull()?.takeIf { it in range }?.let(change)
    }, enabled = editable, label = { Text(title) }, singleLine = true,
        isError = raw.toIntOrNull() !in range,
        supportingText = if (raw.toIntOrNull() !in range) ({ Text("请输入 ${range.first}–${range.last}") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PacksPage(config: LimbusWorkspaceConfig, change: (LimbusWorkspaceConfig) -> Unit, editable: Boolean, resources: LimbusCatalogState, root: File?) {
    val catalog = resources.catalog
    if (resources.message != null || catalog.packs.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Hint(resources.message ?: "资源中没有主题卡包图鉴，请更新或重新下载边狱资源。已有权重已保留。")
        }
        return
    }
    var search by rememberSaveable { mutableStateOf("") }
    var weighted by rememberSaveable { mutableStateOf(false) }
    val rows = catalog.packs.filter { it.title.contains(search, true) || it.name.contains(search, true) }
        .let { if (weighted) it.sortedByDescending { it.weight(config.themePackWeights) } else it.sortedBy { it.name } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        InputField("搜索主题卡包", search, true) { search = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(weighted, { weighted = !weighted }, label = { Text(if (weighted) "按权重排序" else "按名称排序") })
            Spacer(Modifier.width(12.dp)); Hint("${rows.size} 个 · 数值越大越优先")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(rows, key = { it.name }) { item ->
                Section {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LimbusArtwork(item.path, root, Modifier.width(80.dp).height(72.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            NumberField("权重", item.weight(config.themePackWeights), 0..999, editable) { change(config.copy(themePackWeights = config.themePackWeights + (item.name to it))) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsPage(logs: List<String>) {
    val clipboard = LocalClipboardManager.current
    TaskLogPanel(
        title = "执行日志",
        count = logs.size,
        latestDescription = "跳至最新日志",
        lastEntryKey = logs.lastOrNull(),
        actions = {
            IconButton(onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) }, enabled = logs.isNotEmpty()) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制日志", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        emptyContent = { Hint("运行任务后，识别与执行日志会显示在这里") },
    ) { index ->
        val raw = logs[index]
        val prefix = raw.takeIf { it.startsWith("[") && ']' in it }?.substringBefore(']')?.removePrefix("[")
            ?.takeIf { it in setOf("Trace", "Debug", "Info", "Warn", "Error") }
        // These prefixes only style a log row; the host supplies the actual failure state.
        val color = when (prefix) {
            "Error" -> LocalTaskLogColors.current.error
            "Warn" -> LocalTaskLogColors.current.warning
            "Info" -> LocalTaskLogColors.current.info
            "Trace", "Debug" -> LocalTaskLogColors.current.trace
            else -> MaterialTheme.colorScheme.onSurface
        }
        SelectionContainer {
            TaskLogLine(
                message = if (prefix != null) raw.substringAfter(']').trimStart() else raw,
                color = color,
                levelLabel = prefix,
            )
        }
    }
}

@Composable
private fun ConfigTransferDialog(config: LimbusWorkspaceConfig, editable: Boolean, apply: (LimbusWorkspaceConfig) -> Unit, dismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var raw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("导入 / 导出配置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint("复制当前配置，或粘贴含 taskConfigs、teamConfigs、themePackWeights 的 LALC 配置。导入会替换当前边狱配置。")
            OutlinedTextField(raw, { raw = it; error = null }, enabled = editable, label = { Text("配置 JSON") }, modifier = Modifier.heightIn(max = 220.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = editable && raw.isNotBlank(), onClick = {
            runCatching {
                val root = LimbusWorkspaceConfig.json.parseToJsonElement(raw).jsonObject
                require(root["taskConfigs"] is JsonObject && root["teamConfigs"] is JsonObject) { "缺少 taskConfigs 或 teamConfigs" }
                LimbusWorkspaceConfig.decode(raw).also { require(it.schemaVersion == 1) { "不支持的配置版本" } }
            }.onSuccess(apply).onFailure { error = it.message }
        }) { Text("导入并保存") }
    }, dismissButton = {
        Row {
            TextButton(onClick = { clipboard.setText(AnnotatedString(config.encode())) }) { Text("复制当前配置") }
            TextButton(onClick = dismiss) { Text("关闭") }
        }
    })
}
