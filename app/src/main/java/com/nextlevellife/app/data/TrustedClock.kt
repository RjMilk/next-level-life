package com.nextlevellife.app.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.util.Log
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Снимок времени для правил и журнала: доверенный момент [utcMs] (мс эпохи) и учётный пояс [zone].
 * Сутки, минута суток и ночь считаются только из него — системные часы телефона здесь не участвуют.
 */
data class TimeSnapshot(val utcMs: Long, val zone: ZoneId) {
    val local: ZonedDateTime get() = Instant.ofEpochMilli(utcMs).atZone(zone)

    /** Учётные сутки (LocalDate.toEpochDay в учётном поясе). */
    val day: Long get() = local.toLocalDate().toEpochDay()

    /** Минута суток в учётном поясе: 0…1439. */
    val minuteOfDay: Int get() = local.let { it.hour * 60 + it.minute }
}

/**
 * Якорь доверенных часов: в загрузке устройства [bootCount] моменту [anchorElapsedMs] монотонных часов
 * (SystemClock.elapsedRealtime) соответствует доверенное время [anchorUtcMs]. [bootCount] = null — номер
 * загрузки не удалось прочитать.
 */
data class ClockAnchor(val bootCount: Int?, val anchorUtcMs: Long, val anchorElapsedMs: Long)

/** Отложенная смена учётного пояса на [zoneId], запрошенная в доверенный момент [sinceMs]. */
data class PendingZone(val zoneId: String, val sinceMs: Long) {
    val effectiveAtMs: Long get() = sinceMs + TrustedClockMath.ZONE_CHANGE_DELAY_MS
}

/** Учётный пояс [zoneId] и, возможно, отложенная смена [pending]. */
data class ZoneState(val zoneId: String, val pending: PendingZone?)

/** Чистая логика доверенных часов, без Android: её проверяют юнит-тесты. */
object TrustedClockMath {
    /** Расхождение доверенного и системного времени больше этого — пишется в лог. */
    const val MAX_SKEW_MS = 120_000L

    /** last_trusted_ms сохраняется не чаще раза в минуту. */
    const val PERSIST_INTERVAL_MS = 60_000L

    /** Смена учётного пояса вступает в силу через сутки доверенного времени. */
    const val ZONE_CHANGE_DELAY_MS = 24 * 60 * 60_000L

    /** Доверенное время в пределах одной загрузки: якорь плюс прошедшее по монотонным часам. */
    fun trustedNow(anchor: ClockAnchor, elapsedNowMs: Long): Long =
        anchor.anchorUtcMs + (elapsedNowMs - anchor.anchorElapsedMs)

    /**
     * Та же ли это загрузка, что у сохранённого якоря. Монотонные часы ушли назад — точно новая;
     * иначе решает номер загрузки, а если его нет с одной из сторон — остаётся только проверка часов.
     */
    fun isSameBoot(saved: ClockAnchor, bootCount: Int?, elapsedNowMs: Long): Boolean = when {
        elapsedNowMs < saved.anchorElapsedMs -> false
        bootCount != null && saved.bootCount != null -> bootCount == saved.bootCount
        else -> true
    }

    /**
     * Доверенное время в начале новой загрузки: сетевое время, если оно есть (Android 13+);
     * иначе системное, но не раньше последнего доверенного [lastTrustedMs] — назад время не уходит.
     */
    fun bootUtcMs(networkMs: Long?, systemMs: Long, lastTrustedMs: Long?): Long = when {
        networkMs != null -> networkMs
        lastTrustedMs != null -> maxOf(systemMs, lastTrustedMs)
        else -> systemMs
    }

    /**
     * Якорь при запуске процесса: в той же загрузке — прежний (перезапуск процесса его не меняет),
     * иначе новый по [bootUtcMs]. Системное время [systemNowMs] участвует только в новом якоре без сети.
     */
    fun resolveAnchor(
        saved: ClockAnchor?,
        lastTrustedMs: Long?,
        bootCount: Int?,
        elapsedNowMs: Long,
        systemNowMs: Long,
        networkNowMs: Long?,
    ): ClockAnchor =
        if (saved != null && isSameBoot(saved, bootCount, elapsedNowMs)) {
            saved.copy(bootCount = bootCount ?: saved.bootCount)
        } else {
            ClockAnchor(bootCount, bootUtcMs(networkNowMs, systemNowMs, lastTrustedMs), elapsedNowMs)
        }

