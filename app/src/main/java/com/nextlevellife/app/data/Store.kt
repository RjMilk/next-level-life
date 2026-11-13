package com.nextlevellife.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Быстрое состояние приложения. Служба блокировки и интерфейс работают в одном процессе и пользуются одним экземпляром.
 * [version] увеличивается при каждом изменении, чтобы экраны перерисовывались.
 *
 * С версии 0.3 баланс и опыт ведёт [Ledger] (журнал в diary.db); здесь они только читаются из его кеша.
 * В SharedPreferences остаются блокируемые приложения, серия дней, рекорды Минотавра до дневника,
 * настройки ([Settings]) и старые значения баланса/опыта — из них один раз делается перенос (OPENING).
 */
object Store {
    private lateinit var sp: SharedPreferences
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Synchronized
    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences("nll", Context.MODE_PRIVATE)
            // До открытия дневника: восстановление из копии могло прерваться посреди подмены базы.
            Backup.recoverInterruptedRestore(context)
            // Отсюда, чтобы дневник был готов везде, где готов Store: экраны и служба его не вызывают.
            Diary.init(context)
            // Сбой журнала не должен ронять init (его вызывает и служба): баланс тогда читается как 0,
            // то есть заблокированные приложения закрыты, а не открыты.
            try {
                Ledger.init(sp.getLong(KEY_BALANCE, 0L), sp.getLong(KEY_XP, 0L))
            } catch (e: Exception) {
                Log.e("Store", "Не удалось подготовить журнал баланса", e)
            }
            syncLegacyHistory()
        }
    }

    internal fun prefs(): SharedPreferences = sp

    /** Сообщить экранам, что данные изменились. */
    internal fun notifyChanged() {
        _version.update { it + 1 }
    }

    // ---------- Баланс экранного времени ----------

    fun balanceMs(): Long = Ledger.balanceMs()

    // ---------- Блокируемые приложения ----------

    fun isConfigured(): Boolean = sp.getBoolean(KEY_CONFIGURED, false)

    fun blockedPackages(): Set<String> = sp.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    fun isBlocked(pkg: String): Boolean = pkg in blockedPackages()

    fun setBlockedPackages(packages: Set<String>) {
        sp.edit()
            .putStringSet(KEY_BLOCKED, HashSet(packages))
            .putBoolean(KEY_CONFIGURED, true)
            .apply()
        notifyChanged()
    }

    fun setBlocked(pkg: String, blocked: Boolean) {
        val set = blockedPackages().toMutableSet()
        if (blocked) set.add(pkg) else set.remove(pkg)
        setBlockedPackages(set)
    }

    // ---------- Прогресс ----------

    fun xp(): Long = Ledger.xp()

    fun level(): Int = levelFor(xp())

    fun streak(): Int {
        val last = sp.getLong(KEY_LAST_DAY, Long.MIN_VALUE)
        val today = LocalDate.now().toEpochDay()
        // Серия сгорает, если вчера тренировки не было.
        return if (last >= today - 1) sp.getInt(KEY_STREAK, 0) else 0
    }

    fun minotaurBestMs(weightKg: Int): Long? =
        sp.getLong("$KEY_MINOTAUR_BEST$weightKg", -1L).takeIf { it > 0 }

    /** Возвращает true, если это новый личный рекорд. */
    fun submitMinotaurTime(weightKg: Int, ms: Long): Boolean {
        val best = minotaurBestMs(weightKg)
        if (best == null || ms < best) {
            sp.edit().putLong("$KEY_MINOTAUR_BEST$weightKg", ms).apply()
            notifyChanged()
            return true
        }
        return false
    }

    /**
     * Отмечает тренировку сегодня (серия дней). Вызывается из [Workouts] после успешной записи;
     * баланс и опыт начисляет [Ledger] в той же транзакции, что и запись в дневник.
     */
    @Synchronized
    internal fun markTrained() {
        val today = LocalDate.now().toEpochDay()
        val next = WorkoutMath.nextStreak(sp.getLong(KEY_LAST_DAY, Long.MIN_VALUE), today, sp.getInt(KEY_STREAK, 0))
        sp.edit()
            .putLong(KEY_LAST_DAY, today)
            .putInt(KEY_STREAK, next)
            .apply()
        notifyChanged()
    }

    data class HistoryEntry(val timeMs: Long, val title: String, val xp: Long, val earnedMs: Long)

    /** Старая JSON-история (до дневника). Нужна только для переноса в дневник. */
    fun history(): List<HistoryEntry> {
        val arr = JSONArray(sp.getString(KEY_HISTORY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryEntry(o.getLong("t"), o.getString("title"), o.getLong("xp"), o.getLong("earnedMs"))
        }
    }

    /**
     * Переносит в дневник записи старой истории, которых там ещё нет. Отметка переноса живёт в meta
     * базы и пишется той же транзакцией, что и импорт; старая отметка из prefs читается один раз.
     * Баланс за них повторно не начисляется: он уже вошёл в OPENING.
     */
    private fun syncLegacyHistory() {
        // Сбой переноса не должен ронять init: его вызывает и служба блокировки.
        try {
            val prefsUntil = if (sp.contains(KEY_DIARY_LEGACY_UNTIL)) sp.getLong(KEY_DIARY_LEGACY_UNTIL, Long.MIN_VALUE) else null
            Diary.syncLegacy(history(), prefsUntil)
        } catch (e: Exception) {
            Log.e("Store", "Не удалось перенести старую историю в дневник", e)
        }
    }

    // ---------- Резервная копия ----------

    /** Снимок настроек для резервной копии: настройки, блокируемые приложения, серия, рекорды. */
    internal fun exportPrefs(): JSONObject {
        val o = JSONObject()
        o.put(KEY_CONFIGURED, sp.getBoolean(KEY_CONFIGURED, false))
        o.put(KEY_BLOCKED, JSONArray(blockedPackages().sorted()))
        if (sp.contains(KEY_STREAK)) o.put(KEY_STREAK, sp.getInt(KEY_STREAK, 0))
        if (sp.contains(KEY_LAST_DAY)) o.put(KEY_LAST_DAY, sp.getLong(KEY_LAST_DAY, 0L))
        val bests = JSONObject()
        for ((key, value) in sp.all) {
            if (key.startsWith(KEY_MINOTAUR_BEST) && value is Long) bests.put(key.removePrefix(KEY_MINOTAUR_BEST), value)
        }
        o.put("minotaur_best", bests)
        val settings = JSONObject()
        settings.put(Settings.KEY_DAILY_LIMIT, Settings.dailyLimitMin)
        settings.put(Settings.KEY_NIGHT_ENABLED, Settings.nightEnabled)
        settings.put(Settings.KEY_NIGHT_START, Settings.nightStartMin)
        settings.put(Settings.KEY_NIGHT_END, Settings.nightEndMin)
        settings.put(Settings.KEY_STEPS_REWARD, Settings.stepsRewardEnabled)
        // День включения оплаты шагов: без него восстановленный переключатель не платит.
        Settings.stepsRewardSinceDay?.let { settings.put(Settings.KEY_STEPS_SINCE, it) }
        o.put("settings", settings)
        return o
    }

    /** Разобранный снимок настроек; разбор не трогает текущие данные. */
    internal class PrefsSnapshot(private val apply: (SharedPreferences.Editor) -> Unit) {
        fun applyTo(editor: SharedPreferences.Editor) = apply(editor)
    }

    /** Разбирает и проверяет снимок [exportPrefs]. Бросает исключение, если формат не тот. */
    internal fun parsePrefs(o: JSONObject): PrefsSnapshot {
        val configured = o.optBoolean(KEY_CONFIGURED, false)
        val blockedArr = o.optJSONArray(KEY_BLOCKED) ?: JSONArray()
        val blocked = HashSet<String>((0 until blockedArr.length()).map { blockedArr.getString(it) })
        val streak = if (o.has(KEY_STREAK)) o.getInt(KEY_STREAK) else null
        val lastDay = if (o.has(KEY_LAST_DAY)) o.getLong(KEY_LAST_DAY) else null
        val bestsObj = o.optJSONObject("minotaur_best") ?: JSONObject()
        val bests = bestsObj.keys().asSequence()
            .filter { it.toIntOrNull() != null }
            .associateWith { bestsObj.getLong(it) }
        val s = o.optJSONObject("settings") ?: JSONObject()
        val limit = s.optInt(Settings.KEY_DAILY_LIMIT, Settings.DEFAULT_DAILY_LIMIT_MIN)
        val nightOn = s.optBoolean(Settings.KEY_NIGHT_ENABLED, true)
        val nightStart = s.optInt(Settings.KEY_NIGHT_START, Settings.DEFAULT_NIGHT_START_MIN)
        val nightEnd = s.optInt(Settings.KEY_NIGHT_END, Settings.DEFAULT_NIGHT_END_MIN)
        val stepsSince = restoredStepsSince(
            enabled = s.optBoolean(Settings.KEY_STEPS_REWARD, false),
            sinceDay = if (s.has(Settings.KEY_STEPS_SINCE)) s.getLong(Settings.KEY_STEPS_SINCE) else null,
        )
        val steps = stepsSince != null
        require(limit in com.nextlevellife.app.block.BlockRules.LIMIT_RANGE) { "Лимит в копии вне диапазона: $limit" }
        require(nightStart in com.nextlevellife.app.block.BlockRules.MINUTE_RANGE) { "Начало ночи в копии вне суток" }
        require(nightEnd in com.nextlevellife.app.block.BlockRules.MINUTE_RANGE) { "Конец ночи в копии вне суток" }
        return PrefsSnapshot { e ->
            e.putBoolean(KEY_CONFIGURED, configured)
            e.putStringSet(KEY_BLOCKED, blocked)
            if (streak != null) e.putInt(KEY_STREAK, streak) else e.remove(KEY_STREAK)
            if (lastDay != null) e.putLong(KEY_LAST_DAY, lastDay) else e.remove(KEY_LAST_DAY)
            for (key in sp.all.keys) if (key.startsWith(KEY_MINOTAUR_BEST)) e.remove(key)
            for ((w, ms) in bests) e.putLong("$KEY_MINOTAUR_BEST$w", ms)
            e.putInt(Settings.KEY_DAILY_LIMIT, limit)
            e.putBoolean(Settings.KEY_NIGHT_ENABLED, nightOn)
            e.putInt(Settings.KEY_NIGHT_START, nightStart)
            e.putInt(Settings.KEY_NIGHT_END, nightEnd)
            e.putBoolean(Settings.KEY_STEPS_REWARD, steps)
            if (steps && stepsSince != null) e.putLong(Settings.KEY_STEPS_SINCE, stepsSince) else e.remove(Settings.KEY_STEPS_SINCE)
        }
    }

    /**
     * День включения оплаты шагов после восстановления; null — оплата выключена.
     * Оплата включается только вместе с днём включения: в старых копиях дня нет — остаётся выключенной.
     */
    internal fun restoredStepsSince(enabled: Boolean, sinceDay: Long?): Long? = if (enabled) sinceDay else null

    /** Записывает снимок синхронно; false — запись не удалась. */
    internal fun commitPrefs(snapshot: PrefsSnapshot): Boolean {
        val editor = sp.edit()
        snapshot.applyTo(editor)
        return editor.commit()
    }

    // Уровень n начинается с 50·(n−1)² опыта: 0, 50, 200, 450, 800…
    fun levelFor(xp: Long): Int = floor(sqrt(xp / 50.0)).toInt() + 1
    fun xpForLevel(level: Int): Long = 50L * (level - 1) * (level - 1)

    fun titleFor(level: Int): String = when {
        level >= 20 -> "Легенда"
        level >= 12 -> "Титан"
        level >= 8 -> "Воин"
        level >= 5 -> "Атлет"
        level >= 3 -> "Любитель"
        else -> "Новичок"
    }

    // До 0.3 здесь лежали баланс и опыт; теперь они читаются только для переноса (OPENING).
    private const val KEY_BALANCE = "balance_ms"
    // v2: стартовый набор сменился с белого списка на чёрный, старые настройки пересоздаются.
    private const val KEY_CONFIGURED = "configured_v2"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_XP = "xp"
    private const val KEY_STREAK = "streak"
    private const val KEY_LAST_DAY = "last_day"
    private const val KEY_HISTORY = "history"
    private const val KEY_MINOTAUR_BEST = "minotaur_best_"
    // До 0.3 отметка переноса жила здесь; теперь — в meta базы (Diary.KEY_LEGACY_UNTIL).
    private const val KEY_DIARY_LEGACY_UNTIL = "diary_legacy_until"
}
