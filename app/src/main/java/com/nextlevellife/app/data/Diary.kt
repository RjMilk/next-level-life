package com.nextlevellife.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.ZoneId

/** Один подход одного упражнения. [runId] — прохождение комплекса, в которое входит подход. */
data class SetEntry(
    val id: Long, val timeMs: Long, val day: Long,
    val exerciseId: String, val exerciseName: String, val weightKg: Int?,
    val reps: Int, val durationMs: Long, val earnedMs: Long, val xp: Long, val runId: Long?,
)

/** Одно прохождение комплекса (Минотавр, Синди). */
data class ComplexRun(
    val id: Long, val timeMs: Long, val day: Long, val complexId: String, val weightKg: Int?,
    val durationMs: Long, val rounds: Int?, val extraReps: Int?, val totalReps: Int,
    val earnedMs: Long, val xp: Long, val completed: Boolean,
)

/** Итог дня для календаря и списка дней. */
data class DaySummary(val day: Long, val sets: Int, val reps: Int, val earnedMs: Long, val xp: Long)

/** Сколько сделано по одному упражнению (и весу) за день. */
data class ExerciseTotal(val exerciseId: String, val exerciseName: String, val weightKg: Int?, val sets: Int, val reps: Int)

/**
 * Рекорды упражнения за всё время. [bestReps] и [bestWeightKg] — лучший отдельный подход
 * (null — упражнение делалось только в комплексах), [totalReps] и [sets] — всё вместе с комплексами.
 */
data class ExerciseRecord(
    val exerciseId: String, val exerciseName: String,
    val bestReps: Int?, val bestWeightKg: Int?,
    val totalReps: Long, val sets: Int, val lastDay: Long,
)

/** Подход, который ещё не записан: время, день и прохождение проставляет [Diary]. */
data class NewSet(
    val exerciseId: String, val exerciseName: String, val weightKg: Int?,
    val reps: Int, val durationMs: Long, val earnedMs: Long, val xp: Long,
)

/**
 * Дневник тренировок в SQLite (diary.db). Хранится бессрочно.
 * Писать сюда стоит только через [Workouts], чтобы дневник и баланс не расходились.
 */
object Diary {
    private var helper: Helper? = null

    @Synchronized
    fun init(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
    }

    private fun db(): SQLiteDatabase =
        (helper ?: throw IllegalStateException("Diary.init не вызван")).writableDatabase

    // ---------- Чтение ----------

    @Synchronized
    fun setsForDay(day: Long): List<SetEntry> =
        query("SELECT $SET_COLS FROM sets WHERE day = ? ORDER BY time_ms, id", day.toString()) { it.toSetEntry() }

    @Synchronized
    fun runsForDay(day: Long): List<ComplexRun> =
        query("SELECT $RUN_COLS FROM complex_runs WHERE day = ? ORDER BY time_ms, id", day.toString()) { it.toRun() }

    @Synchronized
    fun exerciseTotalsForDay(day: Long): List<ExerciseTotal> = DiaryMath.totals(setsForDay(day))

    @Synchronized
    fun daySummaries(fromDay: Long, toDay: Long): List<DaySummary> = query(
        "SELECT day, COUNT(*), SUM(reps), SUM(earned_ms), SUM(xp) FROM sets " +
            "WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day",
        fromDay.toString(), toDay.toString(),
    ) { DaySummary(it.getLong(0), it.getInt(1), it.getInt(2), it.getLong(3), it.getLong(4)) }

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

    /** Есть ли в дневнике хоть одна запись. */
    @Synchronized
    fun hasEntries(): Boolean = query(
        "SELECT EXISTS(SELECT 1 FROM sets) OR EXISTS(SELECT 1 FROM complex_runs)",
    ) { it.getInt(0) != 0 }.firstOrNull() ?: false

    /**
     * Рекорды по всем упражнениям, которые встречались в дневнике, одним запросом.
     * Лучший подход ищется только среди отдельных подходов: подходы комплексов — это сумма
     * за весь комплекс, а не один подход. Всего повторений — с комплексами.
     */
    @Synchronized
    fun exerciseRecords(): List<ExerciseRecord> = query(
        "SELECT s.exercise_id, " +
            "(SELECT n.exercise_name FROM sets n WHERE n.exercise_id = s.exercise_id " +
            "ORDER BY n.time_ms DESC, n.id DESC LIMIT 1), " +
            "MAX(CASE WHEN s.run_id IS NULL THEN s.reps END), " +
            "(SELECT b.weight_kg FROM sets b WHERE b.exercise_id = s.exercise_id AND b.run_id IS NULL " +
            "ORDER BY b.reps DESC, COALESCE(b.weight_kg, 0) DESC, b.time_ms, b.id LIMIT 1), " +
            "SUM(s.reps), COUNT(*), MAX(s.day) " +
            "FROM sets s GROUP BY s.exercise_id",
    ) {
        ExerciseRecord(
            exerciseId = it.getString(0), exerciseName = it.getString(1),
            bestReps = it.intOrNull(2), bestWeightKg = it.intOrNull(3),
            totalReps = it.getLong(4), sets = it.getInt(5), lastDay = it.getLong(6),
        )
    }

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
    ): Long {
        val db = db()
        db.beginTransaction()
        try {
            val id = insertRun(db, timeMs, complexId, weightKg, durationMs, rounds, extraReps, totalReps, earnedMs, xp, completed, sets)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Переносит записи из старой JSON-истории Store. Нераспознанные заголовки пропускаются:
     * упражнение по ним не восстановить. Возвращает число перенесённых записей.
     */
    @Synchronized
    internal fun importLegacy(entries: List<Store.HistoryEntry>): Int {
        val db = db()
        var count = 0
        db.beginTransaction()
        try {
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
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
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
        }
        return db.insertOrThrow("sets", null, values)
    }

    private inline fun <T> query(sql: String, vararg args: String, read: (Cursor) -> T): List<T> =
        db().rawQuery(sql, args).use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out.add(read(c))
            out
        }

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private const val SET_COLS =
        "id, time_ms, day, exercise_id, exercise_name, weight_kg, reps, duration_ms, earned_ms, xp, run_id"
    private const val RUN_COLS =
        "id, time_ms, day, complex_id, weight_kg, duration_ms, rounds, extra_reps, total_reps, earned_ms, xp, completed"

    private fun Cursor.toSetEntry() = SetEntry(
        id = getLong(0), timeMs = getLong(1), day = getLong(2),
        exerciseId = getString(3), exerciseName = getString(4), weightKg = intOrNull(5),
        reps = getInt(6), durationMs = getLong(7), earnedMs = getLong(8), xp = getLong(9),
        runId = longOrNull(10),
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

    private const val NAME = "diary.db"

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
            ExerciseTotal(
                exerciseId = key.first,
                exerciseName = group.last().exerciseName,
                weightKg = key.second,
                sets = group.size,
                reps = group.sumOf { it.reps },
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
            val exercise = Exercises.all.firstOrNull { it.name == m.groupValues[1] } ?: return null
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