    fun isSkewed(trustedMs: Long, systemMs: Long): Boolean = abs(trustedMs - systemMs) > MAX_SKEW_MS

    /** Пора ли сохранить last_trusted_ms: ни разу не сохраняли или прошла минута монотонных часов. */
    fun shouldPersist(lastPersistElapsedMs: Long?, elapsedNowMs: Long): Boolean =
        lastPersistElapsedMs == null || elapsedNowMs < lastPersistElapsedMs ||
            elapsedNowMs - lastPersistElapsedMs >= PERSIST_INTERVAL_MS

    /** Учётный пояс на доверенный момент [nowMs]: отложенная смена применяется через [ZONE_CHANGE_DELAY_MS], не раньше. */
    fun effectiveZone(state: ZoneState, nowMs: Long): ZoneState {
        val p = state.pending ?: return state
        return when {
            p.zoneId == state.zoneId -> state.copy(pending = null)
            nowMs - p.sinceMs >= ZONE_CHANGE_DELAY_MS -> ZoneState(p.zoneId, null)
            else -> state
        }
    }

    /**
     * Запрос смены пояса на [newZoneId] в доверенный момент [nowMs]: на текущий учётный — отмена отложенной;
     * повтор того же запроса отсчёт не сбрасывает; другой пояс — отсчёт заново.
     */
    fun requestZone(state: ZoneState, newZoneId: String, nowMs: Long): ZoneState = when {
        newZoneId == state.zoneId -> state.copy(pending = null)
        state.pending?.zoneId == newZoneId -> state
        else -> state.copy(pending = PendingZone(newZoneId, nowMs))
    }

    /**
     * На какой пояс предложить кнопку «Сменить на текущий»: пояс телефона [phoneZoneId], если он не учётный
     * и не тот, на который смена уже запрошена; иначе null — кнопки нет.
     */
    fun zoneSwitchOffer(state: ZoneState, phoneZoneId: String): String? =
        phoneZoneId.takeIf { it != state.zoneId && it != state.pending?.zoneId }

    /**
     * Ближайшее после [now] время [minuteOfDay] учётного пояса — на часах телефона в поясе [phoneZone], «07:00».
     * Нужно экрану блокировки: «доступно с …» в учётном поясе, а показывать — по часам телефона.
     */
    fun nextLocalClock(minuteOfDay: Int, now: TimeSnapshot, phoneZone: ZoneId): String {
        val m = Math.floorMod(minuteOfDay, 24 * 60)
        val local = now.local
        var at = local.toLocalDate().atTime(m / 60, m % 60).atZone(now.zone)
        if (!at.isAfter(local)) at = local.toLocalDate().plusDays(1).atTime(m / 60, m % 60).atZone(now.zone)
        val phone = at.withZoneSameInstant(phoneZone)
        return "%02d:%02d".format(phone.hour, phone.minute)
    }

    /** Начало учётных суток [day] в мс эпохи. */
    fun dayStartMs(day: Long, zone: ZoneId): Long = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    // ---------- Восстановление из копии ----------

    /** Ключи meta, которые принадлежат этой загрузке устройства: из копии не восстанавливаются. */
    val LOCAL_ONLY_KEYS = listOf(
        TrustedClock.KEY_BOOT_COUNT, TrustedClock.KEY_ANCHOR_UTC, TrustedClock.KEY_ANCHOR_ELAPSED, TrustedClock.KEY_LAST_TRUSTED,
    )

    /**
     * Что поменять в meta базы из копии перед подменой (ключ → значение; null — удалить).
     * Якорь, номер загрузки и last_trusted — всегда здешние. Учётный пояс — из копии; в старой копии его нет —
     * остаётся здешний. Отложенная смена пояса из копии не переносится (её время из другой загрузки).
     */
    fun restoredMeta(local: Map<String, String>, backup: Map<String, String>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (k in LOCAL_ONLY_KEYS) out[k] = local[k]
        if (backup[TrustedClock.KEY_ZONE] == null) out[TrustedClock.KEY_ZONE] = local[TrustedClock.KEY_ZONE]
        out[TrustedClock.KEY_PENDING_ZONE] = null
        out[TrustedClock.KEY_PENDING_SINCE] = null
        return out
    }
}

