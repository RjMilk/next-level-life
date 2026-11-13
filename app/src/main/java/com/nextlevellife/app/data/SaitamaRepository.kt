package com.nextlevellife.app.data

import android.util.Log
import com.nextlevellife.app.health.ActivitySync
import com.nextlevellife.app.health.HealthAccess

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

    /**
     * Доплатить +10 минут за полные дни из последних [Saitama.CHECK_DAYS] (не раньше `saitama_since`), за которые
     * ещё нет операции журнала с ключом `saitama:<день>`. Повторный вызов ничего не платит. Возвращает, сколько
     * начислено сейчас (мс). Ошибки базы не пробрасываются (лог, 0) — вызов безопасен с любого фонового места:
     * при открытии приложения, экрана «Сайтамы», после синхронизации активности.
     */
    @Synchronized
    fun ensureBonuses(): Long = try {
        val time = TrustedClock.snapshot()
        val today = time.day
        val since = sinceDay(today)
        val from = maxOf(since, today - Saitama.CHECK_DAYS + 1)
        if (from > today) {
            0L
        } else {
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
            paid
        }
    } catch (e: Exception) {
        Log.e(TAG, "Не удалось доплатить бонус «Сайтамы»", e)
        0L
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
        val reason = SaitamaMath.runUnknownReason(
            hasRow = row != null, runsCount = row?.runsCount ?: 0,
            availability = access?.availability, canReadRuns = access?.canReadRuns,
        )
        val todayDay = days.getValue(today).copy(runM = if (reason == null) row?.runDistanceM ?: 0L else null)
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
     * Новые вехи сегодняшнего дня [state] (ещё не показанные «нарезкой») — и сразу отметить их показанными
     * в meta `saitama_shown:<день>`: пропущенная тапом «нарезка» второй раз не всплывает.
     */
    @Synchronized
    fun takeNewMilestones(state: SaitamaState): List<SaitamaMilestone> = try {
        val key = Saitama.shownKey(state.today)
        val shown = SaitamaMath.decodeShown(Diary.metaGet(key))
        val fresh = SaitamaMath.newMilestones(state.day.reached, shown)
        if (fresh.isNotEmpty()) {
            Diary.write {
                Diary.metaPut(key, SaitamaMath.encodeShown(shown + fresh))
                // Старые отметки больше не нужны.
                for (old in Diary.metaWithPrefix(Saitama.META_SHOWN_PREFIX).keys) {
                    val d = old.removePrefix(Saitama.META_SHOWN_PREFIX).toLongOrNull() ?: continue
                    if (d < state.today - SHOWN_KEEP_DAYS) Diary.metaDelete(old)
                }
            }
        }
        fresh
    } catch (e: Exception) {
        Log.e(TAG, "Не удалось прочитать или записать показанные вехи", e)
        emptyList()
    }

    /** Учётный день первого расчёта «Сайтамы» (meta `saitama_since`); нет — ставится [today], один раз. */
    private fun sinceDay(today: Long): Long {
        Diary.metaGet(Saitama.META_SINCE)?.toLongOrNull()?.let { return it }
        return try {
            Diary.write {
                Diary.metaGet(Saitama.META_SINCE)?.toLongOrNull()
                    ?: today.also { Diary.metaPut(Saitama.META_SINCE, it.toString()) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать начало «Сайтамы»", e)
            today
        }
    }
}
