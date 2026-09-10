package com.aliothmoon.maadroid.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import timber.log.Timber

/**
 * 各引擎的任务勾选与参数。
 *
 * 与方舟现有的 `TaskChainState` 刻意分开，理由不是洁癖：`TaskChainState` 已经把
 * `TaskChainNode` / `TaskProfile` / `RecruitConfig` 这些方舟类型写进了公开 API、
 * 序列化格式与业务分支（`:65/:143/:305/:334`），任何引擎想用它都得先依赖方舟。
 * 这里只存两样**引擎无关**的东西：
 *
 * - 勾了哪些任务（`taskType` 字符串）
 * - 每个任务的参数（引擎自己产出、自己解释的 JSON，宿主原样存取）
 *
 * 这正对上 `AutomationEngine.appendTask(type, paramsJson)` 的形状 —— 宿主不解释
 * 参数结构，所以引擎新增任务不需要改宿主一行代码。
 *
 * 方舟将来收拢为 `AutomationEngine` 时应迁到这里；届时 `TaskChainState` 里的方舟
 * 类型留在 `engine/arknights`，宿主侧只剩下这份泛化状态。
 */
class EngineTaskStore internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.engineTaskStore)

    /**
     * 一个引擎的全部任务状态。
     *
     * 按引擎整体存一条 JSON 而不是每任务一个 key：任务集合随资源包热更可能增减，
     * 逐 key 存会留下一堆再也不会被读到的孤儿键。
     */
    @Serializable
    data class EngineTasks(
        /** taskType → 是否勾选 */
        val enabled: Map<String, Boolean> = emptyMap(),
        /** taskType → 参数 JSON（引擎自定义结构） */
        val params: Map<String, String> = emptyMap(),
        /** 引擎工作台的跨任务配置（队伍/策略），宿主不解释其内容。 */
        val workspaceConfig: String? = null,
    )

    fun flow(engineId: String): Flow<EngineTasks> =
        dataStore.data.map { prefs -> decode(prefs[keyOf(engineId)]) }

    suspend fun current(engineId: String): EngineTasks =
        decode(readRaw(engineId))

    suspend fun setEnabled(engineId: String, taskType: String, enabled: Boolean) {
        update(engineId) { it.copy(enabled = it.enabled + (taskType to enabled)) }
    }

    suspend fun setParams(engineId: String, taskType: String, paramsJson: String) {
        update(engineId) { it.copy(params = it.params + (taskType to paramsJson)) }
    }

    suspend fun setWorkspaceConfig(engineId: String, configJson: String) {
        update(engineId) { it.copy(workspaceConfig = configJson) }
    }

    /**
     * Export every persisted engine, including one not installed in this APK variant.
     * Keep the original envelope so unknown future fields and engine-owned JSON survive
     * a round trip. Unlike startup display, backup must never silently reset bad data.
     */
    suspend fun exportSnapshot(): Map<String, String> {
        val snapshot = dataStore.data.first().asMap().entries.mapNotNull { (key, value) ->
            engineIdOf(key.name)?.let { id ->
                require(value is String) { "引擎 $id 的配置存储格式无效，无法导出" }
                id to value
            }
        }.sortedBy { it.first }.toMap()
        return validateSnapshot(snapshot)
    }

    /**
     * Replace only engines present in the backup, in one DataStore transaction. An old
     * Arknights-only backup must not erase Limbus or other local engine settings.
     */
    suspend fun importSnapshot(snapshot: Map<String, String>) {
        val validated = validateSnapshot(snapshot)
        if (validated.isEmpty()) return
        dataStore.edit { prefs ->
            validated.forEach { (id, raw) -> prefs[keyOf(id)] = raw }
        }
    }

    /** In-memory undo for the engines touched by a whole-app import, including absent keys. */
    internal class ImportCheckpoint internal constructor(internal val raw: Map<String, String?>)

    internal suspend fun checkpoint(engineIds: Set<String>): ImportCheckpoint {
        val prefs = dataStore.data.first()
        return ImportCheckpoint(engineIds.associateWith { prefs[keyOf(it)] })
    }

    internal suspend fun restore(checkpoint: ImportCheckpoint) {
        if (checkpoint.raw.isEmpty()) return
        dataStore.edit { prefs ->
            checkpoint.raw.forEach { (id, raw) ->
                if (raw == null) prefs.remove(keyOf(id)) else prefs[keyOf(id)] = raw
            }
        }
    }

    /**
     * 按引擎声明的面板顺序给出「本次要跑的任务」。
     *
     * 排序与默认值的规则见 [EngineTaskSelection.select] —— 那部分刻意抽成纯函数，
     * 因为它算错不会报错，只会让用户「勾了没跑」或「没勾却跑了」，而 DataStore
     * 在无 Robolectric 的纯 JVM 单测里起不来。
     */
    suspend fun selectedTasks(engineId: String): List<Pair<String, String>> {
        val ui = EngineRegistry.provider(engineId)?.ui ?: return emptyList()
        val saved = current(engineId)
        ui.workspace?.let { workspace ->
            return workspace.selectedTasks(saved.workspaceConfig ?: workspace.initialConfig(saved.enabled, saved.params))
        }
        val declared = ui.taskPanels.map { it.taskType to it.enabledByDefault }
        return EngineTaskSelection.select(declared, saved)
    }

    private suspend fun update(engineId: String, mutate: (EngineTasks) -> EngineTasks) {
        val key = keyOf(engineId)
        dataStore.edit { prefs ->
            val raw = prefs[key]
            val original = runCatching { raw?.let { json.parseToJsonElement(it).jsonObject } }.getOrNull()
            val changed = json.encodeToJsonElement(EngineTasks.serializer(), mutate(decode(raw))).jsonObject
            // Editing a known field after restoring a future backup must not erase the
            // envelope's unknown fields. Engine-owned params/workspace remain opaque strings.
            prefs[key] = JsonObject(original.orEmpty() + changed).toString()
        }
    }

    private suspend fun readRaw(engineId: String): String? = dataStore.data.first()[keyOf(engineId)]

    /**
     * 解析失败一律回落到空状态而不是抛异常。
     *
     * 存的是引擎自定义结构，引擎升级后旧数据可能不再可解析；让用户重新配一次任务
     * 远好过 App 起不来 —— 而后者是真会发生的：这份数据在启动路径上。
     */
    private fun decode(raw: String?): EngineTasks = EngineTaskSelection.decode(raw)

    private fun keyOf(engineId: String) = stringPreferencesKey("engine.$engineId.tasks")

    companion object {
        val Context.engineTaskStore: DataStore<Preferences>
            by preferencesDataStore(name = "engine_tasks")

        private val json get() = EngineTaskSelection.json

        /** Validate all engines before the backup manager writes any application settings. */
        internal fun validateSnapshot(snapshot: Map<String, String>): Map<String, String> =
            snapshot.toMap().also { copy ->
                copy.forEach { (id, raw) ->
                    require(id.isNotBlank()) { "备份包含空的引擎 ID" }
                    val tasks = runCatching {
                        require(validLiterals(json.parseToJsonElement(raw)))
                        json.decodeFromString<EngineTasks>(raw)
                    }.getOrElse {
                        throw IllegalArgumentException("引擎 $id 的任务配置格式无效", it)
                    }
                    fun validJson(value: String?, label: String) {
                        if (value.isNullOrBlank()) return
                        require(runCatching { validLiterals(json.parseToJsonElement(value)) }.getOrDefault(false)) {
                            "引擎 $id 的 $label 不是有效 JSON"
                        }
                    }
                    tasks.params.forEach { (type, value) -> validJson(value, "任务 $type 参数") }
                    validJson(tasks.workspaceConfig, "工作区配置")
                }
            }

        // Json's tree parser accepts bare unknown literals such as `not-json`. They are
        // not JSON strings and would fail when an engine decodes its own typed config.
        private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        private fun validLiterals(value: JsonElement): Boolean = when (value) {
            is JsonObject -> value.values.all(::validLiterals)
            is JsonArray -> value.all(::validLiterals)
            is JsonPrimitive -> value.isString || value == JsonNull ||
                value.content == "true" || value.content == "false" || jsonNumber.matches(value.content)
        }

        private fun engineIdOf(key: String): String? =
            if (key.startsWith("engine.") && key.endsWith(".tasks")) {
                key.removePrefix("engine.").removeSuffix(".tasks")
            } else null
    }
}

