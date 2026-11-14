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

    /**
     * Начислять ли минуты за ходьбу и бег (Health Connect). По умолчанию выключено: сначала
     * владелец сверяет цифры. Включено только вместе с днём включения [stepsRewardSinceDay] —
     * так копия из старой версии (где по умолчанию было «включено») не начнёт платить сама.
     */
    val stepsRewardEnabled: Boolean
        get() = Store.prefs().getBoolean(KEY_STEPS_REWARD, false) && stepsRewardSinceDay != null

    /** С каких учётных суток ([TrustedClock.today]) платить за шаги; выставляется при включении. null — ещё не включали. */
    val stepsRewardSinceDay: Long?
        get() = Store.prefs().takeIf { it.contains(KEY_STEPS_SINCE) }?.getLong(KEY_STEPS_SINCE, 0L)

    /**
     * Когда последний раз удалось прочитать Health Connect (мс эпохи); null — ни разу.
     * Служебное значение: в резервную копию не входит.
     */
    val activityLastSyncMs: Long?
        get() = Store.prefs().getLong(KEY_ACTIVITY_LAST_SYNC, -1L).takeIf { it > 0L }

    // Сеттеры сначала пишут общую настройку пары (SharedSettings, версия +1), затем prefs; слияние со вторым
    // телефоном возвращает победителя через applyShared… — читатели не меняются. Процесс умер между записями —
    // при запуске действующие значения из shared_settings снова записываются в prefs (SharedSettings.applyAll).

    fun setDailyLimitMin(minutes: Int) {
        require(minutes in BlockRules.LIMIT_RANGE) { "Дневной лимит вне диапазона ${BlockRules.LIMIT_RANGE}: $minutes" }
        SharedSettings.localChange(SharedSettings.DAILY_LIMIT, minutes.toString())
        applySharedLimit(minutes)
    }

    fun setNightEnabled(enabled: Boolean) = setNight(enabled, nightStartMin, nightEndMin)

    fun setNightStartMin(minuteOfDay: Int) {
        require(minuteOfDay in BlockRules.MINUTE_RANGE) { "Начало ночи вне суток: $minuteOfDay" }
        setNight(nightEnabled, minuteOfDay, nightEndMin)
    }

    fun setNightEndMin(minuteOfDay: Int) {
        require(minuteOfDay in BlockRules.MINUTE_RANGE) { "Конец ночи вне суток: $minuteOfDay" }
        setNight(nightEnabled, nightStartMin, minuteOfDay)
    }

    private fun setNight(enabled: Boolean, startMin: Int, endMin: Int) {
        SharedSettings.localChange(SharedSettings.NIGHT, SharedSettingsCodec.night(enabled, startMin, endMin))
        applySharedNight(enabled, startMin, endMin)
    }

    /**
     * Включение запоминает день [today]: дни раньше него не оплачиваются. Повторное включение
     * уже включённого день не сдвигает; выключение и новое включение — сдвигает.
     */
    fun setStepsRewardEnabled(enabled: Boolean, today: Long = TrustedClock.today()) {
        val since = when {
            !enabled -> null
            stepsRewardEnabled -> stepsRewardSinceDay
            else -> today
        }
        SharedSettings.localChange(SharedSettings.STEPS_REWARD, SharedSettingsCodec.steps(since))
        applySharedSteps(since)
    }

    /**
     * Источник активности пары (device_id): общая настройка. Смена вступает в силу на этом телефоне только после
     * удачного обмена ([SharedSettings.changeActivitySource]): до этого ни один из двух не начисляет дважды.
     */
    fun setActivitySource(deviceId: String) {
        SharedSettings.changeActivitySource(deviceId)
        Store.notifyChanged()
    }

    // ---------- Значения из общих настроек (без новой версии) ----------

    internal fun applySharedLimit(minutes: Int) {
        Store.prefs().edit().putInt(KEY_DAILY_LIMIT, minutes).apply()
        Store.notifyChanged()
    }

    internal fun applySharedNight(enabled: Boolean, startMin: Int, endMin: Int) {
        Store.prefs().edit()
            .putBoolean(KEY_NIGHT_ENABLED, enabled)
            .putInt(KEY_NIGHT_START, startMin)
            .putInt(KEY_NIGHT_END, endMin)
            .apply()
        Store.notifyChanged()
    }

    /** [sinceDay] = null — оплата шагов выключена. */
    internal fun applySharedSteps(sinceDay: Long?) {
        val edit = Store.prefs().edit().putBoolean(KEY_STEPS_REWARD, sinceDay != null)
        if (sinceDay != null) edit.putLong(KEY_STEPS_SINCE, sinceDay)
        edit.apply()
        Store.notifyChanged()
    }

    /** Отметка успешного чтения Health Connect; экраны не будит — это делает синхронизация. */
    internal fun setActivityLastSyncMs(timeMs: Long) {
        require(timeMs > 0L) { "Время синхронизации должно быть положительным: $timeMs" }
        Store.prefs().edit().putLong(KEY_ACTIVITY_LAST_SYNC, timeMs).apply()
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
    internal const val KEY_STEPS_SINCE = "settings_steps_reward_since_day"
    internal const val KEY_ACTIVITY_LAST_SYNC = "activity_last_sync_ms"
}
