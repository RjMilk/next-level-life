package com.nextlevellife.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nextlevellife.app.sleep.NightStatus
import com.nextlevellife.app.sleep.ObservationGap
import com.nextlevellife.app.sleep.PhoneEvent
import com.nextlevellife.app.sleep.PhoneEventType
import com.nextlevellife.app.sleep.SleepNight
import java.time.Instant
import java.time.ZoneId

/**
 * Единицы подхода (столбец `sets.measurement_type`): повторения или удержание позы.
 * Неизвестное значение (из более новой версии) читается как [REPS].
 */
enum class Measurement {
    REPS,
    HOLD;

    companion object {
        fun parse(value: String?): Measurement = entries.firstOrNull { it.name == value } ?: REPS
    }
}

/**
 * Один подход одного упражнения. [runId] — прохождение комплекса, в которое входит подход.
 * [measurement] — единицы: у [Measurement.HOLD] (планка) [reps] = 0, [heldMs] — засчитанное
 * время удержания, [durationMs] — полное время подхода. У повторений [heldMs] = 0.
 */
data class SetEntry(
    val id: Long, val timeMs: Long, val day: Long,
    val exerciseId: String, val exerciseName: String, val weightKg: Int?,
    val reps: Int, val durationMs: Long, val earnedMs: Long, val xp: Long, val runId: Long?,
    val measurement: Measurement = Measurement.REPS,
    val heldMs: Long = 0L,
)

/** Одно прохождение комплекса (Минотавр, Синди). */
data class ComplexRun(
    val id: Long, val timeMs: Long, val day: Long, val complexId: String, val weightKg: Int?,
    val durationMs: Long, val rounds: Int?, val extraReps: Int?, val totalReps: Int,
    val earnedMs: Long, val xp: Long, val completed: Boolean,
)

/**
 * Итог дня для календаря и списка дней. [reps] — только повторения; [heldMs] — засчитанное время
 * удержаний (планка) за день: секунды с повторениями не складываются.
 */
data class DaySummary(
    val day: Long, val sets: Int, val reps: Int, val earnedMs: Long, val xp: Long,
    val heldMs: Long = 0L,
)

/**
 * Сколько сделано по одному упражнению (и весу) за день. У удержания ([measurement] = HOLD)
 * [reps] = 0, а [heldMs] — сумма засчитанного времени.
 */
data class ExerciseTotal(
    val exerciseId: String, val exerciseName: String, val weightKg: Int?, val sets: Int, val reps: Int,
    val heldMs: Long = 0L,
    val measurement: Measurement = Measurement.REPS,
)

/**
 * Рекорды упражнения за всё время. [bestReps] и [bestWeightKg] — лучший отдельный подход
 * (null — упражнение делалось только в комплексах), [totalReps] и [sets] — всё вместе с комплексами.
 * Для удержания ([measurement] = HOLD): [bestHeldMs] — самый длинный засчитанный подход,
 * [totalHeldMs] — всё засчитанное время; [bestReps] и [bestWeightKg] — null, [totalReps] — 0.
 */
data class ExerciseRecord(
    val exerciseId: String, val exerciseName: String,
    val bestReps: Int?, val bestWeightKg: Int?,
    val totalReps: Long, val sets: Int, val lastDay: Long,
    val measurement: Measurement = Measurement.REPS,
    val bestHeldMs: Long? = null,
    val totalHeldMs: Long = 0L,
)

/**
 * Шаги и пробежки за локальные сутки из Health Connect.
 * [steps] — все шаги за день (агрегат Health Connect без двойного счёта); null — данных нет.
 * [runSteps] — шаги во время пробежек с дистанцией (не оплачиваются второй раз как ходьба).
 * [sources] — пакеты приложений, откуда пришли данные. [rewardedMs] — сколько уже начислено за день.
 */
data class ActivityDay(
    val day: Long,
    val steps: Long?,
    val runDistanceM: Long,
    val runDurationMs: Long,
    val runSteps: Long,
    val runsCount: Int,
    val sources: List<String>,
    val rewardedMs: Long,
    val updatedAtMs: Long,
) {
    /** Есть что показать: шаги или пробежка. Подтверждённый ноль шагов днём активности не считается. */
    val hasActivity: Boolean get() = (steps ?: 0L) > 0L || runsCount > 0
}

