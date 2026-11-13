package com.nextlevellife.app.sleep

import android.util.Log
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.TrustedClock

/**
 * Расчёт ночных перерывов по записанным событиям экрана ([PhoneEvents]) и чтение итогов.
 * Ночь с днём пробуждения D окончательна после 12:00 дня D: тогда она один раз считается и пишется
 * в sleep_nights. До этого она считается на лету при каждом открытии и в базу не попадает.
 * Учитывается только этот телефон (device_id). Все функции — с фонового потока.
 */
object SleepRepository {
    private const val TAG = "SleepRepository"
    private const val DAY_MS = 24 * 60 * 60_000L

    /** Сырые данные чистятся раз в учётные сутки (за процесс). */
    private var lastPruneDay: Long? = null

    /**
     * Досчитывает и записывает окончательные ночи, которых ещё нет (или они посчитаны старым правилом),
     * чистит сырые события старше 90 дней и возвращает предварительные ночи (ещё не окончательные):
     * день пробуждения → ночь. Пока служба не наблюдает ([PhoneEvents.observing]), окончательные ночи
     * не пишутся — служба может подключиться через секунду и записать настоящий пропуск.
     */
    @Synchronized
    fun refresh(): Map<Long, SleepNight> {
        val snap = TrustedClock.snapshot()
        val now = snap.utcMs
        val zone = snap.zone
        val device = Ledger.deviceId()
        if (lastPruneDay != snap.day) {
            Diary.prunePhoneData(now - SleepMath.RAW_RETENTION_DAYS * DAY_MS)
            lastPruneDay = snap.day
        }
        val first = Diary.firstPhoneEventMs(device) ?: return emptyMap()
        val observing = PhoneEvents.observing
        val mode = nightMode()

        val today = snap.day
        val lastFinal = SleepMath.lastFinalWakeDay(now, zone)
        // Сырые события живут 90 дней: ночи раньше этого не пересчитать.
        val from = maxOf(SleepMath.firstWakeDay(first, zone), today - SleepMath.RAW_RETENTION_DAYS + 2)
        val provisional = HashMap<Long, SleepNight>()
        if (from <= lastFinal) {
            val stored = Diary.sleepNightsOf(device, from, lastFinal).associateBy { it.wakeDay }
            for (day in from..lastFinal) {
                if (stored[day]?.ruleVersion == SleepMath.RULE_VERSION) continue
                val night = compute(device, day, zone, now, mode, observing)
                if (observing) Diary.upsertSleepNight(device, night) else provisional[day] = night.copy(final = false)
            }
        }
        for (day in maxOf(from, lastFinal + 1)..today) {
            provisional[day] = compute(device, day, zone, now, mode, observing)
        }
        return provisional
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

    private fun compute(device: String, day: Long, zone: java.time.ZoneId, now: Long, mode: NightMode?, observing: Boolean): SleepNight {
        val windowStart = SleepMath.windowStartMs(day, zone)
        val windowEnd = SleepMath.windowEndMs(day, zone)
        val modeInterval = mode?.let { SleepMath.nightModeInterval(day, zone, it) }
        val from = minOf(windowStart, modeInterval?.first ?: windowStart)
        val to = maxOf(windowEnd, modeInterval?.second ?: windowEnd)
        val seed = Diary.lastPhoneEvent(device, beforeMs = from)
        val events = Diary.phoneEvents(device, from, to)
        val gaps = Diary.observationGaps(device, from, to).toMutableList()
        if (!observing) {
            // Служба сейчас не работает: после последнего события ничего не известно.
            val last = Diary.lastPhoneEvent(device)?.utcMs ?: from
            if (now > last) gaps += ObservationGap(last, now)
        }
        return SleepMath.computeNight(day, zone, seed, events, gaps, now, mode)
    }

    /** Окно ночного режима сейчас; выключен или начало == конец — null. */
    private fun nightMode(): NightMode? {
        if (!Settings.nightEnabled) return null
        val s = Settings.nightStartMin
        val e = Settings.nightEndMin
        return if (s == e) null else NightMode(s, e)
    }
}
