package com.nextlevellife.app.data

import android.util.Log
import com.nextlevellife.app.health.ActivitySync
import com.nextlevellife.app.health.HealthAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Состояние «Сайтамы» для экрана и карточки. [day] — сегодняшний прогресс ([SaitamaDay.runM] = null —
 * бег не подтверждён, причина в [runReason]); [streak] — серия (сегодня в ней, только если уже выполнен),
 * [record] — рекорд серии; [bonusPaidToday] — +10 минут за сегодня уже в журнале;
 * [marks] — последние [Saitama.CHECK_DAYS] дней по возрастанию (день → отметка).
 */
data class SaitamaState(
    val today: Long,
    val since: Long,
    val day: SaitamaDay,
    val runReason: String?,
    val streak: Int,
    val record: Int,
    val bonusPaidToday: Boolean,
    val marks: List<Pair<Long, SaitamaMark>>,
)

/**
 * Чтение «Сайтамы» из дневника и meta и доплата бонуса за полные дни. Только с фонового потока.
 * Отдельного счётчика серии нет: всё считается из подходов и строк активности ([SaitamaMath]).
 */
object SaitamaRepository {
    private const val TAG = "Saitama"

    /** Показанные вехи старше этого числа дней удаляются из meta. */
    private const val SHOWN_KEEP_DAYS = 30

    /** Сколько раз проверить бонус заново, если базу подменили во время проверки. */
    private const val MAX_ATTEMPTS = 3

    /** Отметка показанных вех не должна теряться, если экран ушёл раньше, чем она записалась. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Доплатить +10 минут за полные дни из последних [Saitama.CHECK_DAYS] (не раньше `saitama_since`), за которые
     * ещё нет операции журнала с ключом `saitama:<день>`. Повторный вызов ничего не платит. Возвращает, сколько
     * начислено сейчас (мс). Ошибки базы не пробрасываются (лог, 0) — вызов безопасен с любого фонового места:
     * при открытии приложения, экрана «Сайтамы», после синхронизации активности.
     *
     * Проверка полноты дней и начисление — одна транзакция [Diary.write] под блокировкой Diary: восстановление
     * копии не может подменить базу между ними (бонус не попадёт в базу, где день не выполнен). Поколение базы
     * ([Diary.generation]) запоминается до транзакции и сверяется внутри: подменили раньше — расчёт повторяется
     * (не больше [MAX_ATTEMPTS] раз).
     */
    @Synchronized
    fun ensureBonuses(): Long = try {
        SaitamaMath.untilSameDatabase(MAX_ATTEMPTS) {
            val gen = Diary.generation
            Diary.write {
                if (Diary.generation != gen) null else payDue(TrustedClock.snapshot())
            }
        } ?: run {
            Log.w(TAG, "База подменялась во время каждой проверки бонуса «Сайтамы»")
            0L
        }
    } catch (e: Exception) {
        Log.e(TAG, "Не удалось доплатить бонус «Сайтамы»", e)
        0L
    }

    /**
     * Чтение дней и начисления — только внутри уже открытой транзакции [Diary.write] (вложенные write и
     * [Ledger.reward] входят в неё же). Ошибки пробрасываются: проглоченная ошибка вложенной транзакции молча
     * откатила бы внешнюю.
     */
    private fun payDue(time: TimeSnapshot): Long {
        val today = time.day
        val since = sinceDay(today, swallowErrors = false)
        val from = maxOf(since, today - Saitama.CHECK_DAYS + 1)
        if (from > today) return 0L
        val days = SaitamaMath.days(from, today, Diary.setsInRange(from, today), Diary.activityDays(from, today))
        var paid = 0L
        for (d in SaitamaMath.bonusDays(days, since, today)) {
            val key = Saitama.bonusKey(d)
            if (Ledger.opByKey(key) != null) continue
            val p = days.getValue(d)
            val inserted = Ledger.reward(
                kind = OpKind.COMPLEX_REWARD,
                idempotencyKey = key,
                deltaMs = Saitama.BONUS_MS,
                deltaXp = 0L, // опыт за минуты не начисляется с 0.6
                sourceType = Saitama.SOURCE_TYPE,
                sourceId = d.toString(),
                // Прошлый день — его последняя миллисекунда: бонус ложится в свои учётные сутки.
                occurredAtMs = minOf(time.utcMs, TrustedClockMath.dayStartMs(d + 1, time.zone) - 1),
                metadata = mapOf(
                    "complex_id" to Saitama.ID,
                    "day" to d,
                    "pushups" to p.repsOf(SaitamaGoal.PUSHUPS),
                    "abs" to p.repsOf(SaitamaGoal.ABS),
                    "squats" to p.repsOf(SaitamaGoal.SQUATS),
                    "run_m" to p.runM,
                ),
                zone = time.zone,
            )
            if (inserted) paid += Saitama.BONUS_MS
        }
        return paid
    }