/**
 * [EngineTaskStore] 里与 Android 无关的那部分。
 *
 * 抽出来的两段都是「算错不报错、只让行为悄悄变样」的逻辑：解析失败的回落、
 * 以及哪些任务算被选中及其顺序。而 DataStore 在没有 Robolectric 的纯 JVM 单测里
 * 起不来，埋在里面就只能靠真机验证。
 */
internal object EngineTaskSelection {

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 解析失败一律回落到空状态而不是抛异常。
     *
     * 存的是引擎自定义结构，引擎升级后旧数据可能不再可解析；让用户重新配一次任务
     * 远好过 App 起不来 —— 而后者是真会发生的：这份数据在启动路径上。
     */
    fun decode(raw: String?): EngineTaskStore.EngineTasks {
        if (raw.isNullOrBlank()) return EngineTaskStore.EngineTasks()
        return runCatching { json.decodeFromString<EngineTaskStore.EngineTasks>(raw) }
            .onFailure { Timber.w(it, "engine task state unreadable, resetting") }
            .getOrDefault(EngineTaskStore.EngineTasks())
    }

    /**
     * 挑出要跑的任务并定序。
     *
     * @param declared 引擎声明的 (taskType, enabledByDefault)，**顺序即执行次序**
     *
     * 两条规则：
     * - 顺序取自引擎声明而非用户勾选的先后。面板顺序是引擎作者定的执行次序
     *   （例如先领邮件再刷副本）；按用户点击顺序会让结果不可预期。
     * - 存储里没有的任务用 `enabledByDefault`。首次使用时用户什么都没配，
     *   此时应当是引擎作者认为合理的默认组合，而不是空 —— 空会让用户点了开始却什么都不发生。
     */
    fun select(
        declared: List<Pair<String, Boolean>>,
        saved: EngineTaskStore.EngineTasks,
    ): List<Pair<String, String>> = declared
        .filter { (type, byDefault) -> saved.enabled[type] ?: byDefault }
        .map { (type, _) -> type to (saved.params[type] ?: "") }
}
