package com.aliothmoon.maadroid.schedule.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aliothmoon.maadroid.schedule.model.ScheduleStrategy
import com.aliothmoon.maadroid.utils.JsonUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber

class ScheduleStrategyRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    constructor(context: Context) : this(
        context.store,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    companion object {
        private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "schedule_strategies")
        private val STRATEGIES_KEY = stringPreferencesKey("strategies")
    }

    private val json = JsonUtils.common

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /** 从 DataStore 自动同步的策略列表 */
    val strategies: StateFlow<List<ScheduleStrategy>> = dataStore.data
        .map { prefs ->
            val list = decodeStrategies(prefs[STRATEGIES_KEY])
            _isLoaded.value = true
            list
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Read committed data for backup/undo; the UI StateFlow may still be catching up. */
    suspend fun snapshot(): List<ScheduleStrategy> {
        val raw = dataStore.data.first()[STRATEGIES_KEY]
        // An unreadable backup must fail rather than silently replace saved schedules with [].
        return if (raw.isNullOrEmpty()) emptyList() else json.decodeFromString(raw)
    }

    // ---- 策略 CRUD ----

    suspend fun add(strategy: ScheduleStrategy) {
        dataStore.edit { prefs ->
            val current = decodeStrategies(prefs[STRATEGIES_KEY]).toMutableList()
            current.add(strategy)
            prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(current)
            Timber.d("添加调度策略: %s (%s)", strategy.name, strategy.id)
        }
    }

    suspend fun update(strategy: ScheduleStrategy) {
        dataStore.edit { prefs ->
            val current = decodeStrategies(prefs[STRATEGIES_KEY]).toMutableList()
            val idx = current.indexOfFirst { it.id == strategy.id }
            if (idx >= 0) {
                current[idx] = strategy
                prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(current)
                Timber.d("更新调度策略: %s (%s)", strategy.name, strategy.id)
            }
        }
    }

    suspend fun remove(strategyId: String) {
        dataStore.edit { prefs ->
            val current = decodeStrategies(prefs[STRATEGIES_KEY]).toMutableList()
            if (current.removeAll { it.id == strategyId }) {
                prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(current)
                Timber.d("删除调度策略: %s", strategyId)
            }
        }
    }

    suspend fun setEnabled(strategyId: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = decodeStrategies(prefs[STRATEGIES_KEY]).toMutableList()
            val idx = current.indexOfFirst { it.id == strategyId }
            if (idx >= 0) {
                current[idx] = current[idx].copy(enabled = enabled)
                prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(current)
                Timber.d("策略 %s 启用状态 -> %s", strategyId, enabled)
            }
        }
    }

    suspend fun getById(strategyId: String): ScheduleStrategy? {
        return strategies.value.find { it.id == strategyId }
    }

    suspend fun recordExecutionResult(
        strategyId: String,
        result: com.aliothmoon.maadroid.schedule.model.ExecutionResult,
        message: String? = null,
        executedAt: Long = System.currentTimeMillis(),
    ) {
        dataStore.edit { prefs ->
            val current = decodeStrategies(prefs[STRATEGIES_KEY]).toMutableList()
            val idx = current.indexOfFirst { it.id == strategyId }
            if (idx < 0) {
                return@edit
            }

            current[idx] = current[idx].copy(
                lastExecutedAt = executedAt,
                lastResult = result,
                lastResultMessage = message,
            )
            prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(current)
            Timber.d("记录调度结果: %s -> %s (%s)", strategyId, result, message)
        }
    }


    suspend fun importStrategies(strategies: List<ScheduleStrategy>) {
        dataStore.edit { prefs ->
            prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(strategies)
            Timber.d("导入 %d 条调度策略", strategies.size)
        }
    }

    private fun decodeStrategies(raw: String?): List<ScheduleStrategy> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ScheduleStrategy>>(raw)
        }.getOrElse {
            Timber.w(it, "解析调度策略失败，返回空列表")
            emptyList()
        }
    }
}