    /**
     * Состояние для экрана и карточки. [access] — доступ к Health Connect (по умолчанию — последний
     * известный из [ActivitySync]): от него зависит, подтверждён ли сегодняшний бег.
     */
    fun load(access: HealthAccess? = ActivitySync.state.value.access): SaitamaState {
        val today = TrustedClock.today()
        val since = sinceDay(today)
        val stripFrom = today - Saitama.CHECK_DAYS + 1
        val from = minOf(since, stripFrom)
        val days = SaitamaMath.days(from, today, Diary.setsInRange(from, today), Diary.activityDays(from, today)).toMutableMap()

        val row = Diary.activityDay(today)
        val reason = SaitamaMath.runUnknownReason(row, access?.availability, access?.canReadRuns)
        val todayDay = days.getValue(today).copy(runM = if (reason == null) SaitamaMath.runMeters(row) ?: 0L else null)
        days[today] = todayDay

        val full = days.values.filter { it.full && it.day >= since }.map { it.day }.toSet()
        val bonusPaid = todayDay.full && today >= since && Ledger.opByKey(Saitama.bonusKey(today)) != null
        return SaitamaState(
            today = today,
            since = since,
            day = todayDay,
            runReason = reason,
            streak = SaitamaMath.streak(full, since, today),
            record = SaitamaMath.record(full, since, today),
            bonusPaidToday = bonusPaid,
            marks = (stripFrom..today).map { d -> d to SaitamaMath.mark(d, days[d], since, today) },
        )
    }

    /**
     * Вехи сегодняшнего дня [state], ещё не показанные «нарезкой» (по meta `saitama_shown:<день>`). Ничего не пишет:
     * показанной веха становится только после воспроизведения или явного пропуска ([markShownAsync]). Так «нарезка»,
     * прерванная поворотом экрана или раскрытием Fold, после пересоздания экрана покажется снова.
     */
    fun unshownMilestones(state: SaitamaState): List<SaitamaMilestone> = try {
        SaitamaMath.newMilestones(state.day.reached, SaitamaMath.decodeShown(Diary.metaGet(Saitama.shownKey(state.today))))
    } catch (e: Exception) {
        Log.e(TAG, "Не удалось прочитать показанные вехи", e)
        emptyList()
    }

    /**
     * Отметить вехи [milestones] дня [day] показанными (досмотрены или пропущены). Пишется в своей области корутин,
     * а не в области экрана: уход с экрана сразу после «нарезки» отметку не отменит.
     */
    fun markShownAsync(day: Long, milestones: Collection<SaitamaMilestone>) {
        if (milestones.isEmpty()) return
        scope.launch { markShown(day, milestones) }
    }

    @Synchronized
    private fun markShown(day: Long, milestones: Collection<SaitamaMilestone>) {
        try {
            Diary.write {
                val key = Saitama.shownKey(day)
                val shown = SaitamaMath.decodeShown(Diary.metaGet(key))
                Diary.metaPut(key, SaitamaMath.encodeShown(shown + milestones))
                // Старые отметки больше не нужны.
                for (old in Diary.metaWithPrefix(Saitama.META_SHOWN_PREFIX).keys) {
                    val d = old.removePrefix(Saitama.META_SHOWN_PREFIX).toLongOrNull() ?: continue
                    if (d < day - SHOWN_KEEP_DAYS) Diary.metaDelete(old)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать показанные вехи", e)
        }
    }

    /**
     * Учётный день первого расчёта «Сайтамы» (meta `saitama_since`); нет — ставится [today], один раз.
     * [swallowErrors] = false — внутри чужой транзакции: ошибка пробрасывается, а не глотается.
     */
    private fun sinceDay(today: Long, swallowErrors: Boolean = true): Long {
        Diary.metaGet(Saitama.META_SINCE)?.toLongOrNull()?.let { return it }
        return try {
            Diary.write {
                Diary.metaGet(Saitama.META_SINCE)?.toLongOrNull()
                    ?: today.also { Diary.metaPut(Saitama.META_SINCE, it.toString()) }
            }
        } catch (e: Exception) {
            if (!swallowErrors) throw e
            Log.e(TAG, "Не удалось записать начало «Сайтамы»", e)
            today
        }
    }
}
