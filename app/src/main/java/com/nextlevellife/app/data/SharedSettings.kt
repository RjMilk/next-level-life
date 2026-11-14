package com.nextlevellife.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.nextlevellife.app.block.BlockRules
import com.nextlevellife.app.stats.StatsRepository

/**
 * Общие настройки пары (таблица shared_settings): дневной лимит, ночной режим, начисление за шаги, источник
 * активности и учётные дни начала «Сайтамы» и штрафов. Читатели по-прежнему читают prefs и meta ([Settings]):
 * после слияния победившие значения записываются туда же. Слияние — [SharedWalletMath.merge].
 */
object SharedSettings {
    private const val TAG = "SharedSettings"

    const val DAILY_LIMIT = "daily_limit"
    /** «вкл,начало,конец»: «1,1380,420». */
    const val NIGHT = "night"
    /** День включения оплаты шагов; пустая строка — выключено. */
    const val STEPS_REWARD = "steps_reward"
    /** device_id телефона, который читает Health Connect и оплачивает шаги и бонус «Сайтамы». */
    const val ACTIVITY_SOURCE = "activity_source"
    const val SAITAMA_SINCE = "saitama_since"
    const val PENALTY_SINCE = "stats_penalty_since"

    /** Ключи, значения которых заполняются из текущих настроек телефона. */
    private val SEEDED = listOf(DAILY_LIMIT, NIGHT, STEPS_REWARD, SAITAMA_SINCE, PENALTY_SINCE)

    /** Текущее значение настройки этого телефона; null — нет (дни начала ещё не выставлены). */
    private fun currentValue(key: String): String? = when (key) {
        DAILY_LIMIT -> Settings.dailyLimitMin.toString()
        NIGHT -> SharedSettingsCodec.night(Settings.nightEnabled, Settings.nightStartMin, Settings.nightEndMin)
        STEPS_REWARD -> SharedSettingsCodec.steps(if (Settings.stepsRewardEnabled) Settings.stepsRewardSinceDay else null)
        SAITAMA_SINCE -> Diary.metaGet(Saitama.META_SINCE)
        PENALTY_SINCE -> Diary.metaGet(StatsRepository.PENALTY_SINCE_KEY)
        else -> null
    }

    private fun rows(db: SQLiteDatabase): Map<String, SharedWalletMath.Setting> =
        db.rawQuery("SELECT key, value, version, author FROM shared_settings", null).use { c ->
            val out = HashMap<String, SharedWalletMath.Setting>()
            while (c.moveToNext()) out[c.getString(0)] = SharedWalletMath.Setting(c.getString(0), c.getString(1), c.getLong(2), c.getString(3))
            out
        }

