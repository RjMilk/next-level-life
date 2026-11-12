package com.nextlevellife.app.data

import com.nextlevellife.app.block.BlockRules
import com.nextlevellife.app.block.BlockSettings

/**
 * Настройки блокировки поверх тех же SharedPreferences, что и [Store] (нужен [Store.init]).
 * Чтение — свойства, запись — функции set…; каждая запись увеличивает [Store.version].
 * Сеттеры проверяют диапазон и бросают IllegalArgumentException на недопустимое значение.
 */
object Settings {
    const val DEFAULT_DAILY_LIMIT_MIN = 120
    const val DEFAULT_NIGHT_START_MIN = 23 * 60
    const val DEFAULT_NIGHT_END_MIN = 7 * 60

    /** Дневной лимит расхода в минутах; 0 — без лимита. Диапазон [BlockRules.LIMIT_RANGE]. */
    val dailyLimitMin: Int
        get() = Store.prefs().getInt(KEY_DAILY_LIMIT, DEFAULT_DAILY_LIMIT_MIN)

    val nightEnabled: Boolean
        get() = Store.prefs().getBoolean(KEY_NIGHT_ENABLED, true)

    /** Начало ночи в минутах от полуночи (включительно). */
    val nightStartMin: Int
        get() = Store.prefs().getInt(KEY_NIGHT_START, DEFAULT_NIGHT_START_MIN)

    /** Конец ночи в минутах от полуночи (исключительно). */
    val nightEndMin: Int
        get() = Store.prefs().getInt(KEY_NIGHT_END, DEFAULT_NIGHT_END_MIN)

    /** Начислять ли минуты за шаги (для следующей версии). */
    val stepsRewardEnabled: Boolean
        get() = Store.prefs().getBoolean(KEY_STEPS_REWARD, true)

    fun setDailyLimitMin(minutes: Int) {
        require(minutes in BlockRules.LIMIT_RANGE) { "Дневной лимит вне диапазона ${BlockRules.LIMIT_RANGE}: $minutes" }
        Store.prefs().edit().putInt(KEY_DAILY_LIMIT, minutes).apply()
        Store.notifyChanged()
    }

    fun setNightEnabled(enabled: Boolean) {
        Store.prefs().edit().putBoolean(KEY_NIGHT_ENABLED, enabled).apply()
        Store.notifyChanged()
    }

    fun setNightStartMin(minuteOfDay: Int) {
        require(minuteOfDay in BlockRules.MINUTE_RANGE) { "Начало ночи вне суток: $minuteOfDay" }
        Store.prefs().edit().putInt(KEY_NIGHT_START, minuteOfDay).apply()
        Store.notifyChanged()
    }

    fun setNightEndMin(minuteOfDay: Int) {
        require(minuteOfDay in BlockRules.MINUTE_RANGE) { "Конец ночи вне суток: $minuteOfDay" }
        Store.prefs().edit().putInt(KEY_NIGHT_END, minuteOfDay).apply()
        Store.notifyChanged()
    }

    fun setStepsRewardEnabled(enabled: Boolean) {
        Store.prefs().edit().putBoolean(KEY_STEPS_REWARD, enabled).apply()
        Store.notifyChanged()
    }

    /**
     * Когда последний раз создавалась резервная копия (мс эпохи); null — ещё ни разу.
     * Ключ не входит в саму копию (Store.exportPrefs), поэтому восстановление его не меняет.
     */
    val lastBackupMs: Long?
        get() = Store.prefs().getLong(KEY_LAST_BACKUP, -1L).takeIf { it > 0L }

    fun setLastBackupMs(timeMs: Long) {
        require(timeMs > 0L) { "Время копии должно быть положительным: $timeMs" }
        Store.prefs().edit().putLong(KEY_LAST_BACKUP, timeMs).apply()
        Store.notifyChanged()
    }

    /** Снимок для [BlockRules]. */
    fun blockSettings(): BlockSettings = BlockSettings(
        dailyLimitMin = dailyLimitMin,
        nightEnabled = nightEnabled,
        nightStartMin = nightStartMin,
        nightEndMin = nightEndMin,
    )

    internal const val KEY_DAILY_LIMIT = "settings_daily_limit_min"
    internal const val KEY_NIGHT_ENABLED = "settings_night_enabled"
    internal const val KEY_NIGHT_START = "settings_night_start_min"
    internal const val KEY_NIGHT_END = "settings_night_end_min"
    internal const val KEY_STEPS_REWARD = "settings_steps_reward_enabled"
    internal const val KEY_LAST_BACKUP = "settings_last_backup_ms"
}