/**
 * Единый источник «доверенного времени» для правил блокировки и журнала баланса.
 *
 * В пределах одной загрузки устройства время считается от якоря по монотонным часам:
 * `trustedNow = anchorUtc + elapsedRealtime() − anchorElapsed`, поэтому перевод системных часов
 * вперёд или назад ни сутки учёта, ни ночь не меняет. Якорь ставится один раз на загрузку
 * (номер — Settings.Global.BOOT_COUNT) и сразу сохраняется в meta; перезапуск процесса его не меняет.
 *
 * В новой загрузке якорь берётся из сетевого времени (Android 13+, SystemClock.currentNetworkTimeClock),
 * а без него — из системного, но не раньше last_trusted_ms. Это защита от ручного перевода часов,
 * не криптографическая. Ограничение: на Android 10–12 сетевого времени нет, поэтому «перевести часы
 * вперёд и перезагрузиться» по-прежнему сдвигает доверенное время вперёд (назад — нет). На Android 13+
 * то же возможно, если в начале загрузки сетевое время ещё не получено (например, в режиме полёта).
 *
 * Учётный часовой пояс фиксируется при первом запуске и сам не меняется: сутки лимита, журнала и шагов
 * и ночной режим считаются в нём. Смена — только по запросу владельца ([requestZoneChange]) и через
 * [TrustedClockMath.ZONE_CHANGE_DELAY_MS] доверенного времени.
 *
 * Все чтения дешёвые (из памяти); база пишется при постановке якоря, раз в минуту (last_trusted_ms)
 * и при смене пояса. Блокировка базы — монитор [Diary]; собственных блокировок, которые держатся
 * во время записи в базу, нет, поэтому взаимной блокировки с [Diary] быть не может.
 */
object TrustedClock {
    private const val TAG = "TrustedClock"

    internal const val KEY_BOOT_COUNT = "clock_boot_count"
    internal const val KEY_ANCHOR_UTC = "clock_anchor_utc_ms"
    internal const val KEY_ANCHOR_ELAPSED = "clock_anchor_elapsed_ms"
    internal const val KEY_LAST_TRUSTED = "clock_last_trusted_ms"
    internal const val KEY_ZONE = "accounting_zone_id"
    internal const val KEY_PENDING_ZONE = "accounting_zone_pending"
    internal const val KEY_PENDING_SINCE = "accounting_zone_pending_since_ms"

    @Volatile private var anchor: ClockAnchor? = null
    @Volatile private var zoneState: ZoneState? = null
    @Volatile private var lastPersistElapsedMs: Long? = null
    @Volatile private var warnedNotInit = false