/** Одна пробежка из Health Connect. [day] — локальные сутки начала. */
data class ActivityRun(
    val externalId: String,
    val startMs: Long,
    val endMs: Long,
    val day: Long,
    val distanceM: Long,
    val steps: Long,
    val source: String,
    val exerciseType: Int,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Переход к блокировке. [reason] — имя BlockReason (NIGHT, LIMIT, BALANCE); [trigger] — что привело к блоку:
 * `open` (открыли заблокированное приложение), `tick` (правило сработало во время просмотра),
 * `charge_failed` (журнал не принимает списания). [utcMs] — доверенное время, [accountingDay] — учётные сутки.
 */
data class BlockEvent(
    val id: Long,
    val utcMs: Long,
    val accountingDay: Long,
    val packageName: String,
    val reason: String,
    val trigger: String,
)

/**
 * Подход, который ещё не записан: время, день и прохождение проставляет [Diary].
 * Удержание — [measurement] = HOLD, [reps] = 0, [heldMs] — засчитанное время.
 */
data class NewSet(
    val exerciseId: String, val exerciseName: String, val weightKg: Int?,
    val reps: Int, val durationMs: Long, val earnedMs: Long, val xp: Long,
    val measurement: Measurement = Measurement.REPS,
    val heldMs: Long = 0L,
)

/**
 * Дневник тренировок и журнал баланса в SQLite (diary.db). Хранится бессрочно.
 * Писать сюда стоит только через [Workouts] и [Ledger], чтобы дневник и баланс не расходились.
 *
 * Все обращения к базе идут под одной блокировкой — монитором этого объекта ([Ledger] берёт её же).
 */
object Diary {
    private var helper: Helper? = null
    private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (helper == null) helper = Helper(context.applicationContext)
    }

    @Synchronized
    internal fun db(): SQLiteDatabase =
        (helper ?: throw IllegalStateException("Diary.init не вызван")).writableDatabase

    /**
     * Транзакция записи. Вложенные вызовы становятся вложенными транзакциями SQLite:
     * если упадёт любая из них, откатится всё. Исключения внутри [block] глотать нельзя —
     * иначе внешняя транзакция молча откатится, а вызывающий решит, что всё записано.
     * После конца внешней транзакции (успешной или нет) кеш баланса перечитывается из базы.
     */
    @Synchronized
    internal fun <T> write(block: (SQLiteDatabase) -> T): T {
        val db = db()
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
            if (!db.inTransaction()) Ledger.reloadCacheQuietly()
        }
    }

    // ---------- Служебные значения (meta) ----------

    @Synchronized
    internal fun metaGet(key: String): String? =
        query("SELECT value FROM meta WHERE key = ?", key) { it.getString(0) }.firstOrNull()

    @Synchronized
    internal fun metaPut(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db().insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    internal fun metaDelete(key: String) {
        db().delete("meta", "key = ?", arrayOf(key))
    }

    // ---------- События блокировки ----------

    /**
     * Записывает переход к блокировке: приложение [packageName] закрыто по причине [reason]
     * (имя BlockReason: NIGHT, LIMIT, BALANCE) из-за [trigger] (open, tick, charge_failed).
     * [utcMs] и [accountingDay] — доверенное время и учётные сутки. Возвращает id строки.
     */
    @Synchronized
    fun addBlockEvent(utcMs: Long, accountingDay: Long, packageName: String, reason: String, trigger: String): Long =
        db().compileStatement(
            "INSERT INTO block_events (utc_ms, accounting_day, package_name, reason, \"trigger\") VALUES (?, ?, ?, ?, ?)",
        ).use { st ->
            st.bindLong(1, utcMs)
            st.bindLong(2, accountingDay)
            st.bindString(3, packageName)
            st.bindString(4, reason)
            st.bindString(5, trigger)
            st.executeInsert()
        }

    /** События блокировки за учётные сутки [fromDay]..[toDay], по времени. */
    @Synchronized
    fun blockEvents(fromDay: Long, toDay: Long): List<BlockEvent> = query(
        "SELECT id, utc_ms, accounting_day, package_name, reason, \"trigger\" FROM block_events " +
            "WHERE accounting_day BETWEEN ? AND ? ORDER BY utc_ms, id",
        fromDay.toString(), toDay.toString(),
    ) {
        BlockEvent(
            id = it.getLong(0), utcMs = it.getLong(1), accountingDay = it.getLong(2),
            packageName = it.getString(3), reason = it.getString(4), trigger = it.getString(5),
        )
    }

    // ---------- События экрана и ночной перерыв ----------

    /**
     * Записывает событие экрана. Повтор с тем же [dedupKey] молча пропускается.
     * Только короткая вставка: вызывать с фонового потока ([com.nextlevellife.app.sleep.PhoneEvents]).
     */
    @Synchronized
    internal fun addPhoneEvent(
        deviceId: String, bootCount: Int?, elapsedMs: Long, utcMs: Long, zoneId: String,
        type: PhoneEventType, source: String, interactive: Boolean?, locked: Boolean?, dedupKey: String,
    ) {
        val values = ContentValues().apply {
            put("device_id", deviceId)
            if (bootCount != null) put("boot_count", bootCount) else putNull("boot_count")
            put("elapsed_ms", elapsedMs)
            put("utc_ms", utcMs)
            put("zone_id", zoneId)
            put("type", type.name)
            put("source", source)
            if (interactive != null) put("interactive", if (interactive) 1 else 0) else putNull("interactive")
            if (locked != null) put("locked", if (locked) 1 else 0) else putNull("locked")
            put("dedup_key", dedupKey)
        }
        db().insertWithOnConflict("phone_events", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Последнее событие экрана устройства [deviceId] раньше [beforeMs] (null — без ограничения). */
    @Synchronized
    internal fun lastPhoneEvent(deviceId: String, beforeMs: Long? = null): PhoneEvent? = query(
        "SELECT $PHONE_EVENT_COLS FROM phone_events WHERE device_id = ? AND utc_ms < ? ORDER BY utc_ms DESC, id DESC LIMIT 1",
        deviceId, (beforeMs ?: Long.MAX_VALUE).toString(),
    ) { it.toPhoneEvent() }.firstOrNull()

    /** События экрана устройства [deviceId] в [fromMs, toMs), по времени. */
    @Synchronized
    internal fun phoneEvents(deviceId: String, fromMs: Long, toMs: Long): List<PhoneEvent> = query(
        "SELECT $PHONE_EVENT_COLS FROM phone_events WHERE device_id = ? AND utc_ms >= ? AND utc_ms < ? ORDER BY utc_ms, id",
        deviceId, fromMs.toString(), toMs.toString(),
    ) { it.toPhoneEvent() }

    /** Время самого раннего хранимого события экрана устройства; null — событий нет. */
    @Synchronized
    internal fun firstPhoneEventMs(deviceId: String): Long? = query(
        "SELECT MIN(utc_ms) FROM phone_events WHERE device_id = ?", deviceId,
    ) { if (it.isNull(0)) null else it.getLong(0) }.firstOrNull()

    @Synchronized
    internal fun addObservationGap(deviceId: String, startMs: Long, endMs: Long, reason: String) {
        val values = ContentValues().apply {
            put("device_id", deviceId)
            put("start_ms", startMs)
            put("end_ms", endMs)
            put("reason", reason)
        }
        db().insertOrThrow("observation_gaps", null, values)
    }

    /** Пропуски наблюдения устройства [deviceId], пересекающие [fromMs, toMs). */
    @Synchronized
    internal fun observationGaps(deviceId: String, fromMs: Long, toMs: Long): List<ObservationGap> = query(
        "SELECT start_ms, end_ms FROM observation_gaps WHERE device_id = ? AND end_ms > ? AND start_ms < ? ORDER BY start_ms",
        deviceId, fromMs.toString(), toMs.toString(),
    ) { ObservationGap(it.getLong(0), it.getLong(1)) }

    /** Чистка сырых данных: события и пропуски старше [beforeMs]. Итоги ночей не трогаются. */
    @Synchronized
    internal fun prunePhoneData(beforeMs: Long) {
        db().delete("phone_events", "utc_ms < ?", arrayOf(beforeMs.toString()))
        db().delete("observation_gaps", "end_ms < ?", arrayOf(beforeMs.toString()))
    }

    /** Записывает итог ночи устройства [deviceId] (вставка или замена). */
    @Synchronized
    internal fun upsertSleepNight(deviceId: String, n: SleepNight) {
        val values = ContentValues().apply {
            put("device_id", deviceId)
            put("wake_day", n.wakeDay)
            if (n.startMs != null) put("start_ms", n.startMs) else putNull("start_ms")
            if (n.endMs != null) put("end_ms", n.endMs) else putNull("end_ms")
            put("interruption_ms", n.interruptionMs)
            put("interruption_count", n.interruptionCount)
            put("status", n.status.name)
            put("phone_free", if (n.phoneFree) 1 else 0)
            if (n.nightStartMin != null) put("night_start_min", n.nightStartMin) else putNull("night_start_min")
            if (n.nightEndMin != null) put("night_end_min", n.nightEndMin) else putNull("night_end_min")
            put("zone_id", n.zoneId)
            put("rule_version", n.ruleVersion)
        }
        db().insertWithOnConflict("sleep_nights", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Окончательные ночи устройства [deviceId] с днями пробуждения [fromDay]..[toDay]. */
    @Synchronized
    internal fun sleepNightsOf(deviceId: String, fromDay: Long, toDay: Long): List<SleepNight> = query(
        "SELECT $SLEEP_COLS FROM sleep_nights WHERE device_id = ? AND wake_day BETWEEN ? AND ? ORDER BY wake_day",
        deviceId, fromDay.toString(), toDay.toString(),
    ) { it.toSleepNight() }

    /**
     * Окончательные ночные перерывы с днями пробуждения [fromDay]..[toDay] (учётные сутки), по дню.
     * Берутся ночи этого телефона; день, за который у этого телефона записи нет, может прийти из копии
     * другого телефона (история после восстановления). Ночь сегодняшнего утра появляется здесь после 12:00:
     * до этого её считает [com.nextlevellife.app.sleep.SleepRepository] на лету. Чтобы досчитать
     * пропущенные ночи, сначала вызвать [com.nextlevellife.app.sleep.SleepRepository.refresh] (с фонового потока).
     */
    @Synchronized
    fun sleepNights(fromDay: Long, toDay: Long): List<SleepNight> {
        val device = Ledger.deviceId()
        return query(
            "SELECT $SLEEP_COLS, device_id = ? FROM sleep_nights WHERE wake_day BETWEEN ? AND ? ORDER BY wake_day",
            device, fromDay.toString(), toDay.toString(),
        ) { it.toSleepNight() to (it.getInt(SLEEP_COL_COUNT) != 0) }
            .groupBy { it.first.wakeDay }
            .map { (_, rows) -> (rows.firstOrNull { it.second } ?: rows.first()).first }
            .sortedBy { it.wakeDay }
    }

    // ---------- Файл базы (для резервной копии) ----------

    internal fun dbFile(context: Context): java.io.File = context.applicationContext.getDatabasePath(NAME)

    /** Закрывает базу; следующий доступ — только после [reopen]. Вызывать под блокировкой Diary. */
    @Synchronized
    internal fun close() {
        helper?.close()
        helper = null
    }

    /** Открывает базу заново (после подмены файла) и сразу применяет миграции. */
    @Synchronized
    internal fun reopen() {
        val ctx = appContext ?: throw IllegalStateException("Diary.init не вызван")
        if (helper == null) helper = Helper(ctx)
        db()
    }

    // ---------- Чтение ----------

    @Synchronized
    fun setsForDay(day: Long): List<SetEntry> =
        query("SELECT $SET_COLS FROM sets WHERE day = ? ORDER BY time_ms, id", day.toString()) { it.toSetEntry() }

    @Synchronized
    fun runsForDay(day: Long): List<ComplexRun> =
        query("SELECT $RUN_COLS FROM complex_runs WHERE day = ? ORDER BY time_ms, id", day.toString()) { it.toRun() }

    @Synchronized
    fun exerciseTotalsForDay(day: Long): List<ExerciseTotal> = DiaryMath.totals(setsForDay(day))

    /** Итоги дней: повторения — только у подходов-повторений, время удержаний — отдельно ([DaySummary.heldMs]). */
    @Synchronized
    fun daySummaries(fromDay: Long, toDay: Long): List<DaySummary> = query(
        "SELECT day, COUNT(*), " +
            "SUM(CASE WHEN measurement_type = 'HOLD' THEN 0 ELSE reps END), SUM(earned_ms), SUM(xp), " +
            "SUM(CASE WHEN measurement_type = 'HOLD' THEN held_ms ELSE 0 END) FROM sets " +
            "WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day",
        fromDay.toString(), toDay.toString(),
    ) { DaySummary(it.getLong(0), it.getInt(1), it.getInt(2), it.getLong(3), it.getLong(4), it.getLong(5)) }

    /**
     * Засчитанное время удержаний упражнения [exerciseId] (по умолчанию — планки) по учётным дням
     * [fromDay]..[toDay]: день → сумма `held_ms` (включая подходы комплексов, если они появятся).
     * В карте только дни, в которые упражнение делалось.
     */
    @Synchronized
    fun holdMsByDay(fromDay: Long, toDay: Long, exerciseId: String = Exercises.plank.id): Map<Long, Long> = query(
        "SELECT day, SUM(held_ms) FROM sets " +
            "WHERE measurement_type = 'HOLD' AND exercise_id = ? AND day BETWEEN ? AND ? GROUP BY day ORDER BY day",
        exerciseId, fromDay.toString(), toDay.toString(),
    ) { it.getLong(0) to it.getLong(1) }.toMap()

    /** Засчитанное время удержаний упражнения [exerciseId] за всё время (мс). */
    @Synchronized
    fun totalHoldMs(exerciseId: String = Exercises.plank.id): Long = query(
        "SELECT COALESCE(SUM(held_ms), 0) FROM sets WHERE measurement_type = 'HOLD' AND exercise_id = ?",
        exerciseId,
    ) { it.getLong(0) }.firstOrNull() ?: 0L

    @Synchronized
    fun trainedDays(fromDay: Long, toDay: Long): Set<Long> = query(
        "SELECT DISTINCT day FROM sets WHERE day BETWEEN ? AND ?",
        fromDay.toString(), toDay.toString(),
    ) { it.getLong(0) }.toSet()

    /**
     * Итоги по упражнению за последние [limitDays] дней, в которые оно делалось; новые сверху.
     * Если в один день были разные веса — по записи на каждый вес.
     */
    @Synchronized
    fun exerciseHistory(exerciseId: String, limitDays: Int): List<Pair<Long, ExerciseTotal>> {
        if (limitDays <= 0) return emptyList()
        val days = query(
            "SELECT DISTINCT day FROM sets WHERE exercise_id = ? ORDER BY day DESC LIMIT $limitDays",
            exerciseId,
        ) { it.getLong(0) }
        if (days.isEmpty()) return emptyList()
        val sets = query(
            "SELECT $SET_COLS FROM sets WHERE exercise_id = ? AND day >= ? ORDER BY time_ms, id",
            exerciseId, days.last().toString(),
        ) { it.toSetEntry() }
        return sets.groupBy { it.day }.toSortedMap(compareByDescending { it })
            .flatMap { (day, daySets) -> DiaryMath.totals(daySets).map { day to it } }
    }

    /** Лучшее (минимальное) время среди засчитанных прохождений; null — прохождений не было. */
    @Synchronized
    fun bestComplexTimeMs(complexId: String, weightKg: Int?): Long? {
        // null нельзя передать аргументом запроса, поэтому IS NULL отдельной веткой.
        val (weightSql, args) =
            if (weightKg == null) "weight_kg IS NULL" to arrayOf(complexId)
            else "weight_kg = ?" to arrayOf(complexId, weightKg.toString())
        return query(
            "SELECT MIN(duration_ms) FROM complex_runs " +
                "WHERE complex_id = ? AND completed = 1 AND duration_ms > 0 AND $weightSql",
            *args,
        ) { if (it.isNull(0)) null else it.getLong(0) }.firstOrNull()
    }

    /** Лучший результат по кругам: (круги, повторения сверх). */
    @Synchronized
    fun bestComplexRounds(complexId: String): Pair<Int, Int>? = query(
        "SELECT rounds, COALESCE(extra_reps, 0) FROM complex_runs " +
            "WHERE complex_id = ? AND completed = 1 AND rounds IS NOT NULL " +
            "ORDER BY rounds DESC, COALESCE(extra_reps, 0) DESC LIMIT 1",
        complexId,
    ) { it.getInt(0) to it.getInt(1) }.firstOrNull()

    @Synchronized
    fun recentRuns(complexId: String, limit: Int): List<ComplexRun> {
        if (limit <= 0) return emptyList()
        return query(
            "SELECT $RUN_COLS FROM complex_runs WHERE complex_id = ? ORDER BY time_ms DESC, id DESC LIMIT $limit",
            complexId,
        ) { it.toRun() }
    }

    /**
     * Есть ли в дневнике хоть одна запись: подход, комплекс, день с шагами или пробежка,
     * ночной перерыв (подтверждённый или с неполными данными; «нет данных» не считается).
     */
    @Synchronized
    fun hasEntries(): Boolean = query(
        "SELECT EXISTS(SELECT 1 FROM sets) OR EXISTS(SELECT 1 FROM complex_runs) " +
            "OR EXISTS(SELECT 1 FROM activity_days WHERE steps > 0 OR runs_count > 0) " +
            "OR EXISTS(SELECT 1 FROM sleep_nights WHERE status <> 'UNKNOWN')",
    ) { it.getInt(0) != 0 }.firstOrNull() ?: false

    // ---------- Шаги и пробежки (Health Connect) ----------

    @Synchronized
    fun activityDay(day: Long): ActivityDay? =
        query("SELECT $ACTIVITY_COLS FROM activity_days WHERE day = ?", day.toString()) { it.toActivityDay() }.firstOrNull()

    /** Дни активности в [fromDay]..[toDay], только те, что записаны. */
    @Synchronized
    fun activityDays(fromDay: Long, toDay: Long): List<ActivityDay> = query(
        "SELECT $ACTIVITY_COLS FROM activity_days WHERE day BETWEEN ? AND ? ORDER BY day",
        fromDay.toString(), toDay.toString(),
    ) { it.toActivityDay() }

    @Synchronized
    fun activityRunsForDay(day: Long): List<ActivityRun> = activityRuns(day, day)

    @Synchronized
    fun activityRuns(fromDay: Long, toDay: Long): List<ActivityRun> = query(
        "SELECT $ACTIVITY_RUN_COLS FROM activity_runs WHERE day BETWEEN ? AND ? ORDER BY start_ms, external_id",
        fromDay.toString(), toDay.toString(),
    ) { it.toActivityRun() }

    /**
     * Учётные сутки уже записанных пробежек по их external_id (только найденные).
     * Записанная пробежка своего дня не меняет — даже если потом сменился учётный пояс.
     */
    @Synchronized
    fun activityRunDays(externalIds: Collection<String>): Map<String, Long> {
        if (externalIds.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        // Порциями: у SQLite ограничено число параметров запроса.
        for (chunk in externalIds.distinct().chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            query("SELECT external_id, day FROM activity_runs WHERE external_id IN ($marks)", *chunk.toTypedArray()) {
                it.getString(0) to it.getLong(1)
            }.forEach { (id, day) -> out[id] = day }
        }
        return out
    }

    /** Дни с пробежкой: в календаре они тренировочные. Обычные шаги день тренировочным не делают. */
    @Synchronized
    fun runDays(fromDay: Long, toDay: Long): Set<Long> = query(
        "SELECT DISTINCT day FROM activity_runs WHERE day BETWEEN ? AND ?",
        fromDay.toString(), toDay.toString(),
    ) { it.getLong(0) }.toSet()

    /** Рекорды активности: (день, шаги) с наибольшим числом шагов и самая длинная пробежка. */
    @Synchronized
    fun activityRecords(): Pair<Pair<Long, Long>?, ActivityRun?> {
        val steps = query(
            "SELECT day, steps FROM activity_days WHERE steps > 0 ORDER BY steps DESC, day DESC LIMIT 1",
        ) { it.getLong(0) to it.getLong(1) }.firstOrNull()
        val run = query(
            "SELECT $ACTIVITY_RUN_COLS FROM activity_runs WHERE distance_m > 0 " +
                "ORDER BY distance_m DESC, start_ms DESC LIMIT 1",
        ) { it.toActivityRun() }.firstOrNull()
        return steps to run
    }

    /** Записывает день активности целиком (вставка или замена). Только внутри [write] из ActivitySync. */
    internal fun upsertActivityDay(db: SQLiteDatabase, d: ActivityDay) {
        val values = ContentValues().apply {
            put("day", d.day)
            if (d.steps != null) put("steps", d.steps) else putNull("steps")
            put("run_distance_m", d.runDistanceM)
            put("run_duration_ms", d.runDurationMs)
            put("run_steps", d.runSteps)
            put("runs_count", d.runsCount)
            put("sources", d.sources.joinToString(","))
            put("rewarded_ms", d.rewardedMs)
            put("updated_at_ms", d.updatedAtMs)
        }
        db.insertWithOnConflict("activity_days", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Заменяет пробежки дней [fromDay]..[toDay] свежим списком: удалённые в Health Connect
     * пропадают и здесь. Только внутри [write] из ActivitySync.
     */
    internal fun replaceActivityRuns(db: SQLiteDatabase, fromDay: Long, toDay: Long, runs: List<ActivityRun>) {
        db.delete("activity_runs", "day BETWEEN ? AND ?", arrayOf(fromDay.toString(), toDay.toString()))
        for (r in runs) {
            val values = ContentValues().apply {
                put("external_id", r.externalId)
                put("start_ms", r.startMs)
                put("end_ms", r.endMs)
                put("day", r.day)
                put("distance_m", r.distanceM)
                put("steps", r.steps)
                put("source", r.source)
                put("exercise_type", r.exerciseType)
            }
            // Та же сессия могла остаться за окном (сдвиг часового пояса) — заменяем её.
            db.insertWithOnConflict("activity_runs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * Рекорды по всем упражнениям, которые встречались в дневнике (правила — [DiaryMath.records]).
     * Читаются все подходы: даже за годы ежедневных тренировок это тысячи коротких строк.
     */
    @Synchronized
    fun exerciseRecords(): List<ExerciseRecord> =
        DiaryMath.records(query("SELECT $SET_COLS FROM sets ORDER BY time_ms, id") { it.toSetEntry() })

    // ---------- Запись (только для Workouts и переноса старой истории) ----------

    @Synchronized
    internal fun addSet(timeMs: Long, set: NewSet): Long =
        insertSet(db(), timeMs, DiaryMath.dayOf(timeMs), set, runId = null)

    /** Прохождение и его подходы пишутся одной транзакцией: либо всё, либо ничего. */
    @Synchronized
    internal fun addRun(
        timeMs: Long, complexId: String, weightKg: Int?, durationMs: Long,
        rounds: Int?, extraReps: Int?, totalReps: Int, earnedMs: Long, xp: Long,
        completed: Boolean, sets: List<NewSet>,
    ): Long = write { db ->
        insertRun(db, timeMs, complexId, weightKg, durationMs, rounds, extraReps, totalReps, earnedMs, xp, completed, sets)
    }

    /**
     * Переносит записи старой JSON-истории Store, которых в дневнике ещё нет (новее отметки
     * [KEY_LEGACY_UNTIL] в meta). Импорт и новая отметка пишутся одной транзакцией.
     * [prefsUntil] — отметка из SharedPreferences, где она жила до 0.3; используется, если в meta её ещё нет.
     * Возвращает число перенесённых записей.
     */
    @Synchronized
    internal fun syncLegacy(entries: List<Store.HistoryEntry>, prefsUntil: Long?): Int = write {
        val until = metaGet(KEY_LEGACY_UNTIL)?.toLongOrNull() ?: prefsUntil ?: Long.MIN_VALUE
        val fresh = entries.filter { it.timeMs > until }
        val count = importLegacy(fresh)
        val newUntil = maxOf(until, fresh.maxOfOrNull { it.timeMs } ?: Long.MIN_VALUE)
        if (metaGet(KEY_LEGACY_UNTIL) != newUntil.toString()) metaPut(KEY_LEGACY_UNTIL, newUntil.toString())
        count
    }

    /**
     * Переносит записи из старой JSON-истории Store. Нераспознанные заголовки пропускаются:
     * упражнение по ним не восстановить. Возвращает число перенесённых записей.
     */
    @Synchronized
    private fun importLegacy(entries: List<Store.HistoryEntry>): Int = write { db ->
        var count = 0
        for (e in entries.sortedBy { it.timeMs }) {
            when (val item = DiaryMath.parseLegacy(e.title)) {
                is DiaryMath.LegacySet -> {
                    val set = NewSet(
                        item.exercise.id, item.exercise.name, item.weightKg,
                        item.reps, 0L, e.earnedMs, e.xp,
                    )
                    insertSet(db, e.timeMs, DiaryMath.dayOf(e.timeMs), set, runId = null)
                }
                is DiaryMath.LegacyMinotaur -> insertRun(
                    db, e.timeMs, Minotaur.ID, item.weightKg, item.durationMs,
                    rounds = null, extraReps = null, totalReps = Minotaur.totalReps,
                    earnedMs = e.earnedMs, xp = e.xp, completed = true,
                    sets = WorkoutMath.minotaurSets(item.weightKg, e.earnedMs, e.xp),
                )
                null -> continue
            }
            count++
        }
        count
    }

    private fun insertRun(
        db: SQLiteDatabase, timeMs: Long, complexId: String, weightKg: Int?, durationMs: Long,
        rounds: Int?, extraReps: Int?, totalReps: Int, earnedMs: Long, xp: Long,
        completed: Boolean, sets: List<NewSet>,
    ): Long {
        val day = DiaryMath.dayOf(timeMs)
        val values = ContentValues().apply {
            put("time_ms", timeMs)
            put("day", day)
            put("complex_id", complexId)
            put("weight_kg", weightKg)
            put("duration_ms", durationMs)
            put("rounds", rounds)
            put("extra_reps", extraReps)
            put("total_reps", totalReps)
            put("earned_ms", earnedMs)
            put("xp", xp)
            put("completed", if (completed) 1 else 0)
        }
        val runId = db.insertOrThrow("complex_runs", null, values)
        for (set in sets) insertSet(db, timeMs, day, set, runId)
        return runId
    }

    private fun insertSet(db: SQLiteDatabase, timeMs: Long, day: Long, set: NewSet, runId: Long?): Long {
        val values = ContentValues().apply {
            put("time_ms", timeMs)
            put("day", day)
            put("exercise_id", set.exerciseId)
            put("exercise_name", set.exerciseName)
            put("weight_kg", set.weightKg)
            put("reps", set.reps)
            put("duration_ms", set.durationMs)
            put("earned_ms", set.earnedMs)
            put("xp", set.xp)
            put("run_id", runId)
            put("measurement_type", set.measurement.name)
            put("held_ms", set.heldMs)
        }
        return db.insertOrThrow("sets", null, values)
    }

    internal inline fun <T> query(sql: String, vararg args: String, read: (Cursor) -> T): List<T> =
        db().rawQuery(sql, args).use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out.add(read(c))
            out
        }

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private const val SET_COLS =
        "id, time_ms, day, exercise_id, exercise_name, weight_kg, reps, duration_ms, earned_ms, xp, run_id, " +
            "measurement_type, held_ms"
    private const val RUN_COLS =
        "id, time_ms, day, complex_id, weight_kg, duration_ms, rounds, extra_reps, total_reps, earned_ms, xp, completed"

    private fun Cursor.toSetEntry() = SetEntry(
        id = getLong(0), timeMs = getLong(1), day = getLong(2),
        exerciseId = getString(3), exerciseName = getString(4), weightKg = intOrNull(5),
        reps = getInt(6), durationMs = getLong(7), earnedMs = getLong(8), xp = getLong(9),
        runId = longOrNull(10),
        measurement = Measurement.parse(getString(11)),
        heldMs = getLong(12),
    )

    private const val ACTIVITY_COLS =
        "day, steps, run_distance_m, run_duration_ms, run_steps, runs_count, sources, rewarded_ms, updated_at_ms"
    private const val ACTIVITY_RUN_COLS =
        "external_id, start_ms, end_ms, day, distance_m, steps, source, exercise_type"

    private fun Cursor.toActivityDay() = ActivityDay(
        day = getLong(0), steps = longOrNull(1), runDistanceM = getLong(2), runDurationMs = getLong(3),
        runSteps = getLong(4), runsCount = getInt(5),
        sources = getString(6).split(',').filter { it.isNotBlank() },
        rewardedMs = getLong(7), updatedAtMs = getLong(8),
    )

    private fun Cursor.toActivityRun() = ActivityRun(
        externalId = getString(0), startMs = getLong(1), endMs = getLong(2), day = getLong(3),
        distanceM = getLong(4), steps = getLong(5), source = getString(6), exerciseType = getInt(7),
    )

    private const val PHONE_EVENT_COLS = "utc_ms, type, interactive, locked, boot_count, source"

    private fun Cursor.boolOrNull(i: Int): Boolean? = if (isNull(i)) null else getInt(i) != 0

    private fun Cursor.toPhoneEvent() = PhoneEvent(
        utcMs = getLong(0),
        // Неизвестный тип (из более новой версии) читается как состояние «неизвестно».
        type = PhoneEventType.entries.firstOrNull { it.name == getString(1) } ?: PhoneEventType.STATE,
        interactive = boolOrNull(2),
        locked = boolOrNull(3),
        bootCount = intOrNull(4),
        source = getString(5),
    )

    private const val SLEEP_COLS =
        "wake_day, status, start_ms, end_ms, interruption_ms, interruption_count, phone_free, " +
            "night_start_min, night_end_min, zone_id, rule_version"
    private const val SLEEP_COL_COUNT = 11

    private fun Cursor.toSleepNight() = SleepNight(
        wakeDay = getLong(0),
        status = NightStatus.entries.firstOrNull { it.name == getString(1) } ?: NightStatus.UNKNOWN,
        startMs = longOrNull(2),
        endMs = longOrNull(3),
        interruptionMs = getLong(4),
        interruptionCount = getInt(5),
        phoneFree = getInt(6) != 0,
        nightStartMin = intOrNull(7),
        nightEndMin = intOrNull(8),
        zoneId = getString(9),
        ruleVersion = getInt(10),
        final = true,
    )

    private fun Cursor.toRun() = ComplexRun(
        id = getLong(0), timeMs = getLong(1), day = getLong(2), complexId = getString(3),
        weightKg = intOrNull(4), durationMs = getLong(5), rounds = intOrNull(6),
        extraReps = intOrNull(7), totalReps = getInt(8), earnedMs = getLong(9), xp = getLong(10),
        completed = getInt(11) != 0,
    )

    private class Helper(context: Context) : SQLiteOpenHelper(context, NAME, null, MIGRATIONS.size) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) = migrate(db, from = 0)

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = migrate(db, oldVersion)

        // Новая установка проходит те же шаги, что и обновление: схема всегда одна.
        private fun migrate(db: SQLiteDatabase, from: Int) {
            for (v in from until MIGRATIONS.size) MIGRATIONS[v].forEach(db::execSQL)
        }
    }

    internal const val NAME = "diary.db"

    /** Отметка переноса старой JSON-истории (время последней перенесённой записи). */
    internal const val KEY_LEGACY_UNTIL = "diary_legacy_until"

    /** Текущая версия схемы. */
    internal val SCHEMA_VERSION: Int get() = MIGRATIONS.size

    /**
     * Шаги схемы: MIGRATIONS[i] переводит базу с версии i на i + 1.
     * Старые шаги не менять — только дописывать новые в конец.
     */
    internal val MIGRATIONS: List<List<String>> = listOf(
        // 0 → 1
        listOf(
            """CREATE TABLE complex_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time_ms INTEGER NOT NULL,
                day INTEGER NOT NULL,
                complex_id TEXT NOT NULL,
                weight_kg INTEGER,
                duration_ms INTEGER NOT NULL,
                rounds INTEGER,
                extra_reps INTEGER,
                total_reps INTEGER NOT NULL,
                earned_ms INTEGER NOT NULL,
                xp INTEGER NOT NULL,
                completed INTEGER NOT NULL
            )""",
            "CREATE INDEX idx_runs_day ON complex_runs(day)",
            "CREATE INDEX idx_runs_complex ON complex_runs(complex_id, day)",
            """CREATE TABLE sets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time_ms INTEGER NOT NULL,
                day INTEGER NOT NULL,
                exercise_id TEXT NOT NULL,
                exercise_name TEXT NOT NULL,
                weight_kg INTEGER,
                reps INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                earned_ms INTEGER NOT NULL,
                xp INTEGER NOT NULL,
                run_id INTEGER REFERENCES complex_runs(id) ON DELETE CASCADE
            )""",
            "CREATE INDEX idx_sets_day ON sets(day)",
            "CREATE INDEX idx_sets_exercise ON sets(exercise_id, day)",
            "CREATE INDEX idx_sets_run ON sets(run_id)",
        ),
        // 1 → 2: журнал операций баланса, итог счёта и служебные значения (версия 0.3).
        listOf(
            """CREATE TABLE balance_ops (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                device_id TEXT NOT NULL,
                idempotency_key TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                delta_ms INTEGER NOT NULL,
                delta_xp INTEGER NOT NULL DEFAULT 0,
                occurred_at_ms INTEGER NOT NULL,
                recorded_at_ms INTEGER NOT NULL,
                accounting_day INTEGER NOT NULL,
                zone_id TEXT NOT NULL,
                source_type TEXT NOT NULL,
                source_id TEXT NOT NULL,
                rule_version INTEGER NOT NULL,
                reverses_id TEXT,
                metadata_json TEXT NOT NULL
            )""",
            "CREATE INDEX idx_ops_day_kind ON balance_ops(accounting_day, kind)",
            "CREATE INDEX idx_ops_kind_day ON balance_ops(kind, accounting_day)",
            """CREATE TABLE account (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                balance_ms INTEGER NOT NULL,
                xp INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )""",
            "INSERT INTO account (id, balance_ms, xp, updated_at_ms) VALUES (1, 0, 0, 0)",
            "CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)",
        ),
        // 2 → 3: шаги и пробежки из Health Connect (версия 0.4). steps = NULL — данных за день нет.
        listOf(
            """CREATE TABLE activity_days (
                day INTEGER PRIMARY KEY,
                steps INTEGER,
                run_distance_m INTEGER NOT NULL DEFAULT 0,
                run_duration_ms INTEGER NOT NULL DEFAULT 0,
                run_steps INTEGER NOT NULL DEFAULT 0,
                runs_count INTEGER NOT NULL DEFAULT 0,
                sources TEXT NOT NULL DEFAULT '',
                rewarded_ms INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )""",
            """CREATE TABLE activity_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                external_id TEXT NOT NULL UNIQUE,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                day INTEGER NOT NULL,
                distance_m INTEGER NOT NULL,
                steps INTEGER NOT NULL,
                source TEXT NOT NULL,
                exercise_type INTEGER NOT NULL
            )""",
            "CREATE INDEX idx_activity_runs_day ON activity_runs(day)",
        ),
        // 3 → 4: события блокировки (переход к экрану блокировки), для характеристик.
        // utc_ms — доверенное время, accounting_day — учётные сутки ([TrustedClock]).
        listOf(
            """CREATE TABLE block_events (
                id INTEGER PRIMARY KEY,
                utc_ms INTEGER NOT NULL,
                accounting_day INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                reason TEXT NOT NULL,
                "trigger" TEXT NOT NULL
            )""",
            "CREATE INDEX idx_block_events_day ON block_events(accounting_day)",
        ),
        // 4 → 5: ночной перерыв в использовании телефона (версия 0.5).
        // phone_events — сырые события экрана (хранятся 90 дней): utc_ms — доверенное время, type —
        // SCREEN_ON / SCREEN_OFF / USER_PRESENT / STATE; interactive и locked — экран включён и показан ли экран
        // блокировки в момент события (NULL — не известно). observation_gaps — когда служба не работала.
        // sleep_nights — окончательные итоги ночей (хранятся всегда); status — OK / PARTIAL / UNKNOWN.
        listOf(
            """CREATE TABLE phone_events (
                id INTEGER PRIMARY KEY,
                device_id TEXT NOT NULL,
                boot_count INTEGER,
                elapsed_ms INTEGER NOT NULL,
                utc_ms INTEGER NOT NULL,
                zone_id TEXT NOT NULL,
                type TEXT NOT NULL,
                source TEXT NOT NULL,
                interactive INTEGER,
                locked INTEGER,
                dedup_key TEXT NOT NULL UNIQUE
            )""",
            "CREATE INDEX idx_phone_events_device_time ON phone_events(device_id, utc_ms)",
            """CREATE TABLE observation_gaps (
                id INTEGER PRIMARY KEY,
                device_id TEXT NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                reason TEXT NOT NULL
            )""",
            "CREATE INDEX idx_observation_gaps_device_end ON observation_gaps(device_id, end_ms)",
            """CREATE TABLE sleep_nights (
                device_id TEXT NOT NULL,
                wake_day INTEGER NOT NULL,
                start_ms INTEGER,
                end_ms INTEGER,
                interruption_ms INTEGER NOT NULL DEFAULT 0,
                interruption_count INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                phone_free INTEGER NOT NULL DEFAULT 0,
                night_start_min INTEGER,
                night_end_min INTEGER,
                zone_id TEXT NOT NULL,
                rule_version INTEGER NOT NULL,
                PRIMARY KEY (device_id, wake_day)
            )""",
        ),
        // 5 → 6: подходы-удержания (планка). measurement_type — REPS / HOLD; у HOLD reps = 0,
        // held_ms — засчитанное время, duration_ms — полное время подхода. Старые подходы — REPS с held_ms = 0.
        listOf(
            "ALTER TABLE sets ADD COLUMN measurement_type TEXT NOT NULL DEFAULT 'REPS'",
            "ALTER TABLE sets ADD COLUMN held_ms INTEGER NOT NULL DEFAULT 0",
        ),
    )
}

/** Чистая логика дневника, без Android: её проверяют юнит-тесты. */
internal object DiaryMath {
    fun dayOf(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Сворачивает подходы в итоги по (упражнение, вес) в порядке первого появления.
     * Название берётся из последнего подхода — оно самое свежее.
     */
    fun totals(sets: List<SetEntry>): List<ExerciseTotal> =
        sets.groupBy { it.exerciseId to it.weightKg }.map { (key, group) ->
            val hold = group.any { it.measurement == Measurement.HOLD }
            ExerciseTotal(
                exerciseId = key.first,
                exerciseName = group.last().exerciseName,
                weightKg = key.second,
                sets = group.size,
                // Секунды удержания с повторениями не складываются: у удержания повторений нет.
                reps = group.filter { it.measurement != Measurement.HOLD }.sumOf { it.reps },
                heldMs = group.filter { it.measurement == Measurement.HOLD }.sumOf { it.heldMs },
                measurement = if (hold) Measurement.HOLD else Measurement.REPS,
            )
        }

    /**
     * Рекорды по упражнениям в порядке первого появления.
     * Лучший подход ищется только среди отдельных подходов: подходы комплексов — это сумма
     * за весь комплекс, а не один подход. Всего — вместе с комплексами. Название — из самого
     * позднего подхода. Повторения: лучший — больше повторений, при равенстве тяжелее гиря,
     * при равенстве — более ранний. Удержание: лучший — самое длинное засчитанное время.
     */
    fun records(sets: List<SetEntry>): List<ExerciseRecord> =
        sets.groupBy { it.exerciseId }.map { (id, group) ->
            val latest = group.maxWith(compareBy<SetEntry>({ it.timeMs }, { it.id }))
            val loose = group.filter { it.runId == null }
            val hold = group.any { it.measurement == Measurement.HOLD }
            val repSets = group.filter { it.measurement != Measurement.HOLD }
            val holdSets = group.filter { it.measurement == Measurement.HOLD }
            val bestRepSet = loose.filter { it.measurement != Measurement.HOLD }.minWithOrNull(
                compareByDescending<SetEntry> { it.reps }
                    .thenByDescending { it.weightKg ?: 0 }
                    .thenBy { it.timeMs }
                    .thenBy { it.id },
            )
            ExerciseRecord(
                exerciseId = id,
                exerciseName = latest.exerciseName,
                bestReps = bestRepSet?.reps,
                bestWeightKg = bestRepSet?.weightKg,
                totalReps = repSets.sumOf { it.reps.toLong() },
                sets = group.size,
                lastDay = group.maxOf { it.day },
                measurement = if (hold) Measurement.HOLD else Measurement.REPS,
                bestHeldMs = loose.filter { it.measurement == Measurement.HOLD }.maxOfOrNull { it.heldMs },
                totalHeldMs = holdSets.sumOf { it.heldMs },
            )
        }

    /** (круги, повторения сверх) лучше прежнего; без прежнего — любой результат лучше. */
    fun isBetterRounds(result: Pair<Int, Int>, best: Pair<Int, Int>?): Boolean =
        best == null || result.first > best.first ||
            (result.first == best.first && result.second > best.second)

    sealed interface Legacy
    data class LegacySet(val exercise: Exercise, val weightKg: Int?, val reps: Int) : Legacy
    data class LegacyMinotaur(val weightKg: Int, val durationMs: Long) : Legacy

    private val minotaurTitle = Regex("""^Минотавр (\d+) кг · (\d+):(\d{2})$""")
    private val setTitle = Regex("""^(.+?)(?: (\d+) кг)? ×(\d+)$""")

    /** Разбирает заголовки, которые писали старые экраны через Store.recordWorkout. */
    fun parseLegacy(title: String): Legacy? {
        val t = title.trim()
        minotaurTitle.matchEntire(t)?.let { m ->
            val weight = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toLongOrNull() ?: return null
            val sec = m.groupValues[3].toLong()
            return LegacyMinotaur(weight, (min * 60 + sec) * 1000)
        }
        setTitle.matchEntire(t)?.let { m ->
            // Удержаний (планки) в старой истории не было: «×N» — всегда повторения.
            val exercise = Exercises.all.firstOrNull { it.name == m.groupValues[1] && !it.hold } ?: return null
            val reps = m.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val weight = when {
                !exercise.kettlebell -> null
                // Без веса в заголовке гиревое упражнение делалось с гирей по умолчанию.
                else -> m.groupValues[2].toIntOrNull() ?: Weights.DEFAULT
            }
            return LegacySet(exercise, weight, reps)
        }
        return null
    }
}
