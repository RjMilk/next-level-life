package com.nextlevellife.app.health

import android.content.Context
import android.util.Log
import com.nextlevellife.app.data.ActivityDay
import com.nextlevellife.app.data.ActivityRun
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.OpKind
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TrustedClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Состояние синхронизации для экранов. */
data class ActivitySyncState(
    /** null — ещё не проверяли. */
    val access: HealthAccess? = null,
    val running: Boolean = false,
    /** Последнее успешное чтение (мс эпохи). */
    val lastSyncMs: Long? = null,
    /** Ошибка последней попытки, понятная человеку; null — всё в порядке. */
    val error: String? = null,
) {
    val availability: HealthAvailability? get() = access?.availability
    val connected: Boolean get() = availability == HealthAvailability.CONNECTED
}

/**
 * Синхронизация шагов и пробежек: Health Connect → таблицы activity_* в дневнике → начисление
 * в журнал баланса. Запускается при возврате в приложение, по кнопке «Обновить» и при открытии
 * экрана блокировки; автоматически — не чаще раза в [ActivityMath.SYNC_INTERVAL_MS].
 * Фоновых задач нет: работает собственная область корутин, пока жив процесс.
 *
 * Начисление идемпотентно и только «вверх»: за день считается положенное (due); если оно больше
 * уже начисленного (rewarded_ms), одной транзакцией пишется операция STEPS_REWARD с ключом
 * `steps:v2:<day>:<due>` на разницу и обновляется rewarded_ms. Уменьшение данных ничего не списывает.
 * Сутки — учётные ([TrustedClock]): доверенное время и учётный пояс, а не часы и пояс телефона.
 * После смены учётного пояса сутки по день смены включительно не оплачиваются
 * ([TrustedClock.stepsFrozenThroughDay]): иначе оплаченные шаги, сдвинувшись в соседние сутки, оплатились бы снова.
 */
object ActivitySync {
    private const val TAG = "ActivitySync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Последняя попытка чтения в этом процессе (успешная или нет) — для ограничения частоты. */
    @Volatile private var lastAttemptMs: Long? = null

    private val _state = MutableStateFlow(ActivitySyncState())
    val state: StateFlow<ActivitySyncState> = _state