    private val persisting = AtomicBoolean(false)
    private val persistExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "TrustedClock").apply { isDaemon = true } }

    /**
     * Ставит или читает якорь и учётный пояс. Вызывается из [Store.init] после [Diary.init].
     * При ошибке базы якорь живёт только в памяти (время всё равно монотонно в пределах процесса).
     */
    fun init(context: Context) {
        if (anchor != null) return
        val elapsed = SystemClock.elapsedRealtime()
        val system = System.currentTimeMillis()
        val boot = bootCount(context)
        synchronized(Diary) {
            if (anchor != null) return
            try {
                val saved = readAnchor()
                val last = Diary.metaGet(KEY_LAST_TRUSTED)?.toLongOrNull()
                // Сетевое время нужно только для нового якоря: не трогаем его в той же загрузке.
                val sameBoot = saved != null && TrustedClockMath.isSameBoot(saved, boot, elapsed)
                val network = if (sameBoot) null else networkTimeMs()
                val a = TrustedClockMath.resolveAnchor(saved, last, boot, elapsed, system, network)
                if (a != saved) {
                    Diary.write {
                        a.bootCount?.let { Diary.metaPut(KEY_BOOT_COUNT, it.toString()) }
                        Diary.metaPut(KEY_ANCHOR_UTC, a.anchorUtcMs.toString())
                        Diary.metaPut(KEY_ANCHOR_ELAPSED, a.anchorElapsedMs.toString())
                        Diary.metaPut(KEY_LAST_TRUSTED, TrustedClockMath.trustedNow(a, elapsed).toString())
                    }
                    lastPersistElapsedMs = elapsed
                    if (!sameBoot) {
                        Log.i(TAG, "Новый якорь: загрузка ${a.bootCount}, источник ${if (network != null) "сеть" else "система"}")
                    }
                }
                anchor = a
                zoneState = readZoneState()
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось подготовить доверенные часы; якорь только в памяти", e)
                anchor = ClockAnchor(boot, system, elapsed)
                zoneState = ZoneState(ZoneId.systemDefault().id, null)
            }
        }
        logSkew("запуск")
    }

    /** Доверенное время (мс эпохи). До [init] — системное (с предупреждением в лог). */
    fun nowMs(): Long {
        val a = anchor
        if (a == null) {
            if (!warnedNotInit) {
                warnedNotInit = true
                Log.w(TAG, "TrustedClock.init не вызван: пока используется системное время")
            }
            return System.currentTimeMillis()
        }
        val elapsed = SystemClock.elapsedRealtime()
        val now = TrustedClockMath.trustedNow(a, elapsed)
        if (TrustedClockMath.shouldPersist(lastPersistElapsedMs, elapsed)) persistLastTrusted(now, elapsed)
        return now
    }

    /** Учётный пояс; отложенная смена применяется здесь, когда прошли сутки доверенного времени. */
    fun zone(): ZoneId = zoneAt(nowMs())

    /** Доверенный момент и учётный пояс одним снимком. */
    fun snapshot(): TimeSnapshot {
        val now = nowMs()
        return TimeSnapshot(now, zoneAt(now))
    }

    /** Учётные сутки сейчас. */
    fun today(): Long = snapshot().day

    /** Учётные сутки момента [utcMs]. */
    fun dayOf(utcMs: Long): Long = LedgerMath.accountingDay(utcMs, zone())

    /** Учётный пояс и отложенная смена (для экрана настроек). */
    fun zoneState(): ZoneState {
        zone() // применить отложенную смену, если срок вышел
        return zoneState ?: ZoneState(ZoneId.systemDefault().id, null)
    }

    /**
     * Запросить смену учётного пояса на [newZone] (по умолчанию — текущий пояс телефона).
     * Вступает в силу через сутки; запрос на текущий учётный пояс отменяет отложенную смену.
     */
    fun requestZoneChange(newZone: ZoneId = ZoneId.systemDefault()) {
        val now = nowMs()
        synchronized(Diary) {
            val state = TrustedClockMath.effectiveZone(zoneState ?: readZoneState(), now)
            val next = TrustedClockMath.requestZone(state, newZone.id, now)
            writeZoneState(next)
        }
        Store.notifyChanged()
    }

    /** Отменить отложенную смену пояса. */
    fun cancelZoneChange() {
        val current = zoneState() // уже с применённой сменой, если срок вышел
        if (current.pending == null) return
        requestZoneChange(ZoneId.of(current.zoneId))
    }

    /** Насколько доверенное время впереди системного (мс; для диагностики). */
    fun systemSkewMs(): Long = nowMs() - System.currentTimeMillis()

    /** Пишет в лог, если доверенное и системное время расходятся больше чем на 2 минуты. Якорь не переставляется. */
    fun logSkew(cause: String) {
        val a = anchor ?: return
        val trusted = TrustedClockMath.trustedNow(a, SystemClock.elapsedRealtime())
        val system = System.currentTimeMillis()
        if (TrustedClockMath.isSkewed(trusted, system)) {
            Log.w(TAG, "Системные часы расходятся с доверенными на ${(system - trusted) / 1000} с ($cause); якорь не меняется")
        }
    }

    /** После подмены базы (восстановление копии): перечитать учётный пояс; якорь здешний и не меняется. */
    internal fun onDatabaseReplaced() = synchronized(Diary) {
        try {
            zoneState = readZoneState()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось перечитать учётный пояс", e)
        }
    }

    /** Здешние значения meta, которые восстановление не должно перезаписать (якорь, пояс). */
    internal fun localMetaForRestore(): Map<String, String> = synchronized(Diary) {
        val out = HashMap<String, String>()
        for (k in TrustedClockMath.LOCAL_ONLY_KEYS + KEY_ZONE) Diary.metaGet(k)?.let { out[k] = it }
        // last_trusted_ms в базе может отставать до минуты — берём текущее.
        anchor?.let { out[KEY_LAST_TRUSTED] = TrustedClockMath.trustedNow(it, SystemClock.elapsedRealtime()).toString() }
        out
    }

    // ---------- Внутреннее ----------

    private fun zoneAt(nowMs: Long): ZoneId {
        val state = zoneState ?: return ZoneId.systemDefault()
        val eff = TrustedClockMath.effectiveZone(state, nowMs)
        if (eff != state) {
            try {
                synchronized(Diary) {
                    val current = zoneState ?: state
                    val again = TrustedClockMath.effectiveZone(current, nowMs)
                    if (again != current) {
                        writeZoneState(again)
                        Log.i(TAG, "Учётный пояс сменён: ${current.zoneId} → ${again.zoneId}")
                    }
                }
                Store.notifyChanged()
            } catch (e: Exception) {
                // Не записалось — применяем в памяти, запись повторится при следующем чтении после перезапуска.
                Log.e(TAG, "Не удалось сохранить смену учётного пояса", e)
                zoneState = eff
            }
        }
        return zoneOf((zoneState ?: eff).zoneId)
    }

    private fun writeZoneState(s: ZoneState) {
        Diary.write {
            Diary.metaPut(KEY_ZONE, s.zoneId)
            val p = s.pending
            if (p == null) {
                Diary.metaDelete(KEY_PENDING_ZONE)
                Diary.metaDelete(KEY_PENDING_SINCE)
            } else {
                Diary.metaPut(KEY_PENDING_ZONE, p.zoneId)
                Diary.metaPut(KEY_PENDING_SINCE, p.sinceMs.toString())
            }
        }
        zoneState = s
    }

    /** Под блокировкой Diary. Пояса ещё нет — фиксируется текущий пояс телефона. */
    private fun readZoneState(): ZoneState {
        var zone = Diary.metaGet(KEY_ZONE)?.takeIf { isValidZone(it) }
        if (zone == null) {
            zone = ZoneId.systemDefault().id
            Diary.metaPut(KEY_ZONE, zone)
            Log.i(TAG, "Учётный пояс зафиксирован: $zone")
        }
        val pendingId = Diary.metaGet(KEY_PENDING_ZONE)?.takeIf { isValidZone(it) }
        val since = Diary.metaGet(KEY_PENDING_SINCE)?.toLongOrNull()
        val pending = if (pendingId != null && since != null) PendingZone(pendingId, since) else null
        return ZoneState(zone, pending)
    }

    private fun readAnchor(): ClockAnchor? {
        val utc = Diary.metaGet(KEY_ANCHOR_UTC)?.toLongOrNull() ?: return null
        val elapsed = Diary.metaGet(KEY_ANCHOR_ELAPSED)?.toLongOrNull() ?: return null
        return ClockAnchor(Diary.metaGet(KEY_BOOT_COUNT)?.toIntOrNull(), utc, elapsed)
    }

    private fun persistLastTrusted(now: Long, elapsed: Long) {
        if (!persisting.compareAndSet(false, true)) return
        lastPersistElapsedMs = elapsed
        persistExecutor.execute {
            try {
                synchronized(Diary) {
                    val stored = Diary.metaGet(KEY_LAST_TRUSTED)?.toLongOrNull()
                    if (stored == null || now > stored) Diary.metaPut(KEY_LAST_TRUSTED, now.toString())
                }
                logSkew("проверка раз в минуту")
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось сохранить last_trusted_ms", e)
            } finally {
                persisting.set(false)
            }
        }
    }

    private fun bootCount(context: Context): Int? = try {
        SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        Log.w(TAG, "Номер загрузки недоступен", e)
        null
    }

    /** Сетевое время (Android 13+); нет — null. Не зависит от ручного перевода системных часов. */
    private fun networkTimeMs(): Long? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            SystemClock.currentNetworkTimeClock().millis()
        } catch (e: DateTimeException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "Сетевое время недоступно", e)
            null
        }
    }

    private fun isValidZone(id: String): Boolean = runCatching { ZoneId.of(id) }.isSuccess

    private fun zoneOf(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrElse { ZoneId.systemDefault() }
}