    private fun put(db: SQLiteDatabase, s: SharedWalletMath.Setting) {
        db.insertWithOnConflict(
            "shared_settings", null,
            ContentValues().apply {
                put("key", s.key)
                put("value", s.value)
                put("version", s.version)
                put("author", s.author)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Все общие настройки. Недостающие заполняются текущими значениями версией 0 (при равных версиях побеждает больший
     * device_id — на обоих телефонах одинаково); дни начала обновляются до текущего минимума.
     */
    fun all(): List<SharedWalletMath.Setting> = Diary.write { db ->
        val me = Ledger.deviceId()
        val have = rows(db).toMutableMap()
        for (key in SEEDED) {
            val value = currentValue(key) ?: continue
            val row = have[key]
            val next = when {
                row == null -> SharedWalletMath.Setting(key, value, 0L, me)
                key in SharedWalletMath.MIN_KEYS ->
                    SharedWalletMath.merge(row, SharedWalletMath.Setting(key, value, row.version, me))!!
                else -> row
            }
            if (next != row) {
                put(db, next)
                have[key] = next
            }
        }
        have.values.toList()
    }

    /** Значение настройки по общей таблице; null — не записано. */
    fun value(key: String): String? = runCatching {
        Diary.query("SELECT value FROM shared_settings WHERE key = ?", key) { it.getString(0) }.firstOrNull()
    }.getOrNull()

    /** Источник активности; null — не выбран (пары не было). */
    fun activitySource(): String? = value(ACTIVITY_SOURCE)

    /**
     * Этот телефон читает Health Connect и оплачивает активность: без пары — да; в паре — если он выбран источником
     * (пока не выбран — да, выбор делает первый обмен).
     */
    fun isActivitySource(): Boolean {
        if (Ledger.peerId() == null) return true
        val src = activitySource() ?: return true
        return src == Ledger.deviceId()
    }

    /** Локальное изменение (сеттер настроек): версия больше всех известных, автор — этот телефон. */
    fun localChange(key: String, value: String) {
        try {
            Diary.write { db ->
                val have = rows(db)
                put(db, SharedWalletMath.Setting(key, value, SharedWalletMath.nextVersion(have.values, key), Ledger.deviceId()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать общую настройку $key", e)
        }
    }

    /** Источник по умолчанию, если ещё не выбран (одинаковая строка на обоих телефонах). */
    fun ensureActivitySource(peerId: String, myHealth: Boolean, peerHealth: Boolean) {
        Diary.write { db ->
            if (ACTIVITY_SOURCE in rows(db)) return@write
            val me = Ledger.deviceId()
            put(db, SharedWalletMath.Setting(ACTIVITY_SOURCE, SharedWalletMath.defaultActivitySource(me, myHealth, peerId, peerHealth), 1L, minOf(me, peerId)))
        }
    }

    /** Слить настройки второго телефона и применить победителей. true — что-то изменилось. */
    fun merge(remote: List<SharedWalletMath.Setting>): Boolean {
        val local = all()
        val merged = SharedWalletMath.mergeAll(local, remote)
        val localByKey = local.associateBy { it.key }
        val changedRows = merged.values.filter { localByKey[it.key] != it }
        if (changedRows.isEmpty()) return false
        Diary.write { db -> for (s in changedRows) put(db, s) }
        var applied = false
        for (s in changedRows) {
            if (currentValue(s.key) != s.value && apply(s.key, s.value)) applied = true
        }
        if (applied || changedRows.any { it.key == ACTIVITY_SOURCE }) Store.notifyChanged()
        return true
    }

    /** Записывает победившее значение туда, откуда его читают (prefs или meta). false — значение не годится. */
    private fun apply(key: String, value: String): Boolean = try {
        when (key) {
            DAILY_LIMIT -> value.toIntOrNull()?.takeIf { it in BlockRules.LIMIT_RANGE }?.let { Settings.applySharedLimit(it); true } ?: false
            NIGHT -> SharedSettingsCodec.parseNight(value)?.let { (on, start, end) -> Settings.applySharedNight(on, start, end); true } ?: false
            STEPS_REWARD -> SharedSettingsCodec.parseSteps(value)?.let { Settings.applySharedSteps(it.getOrNull()); true } ?: false
            SAITAMA_SINCE -> value.toLongOrNull()?.let { Diary.metaPut(Saitama.META_SINCE, it.toString()); true } ?: false
            PENALTY_SINCE -> value.toLongOrNull()?.let { Diary.metaPut(StatsRepository.PENALTY_SINCE_KEY, it.toString()); true } ?: false
            else -> false
        }
    } catch (e: Exception) {
        Log.e(TAG, "Не удалось применить общую настройку $key", e)
        false
    }
}

/** Значения общих настроек строкой (чистая логика). */
object SharedSettingsCodec {
    fun night(enabled: Boolean, startMin: Int, endMin: Int) = "${if (enabled) 1 else 0},$startMin,$endMin"

    /** (вкл, начало, конец); null — строка повреждена или время вне суток. */
    fun parseNight(s: String): Triple<Boolean, Int, Int>? {
        val p = s.split(',')
        if (p.size != 3) return null
        val on = when (p[0]) { "1" -> true; "0" -> false; else -> return null }
        val start = p[1].toIntOrNull()?.takeIf { it in BlockRules.MINUTE_RANGE } ?: return null
        val end = p[2].toIntOrNull()?.takeIf { it in BlockRules.MINUTE_RANGE } ?: return null
        return Triple(on, start, end)
    }

    /** День включения оплаты шагов; null — выключено. */
    fun steps(sinceDay: Long?) = sinceDay?.toString() ?: ""

    /** Результат: Result(день) или Result(null) — выключено; null — строка повреждена. */
    fun parseSteps(s: String): Result<Long?>? = when {
        s.isEmpty() -> Result.success(null)
        else -> s.toLongOrNull()?.let { Result.success(it) }
    }
}