    /**
     * Проверить доступ и, если пора (или [force]), прочитать Health Connect.
     * Безопасно вызывать часто: проверка доступа дешёвая, чтение ограничено по частоте,
     * параллельный запуск пропускается.
     */
    fun request(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                syncOnce(app, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Синхронизация не удалась", e)
                _state.update { it.copy(error = errorText(e)) }
                // Разрешение могли отозвать прямо сейчас: обновим доступ, чтобы экраны это показали.
                if (e is SecurityException) _state.update { it.copy(access = HealthConnect.access(app)) }
            } finally {
                _state.update { it.copy(running = false) }
                mutex.unlock()
            }
        }
    }

    private suspend fun syncOnce(context: Context, force: Boolean) {
        Store.init(context)
        if (_state.value.lastSyncMs == null) {
            _state.update { it.copy(lastSyncMs = runCatching { Settings.activityLastSyncMs }.getOrNull()) }
        }
        val access = HealthConnect.access(context)
        _state.update { it.copy(access = access) }
        if (access.availability != HealthAvailability.CONNECTED) {
            _state.update { it.copy(error = null) }
            return
        }

        // Доверенное время и учётный пояс: перевод часов и смена пояса телефона не сдвигают сутки начисления.
        val time = TrustedClock.snapshot()
        val now = time.utcMs
        val last = listOfNotNull(lastAttemptMs, _state.value.lastSyncMs).maxOrNull()
        if (!ActivityMath.shouldSync(last, now, force)) return
        lastAttemptMs = now
        _state.update { it.copy(running = true) }

        val zone = time.zone
        val today = time.day
        val days = ActivityMath.syncDays(today)
        val snapshot = HealthConnect.read(context, days, zone, now, readRuns = access.canReadRuns)
        // Уже записанная пробежка сохраняет свой день: смена учётного пояса не оплатит её второй раз.
        val knownDays = withContext(Dispatchers.IO) { Diary.activityRunDays(snapshot.runs.map { it.id }) }
        val runDays = ActivityMath.assignRunDays(snapshot.runs, knownDays, zone)
        val totals = ActivityMath.buildDays(days, snapshot.stepsByDay, snapshot.sourcesByDay, snapshot.runs, zone, runDays)
        // Хранятся только пробежки своего дня в окне; начатые накануне окна нужны лишь для шагов в buildDays.
        val runs = snapshot.runs.filter { runDays.getValue(it.id) in days.first()..days.last() }.map { r ->
            ActivityRun(
                externalId = r.id, startMs = r.startMs, endMs = r.endMs, day = runDays.getValue(r.id),
                distanceM = r.distanceM, steps = r.steps, source = r.source, exerciseType = r.exerciseType,
            )
        }

        val changed = withContext(Dispatchers.IO) { store(days, totals, runs, now, zone, runsRead = access.canReadRuns) }
        Settings.setActivityLastSyncMs(now)
        _state.update { it.copy(lastSyncMs = now, error = null) }
        if (changed) Store.notifyChanged()
    }

    /**
     * Пишет итоги, пробежки и начисления одной транзакцией. Возвращает true, если что-то
     * изменилось (данные дня, пробежки или начислено). [runsRead] — тренировки и дистанция читались в этот раз
     * (выдано разрешение): пишется в строку дня, только тогда «0 пробежек» — подтверждённый ноль.
     */
    private fun store(
        days: List<Long>, totals: List<DayTotals>, runs: List<ActivityRun>, now: Long, zone: ZoneId, runsRead: Boolean,
    ): Boolean {
        val enabled = Settings.stepsRewardEnabled
        val since = Settings.stepsRewardSinceDay
        val from = days.first()
        val to = days.last()
        return Diary.write { db ->
            // После смены учётного пояса сутки по день смены не оплачиваются (только показываются).
            val frozenThrough = TrustedClock.stepsFrozenThroughDay()
            val oldDays = Diary.activityDays(from, to).associateBy { it.day }
            val oldRuns = Diary.activityRuns(from, to)
            var changed = oldRuns != runs.sortedWith(compareBy({ it.startMs }, { it.externalId }))
            Diary.replaceActivityRuns(db, from, to, runs)

            for (t in totals) {
                val old = oldDays[t.day]
                val rewarded = old?.rewardedMs ?: 0L
                val due = ActivityMath.due(t)
                val delta = ActivityMath.rewardDelta(due.totalMs, rewarded, enabled, t.day, since, frozenThrough)
                var newRewarded = rewarded
                if (delta > 0) {
                    val walkSteps = ActivityMath.walkSteps(t.steps, t.runSteps)
                    Ledger.reward(
                        kind = OpKind.STEPS_REWARD,
                        idempotencyKey = ActivityMath.rewardKey(t.day, due.totalMs),
                        deltaMs = delta,
                        // С 0.6 опыт за минуты не начисляется: шаги и бег идут в «Выносливость» (stats/).
                        deltaXp = 0L,
                        sourceType = "activity_day",
                        sourceId = t.day.toString(),
                        occurredAtMs = ActivityMath.occurredAtMs(t.day, now, zone),
                        metadata = mapOf(
                            "day" to t.day,
                            "steps" to t.steps,
                            "walk_steps" to walkSteps,
                            "run_m" to t.runDistanceM,
                            "run_steps" to t.runSteps,
                            "walk_ms" to due.walkMs,
                            "run_ms" to due.runMs,
                            "due_ms" to due.totalMs,
                            "previous_rewarded_ms" to rewarded,
                        ),
                    )
                    // Ключ уже был (не должно случаться: он пишется вместе с rewarded_ms) — значит, оплачено до due.
                    newRewarded = due.totalMs
                    changed = true
                }
                val row = ActivityDay(
                    day = t.day, steps = t.steps, runDistanceM = t.runDistanceM, runDurationMs = t.runDurationMs,
                    runSteps = t.runSteps, runsCount = t.runsCount, sources = t.sources,
                    rewardedMs = newRewarded, updatedAtMs = now, runsRead = runsRead,
                )
                if (old == null || old.copy(updatedAtMs = now) != row) changed = true
                Diary.upsertActivityDay(db, row)
            }
            changed
        }
    }

    private fun errorText(e: Exception): String = when (e) {
        is SecurityException -> "Нет разрешения на чтение Health Connect"
        is IllegalStateException -> "Health Connect недоступен"
        else -> "Не удалось прочитать Health Connect: ${e.message ?: e.javaClass.simpleName}"
    }
}
