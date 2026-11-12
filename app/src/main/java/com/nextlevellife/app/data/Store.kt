package com.nextlevellife.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Всё состояние приложения в SharedPreferences.
 * Служба блокировки и интерфейс работают в одном процессе и пользуются одним экземпляром.
 * [version] увеличивается при каждом изменении, чтобы экраны перерисовывались.
 * Подробности тренировок — в [Diary]; здесь только то, что нужно читать быстро (баланс, опыт, серия).
 */
object Store {
    private lateinit var prefs: SharedPreferences
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("nll", Context.MODE_PRIVATE)
            // Отсюда, чтобы дневник был готов везде, где готов Store: экраны и служба его не вызывают.
            Diary.init(context)
            syncLegacyHistory()
        }
    }

    private fun changed() {
        _version.value = _version.value + 1
    }

    // ---------- Баланс экранного времени ----------

    fun balanceMs(): Long = prefs.getLong(KEY_BALANCE, 0L)

    @Synchronized
    fun addBalance(ms: Long) {
        prefs.edit().putLong(KEY_BALANCE, balanceMs() + ms).apply()
        changed()
    }

    /** Списывает время и возвращает остаток (не меньше нуля). */
    @Synchronized
    fun spend(ms: Long): Long {
        val left = (balanceMs() - ms).coerceAtLeast(0L)
        prefs.edit().putLong(KEY_BALANCE, left).apply()
        changed()
        return left
    }

    // ---------- Блокируемые приложения ----------

    fun isConfigured(): Boolean = prefs.getBoolean(KEY_CONFIGURED, false)

    fun blockedPackages(): Set<String> = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    fun isBlocked(pkg: String): Boolean = pkg in blockedPackages()

    fun setBlockedPackages(packages: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_BLOCKED, HashSet(packages))
            .putBoolean(KEY_CONFIGURED, true)
            .apply()
        changed()
    }

    fun setBlocked(pkg: String, blocked: Boolean) {
        val set = blockedPackages().toMutableSet()
        if (blocked) set.add(pkg) else set.remove(pkg)
        setBlockedPackages(set)
    }

    // ---------- Прогресс ----------

    fun xp(): Long = prefs.getLong(KEY_XP, 0L)

    fun level(): Int = levelFor(xp())

    fun streak(): Int {
        val last = prefs.getLong(KEY_LAST_DAY, Long.MIN_VALUE)
        val today = LocalDate.now().toEpochDay()
        // Серия сгорает, если вчера тренировки не было.
        return if (last >= today - 1) prefs.getInt(KEY_STREAK, 0) else 0
    }

    fun minotaurBestMs(weightKg: Int): Long? =
        prefs.getLong("$KEY_MINOTAUR_BEST$weightKg", -1L).takeIf { it > 0 }

    /** Возвращает true, если это новый личный рекорд. */
    fun submitMinotaurTime(weightKg: Int, ms: Long): Boolean {
        val best = minotaurBestMs(weightKg)
        if (best == null || ms < best) {
            prefs.edit().putLong("$KEY_MINOTAUR_BEST$weightKg", ms).apply()
            changed()
            return true
        }
        return false
    }

    /**
     * Начисляет награду за тренировку: баланс, опыт, серия дней.
     * Вызывается из [Workouts]; старую JSON-историю не трогает — подробности пишет [Diary].
     */
    @Synchronized
    fun credit(earnedMs: Long, xp: Long) {
        val today = LocalDate.now().toEpochDay()
        prefs.edit()
            .putLong(KEY_XP, xp() + xp)
            .putLong(KEY_BALANCE, balanceMs() + earnedMs)
            .putLong(KEY_LAST_DAY, today)
            .putInt(KEY_STREAK, nextStreak(today))
            .apply()
        changed()
    }

    private fun nextStreak(today: Long): Int =
        WorkoutMath.nextStreak(prefs.getLong(KEY_LAST_DAY, Long.MIN_VALUE), today, prefs.getInt(KEY_STREAK, 0))

    /** Старый способ записи (заголовок + награда). Остаётся, пока экраны не перешли на [Workouts]. */
    @Synchronized
    fun recordWorkout(title: String, xpGain: Long, earnedMs: Long) {
        val today = LocalDate.now().toEpochDay()
        val newStreak = nextStreak(today)
        val entry = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("title", title)
            .put("xp", xpGain)
            .put("earnedMs", earnedMs)
        val history = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        val trimmed = JSONArray().put(entry)
        for (i in 0 until minOf(history.length(), HISTORY_LIMIT - 1)) trimmed.put(history.get(i))

        prefs.edit()
            .putLong(KEY_XP, xp() + xpGain)
            .putLong(KEY_BALANCE, balanceMs() + earnedMs)
            .putLong(KEY_LAST_DAY, today)
            .putInt(KEY_STREAK, newStreak)
            .putString(KEY_HISTORY, trimmed.toString())
            .apply()
        syncLegacyHistory()
        changed()
    }

    data class HistoryEntry(val timeMs: Long, val title: String, val xp: Long, val earnedMs: Long)

    fun history(): List<HistoryEntry> {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryEntry(o.getLong("t"), o.getString("title"), o.getLong("xp"), o.getLong("earnedMs"))
        }
    }

    /**
     * Переносит в дневник записи старой истории, которых там ещё нет (граница — время последней
     * перенесённой). Так прежние тренировки не теряются, а записи старых экранов попадают в дневник.
     */
    private fun syncLegacyHistory() {
        // Сбой переноса не должен ронять init: его вызывает и служба блокировки.
        try {
            val until = prefs.getLong(KEY_DIARY_LEGACY_UNTIL, Long.MIN_VALUE)
            val fresh = history().filter { it.timeMs > until }
            if (fresh.isEmpty()) return
            Diary.importLegacy(fresh)
            // commit, а не apply: если граница не сохранится, записи перенесутся повторно.
            prefs.edit().putLong(KEY_DIARY_LEGACY_UNTIL, fresh.maxOf { it.timeMs }).commit()
        } catch (e: Exception) {
            Log.e("Store", "Не удалось перенести старую историю в дневник", e)
        }
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

    private const val KEY_BALANCE = "balance_ms"
    // v2: стартовый набор сменился с белого списка на чёрный, старые настройки пересоздаются.
    private const val KEY_CONFIGURED = "configured_v2"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_XP = "xp"
    private const val KEY_STREAK = "streak"
    private const val KEY_LAST_DAY = "last_day"
    private const val KEY_HISTORY = "history"
    private const val KEY_MINOTAUR_BEST = "minotaur_best_"
    private const val KEY_DIARY_LEGACY_UNTIL = "diary_legacy_until"
    private const val HISTORY_LIMIT = 50
}
