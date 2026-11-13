package com.nextlevellife.app.sleep

import android.util.Log
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.TrustedClock
import java.time.ZoneId

/**
 * Расчёт ночных перерывов по записанным событиям экрана ([PhoneEvents]) и чтение итогов.
 * Ночь с днём пробуждения D окончательна после 12:00 дня D: тогда она один раз считается и пишется
 * в sleep_nights. До этого она считается на лету и в базу не попадает.
 *
 * Расчёт и запись итогов идут только на потоке записи [PhoneEvents] (барьер: всё, что поставлено
 * в очередь раньше, уже записано), а данные каждой ночи читаются одной транзакцией ([Diary.nightData]).
 * Окончательные ночи дописывает и сырые данные чистит служба раз в час ([maintainOnWriter]); интерфейс
 * только досчитывает при открытии ([nights]). Учитывается только этот телефон (device_id).
 */
object SleepRepository {
    private const val TAG = "SleepRepository"

    /** Сколько интерфейс ждёт очередь записи (она может стоять на паузе восстановления копии). */
    private const val WAIT_MS = 20_000L

    private class Update(val provisional: Map<Long, SleepNight>, val firstUnsavedDay: Long?, val nowMs: Long, val zone: ZoneId)

    /**
     * Досчитывает и записывает окончательные ночи, которых ещё нет (или они посчитаны старым правилом),
     * и возвращает предварительные ночи (ещё не окончательные): день пробуждения → ночь. Пока служба
     * не наблюдает ([PhoneEvents.observing]), окончательные ночи не пишутся — служба может подключиться
     * через секунду и записать настоящий пропуск. С фонового потока (не главного).
     */
    fun refresh(): Map<Long, SleepNight> = PhoneEvents.onWriter(WAIT_MS) { update().provisional }

    /**
     * Часовое обслуживание (поток записи): сначала финализация ночей, потом чистка сырых данных старше
     * 90 дней — но никогда не раньше первой ночи, итог которой ещё не записан.
     */
    internal fun maintainOnWriter() {
        val u = update()
        val firstUnsaved = u.firstUnsavedDay ?: return // событий нет — чистить нечего
        Diary.prunePhoneData(SleepMath.pruneCutoffMs(u.nowMs, u.zone, firstUnsaved))
    }

    /** Поток записи. */
    private fun update(): Update {
        val snap = TrustedClock.snapshot()
        val now = snap.utcMs
        val zone = snap.zone
        val today = snap.day
        val device = Ledger.deviceId()
        val first = Diary.firstPhoneEventMs(device) ?: return Update(emptyMap(), null, now, zone)
        val observing = PhoneEvents.observing
        val mode = nightMode()

        val lastFinal = SleepMath.lastFinalWakeDay(now, zone)
        val from = SleepMath.firstWakeDay(first, zone)
        val provisional = HashMap<Long, SleepNight>()
        var firstUnsaved: Long? = null
        if (from <= lastFinal) {
            val stored = Diary.sleepNightsOf(device, from, lastFinal).associate { it.wakeDay to it.ruleVersion }
            for (day in SleepMath.nightsToFinalize(from, lastFinal, stored, today)) {
                val night = compute(device, day, zone, now, mode, observing)
                if (observing) {
                    Diary.upsertSleepNight(device, night)
                } else {
                    provisional[day] = night.copy(final = false)
                    if (firstUnsaved == null) firstUnsaved = day
                }
            }
        }
        for (day in maxOf(from, lastFinal + 1)..today) {
            provisional[day] = compute(device, day, zone, now, mode, observing)
        }
        return Update(provisional, firstUnsaved ?: maxOf(from, lastFinal + 1), now, zone)
    }

    /**
     * Ночи с днями пробуждения [fromDay]..[toDay]: окончательные из базы и предварительные поверх.
     * Ошибка базы — пустой ответ (в интерфейсе — «нет данных»).
     */
    fun nights(fromDay: Long, toDay: Long): Map<Long, SleepNight> = try {
        val provisional = refresh()
        val out = Diary.sleepNights(fromDay, toDay).associateBy { it.wakeDay }.toMutableMap()
        for ((day, n) in provisional) if (day in fromDay..toDay) out[day] = n
        out
    } catch (e: Exception) {
        Log.e(TAG, "Не удалось посчитать ночные перерывы", e)
        emptyMap()
    }

    /** Ночь с днём пробуждения [day] или null. */
    fun night(day: Long): SleepNight? = nights(day, day)[day]

    private fun compute(device: String, day: Long, zone: ZoneId, now: Long, mode: NightMode?, observing: Boolean): SleepNight {
        val (from, to) = SleepMath.dataRange(day, zone, mode)
        val data = Diary.nightData(device, from, to)
        return SleepMath.computeFromData(day, zone, data, now, mode, observing)
    }

    /** Окно ночного режима сейчас; выключен или начало == конец — null. */
    private fun nightMode(): NightMode? {
        if (!Settings.nightEnabled) return null
        val s = Settings.nightStartMin
        val e = Settings.nightEndMin
        return if (s == e) null else NightMode(s, e)
    }
}
