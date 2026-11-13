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
 * Откуда взят якорь. [NETWORK] — сетевое время подтверждено (якорь по нему или впереди него);
 * [CONSERVATIVE] — сетевого времени не было: якорь от последнего доверенного момента, время
 * выключенного телефона потеряно (доверенное время отстаёт — это безопасная сторона).
 * Имя пишется в meta — не переименовывать.
 */
enum class AnchorSource { NETWORK, CONSERVATIVE }

/**
 * Якорь доверенных часов: в загрузке устройства [bootCount] моменту [anchorElapsedMs] монотонных часов
 * (SystemClock.elapsedRealtime) соответствует доверенное время [anchorUtcMs]. [bootCount] = null — номер
 * загрузки не удалось прочитать. [source] — откуда взят якорь.
 */
data class ClockAnchor(
    val bootCount: Int?,
    val anchorUtcMs: Long,
    val anchorElapsedMs: Long,
    val source: AnchorSource = AnchorSource.CONSERVATIVE,
)

/**
 * Состояние часов из meta, уже проверенное ([TrustedClockMath.parseSaved]): якорь, верхняя отметка
 * доверенного времени [lastTrustedMs] и момент монотонных часов [lastElapsedMs], когда она записана
 * (в той же загрузке, что и якорь). [rejected] — ключи, значения которых отброшены как недопустимые.
 */
data class SavedClock(
    val anchor: ClockAnchor?,
    val lastTrustedMs: Long?,
    val lastElapsedMs: Long?,
    val rejected: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = SavedClock(null, null, null)
    }
}

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

    /** Пока якорь консервативный, сетевое время пробуется не чаще раза в минуту. */
    const val NETWORK_RETRY_MS = 60_000L

    /** Неудавшаяся подготовка часов повторяется при обращении, не чаще раза в 5 секунд. */
    const val INIT_RETRY_MS = 5_000L

    /** Смена учётного пояса вступает в силу через сутки доверенного времени. */
    const val ZONE_CHANGE_DELAY_MS = 24 * 60 * 60_000L

    /** Допустимое время UTC: [MIN_UTC_MS] (2024-01-01) включительно … [MAX_UTC_MS] (2100-01-01) исключительно. */
    const val MIN_UTC_MS = 1_704_067_200_000L
    const val MAX_UTC_MS = 4_102_444_800_000L

    fun isValidUtc(ms: Long?): Boolean = ms != null && ms >= MIN_UTC_MS && ms < MAX_UTC_MS

    fun clampUtc(ms: Long): Long = ms.coerceIn(MIN_UTC_MS, MAX_UTC_MS - 1)

    /** Сложение без переполнения: при переполнении — граница Long. */
    fun addSaturating(a: Long, b: Long): Long = try {
        Math.addExact(a, b)
    } catch (e: ArithmeticException) {
        if (b > 0) Long.MAX_VALUE else Long.MIN_VALUE
    }

    /** Доверенное время в пределах одной загрузки: якорь плюс прошедшее по монотонным часам (в допустимых пределах). */
    fun trustedNow(anchor: ClockAnchor, elapsedNowMs: Long): Long =
        clampUtc(addSaturating(anchor.anchorUtcMs, elapsedNowMs - anchor.anchorElapsedMs))

    /**
     * Разбирает meta часов ([get] — значение по ключу). Время вне [MIN_UTC_MS]…[MAX_UTC_MS], отрицательные
     * монотонные часы и нечисла отбрасываются (ключ попадает в [SavedClock.rejected]): дальше такая
     * загрузка считается неопределённой, как без сетевого времени.
     */
    fun parseSaved(get: (String) -> String?): SavedClock {
        val rejected = ArrayList<String>()
        fun long(key: String, ok: (Long) -> Boolean): Long? {
            val raw = get(key) ?: return null
            val v = raw.toLongOrNull()
            if (v == null || !ok(v)) {
                rejected += key
                return null
            }
            return v
        }
        val utc = long(TrustedClock.KEY_ANCHOR_UTC) { isValidUtc(it) }
        val elapsed = long(TrustedClock.KEY_ANCHOR_ELAPSED) { it >= 0 }
        val anchor = if (utc != null && elapsed != null) {
            val source = get(TrustedClock.KEY_ANCHOR_SOURCE)?.let { s -> AnchorSource.entries.firstOrNull { it.name == s } }
            ClockAnchor(get(TrustedClock.KEY_BOOT_COUNT)?.toIntOrNull(), utc, elapsed, source ?: AnchorSource.CONSERVATIVE)
        } else null
        val last = long(TrustedClock.KEY_LAST_TRUSTED) { isValidUtc(it) }
        val lastElapsed = long(TrustedClock.KEY_LAST_ELAPSED) { it >= 0 }
        return SavedClock(anchor, last, lastElapsed, rejected)
    }

    /**
     * Та же ли это загрузка, что у сохранённого якоря. Монотонные часы ниже последнего наблюдавшегося
     * значения ([saved].anchorElapsedMs или [lastElapsedMs]) — точно новая. Иначе решает номер загрузки,
     * а если его нет с одной из сторон — загрузка та же, только если известен последний момент
     * монотонных часов [lastElapsedMs] этой загрузки (и сейчас не меньше его); иначе новая.
     */
    fun isSameBoot(saved: ClockAnchor, lastElapsedMs: Long?, bootCount: Int?, elapsedNowMs: Long): Boolean {
        val seen = maxOf(saved.anchorElapsedMs, lastElapsedMs ?: 0L)
        return when {
            elapsedNowMs < seen -> false
            bootCount != null && saved.bootCount != null -> bootCount == saved.bootCount
            else -> lastElapsedMs != null
        }
    }

    /** Верхняя отметка доверенного времени: доверенное время никогда не опускается ниже неё. */
    fun highWaterMs(saved: SavedClock): Long? =
        listOfNotNull(saved.lastTrustedMs, saved.anchor?.anchorUtcMs).maxOrNull()

    /**
     * Якорь при запуске процесса.
     *
     * Та же загрузка — прежний якорь (перезапуск процесса его не меняет); если он консервативный,
     * а сетевое время [networkNowMs] впереди — переставляется на сетевое ([upgradeWithNetwork]).
     *
     * Новая (или неопределённая) загрузка:
     * - есть сетевое время не ниже верхней отметки — якорь по сети ([AnchorSource.NETWORK]);
     * - сетевое время ниже отметки — остаёмся на отметке и идём от неё (время «догонит»);
     * - сетевого нет — отметка + elapsedRealtime на момент запуска ([AnchorSource.CONSERVATIVE]):
     *   время выключенного телефона теряется, сутки наступают позже — безопасная сторона;
     * - нет ни сети, ни истории (первый запуск) — системное время в допустимых пределах.
     *
     * Доверенное время никогда не уходит ниже отметки [highWaterMs].
     * Системное время [systemNowMs] участвует только при первом запуске.
     */
    fun resolveAnchor(
        saved: SavedClock,
        bootCount: Int?,
        elapsedNowMs: Long,
        systemNowMs: Long,
        networkNowMs: Long?,
    ): ClockAnchor {
        val elapsed = elapsedNowMs.coerceAtLeast(0L)
        val network = networkNowMs?.takeIf { isValidUtc(it) }
        val floor = highWaterMs(saved)
        val s = saved.anchor
        if (s != null && isSameBoot(s, saved.lastElapsedMs, bootCount, elapsed)) {
            var a = s.copy(bootCount = bootCount ?: s.bootCount)
            if (floor != null && trustedNow(a, elapsed) < floor) a = a.copy(anchorUtcMs = floor, anchorElapsedMs = elapsed)
            return upgradeWithNetwork(a, elapsed, network) ?: a
        }
        // Неопределённость: номера загрузки нет и последнего момента монотонных часов тоже (например, данные
        // прежней версии). Если это всё-таки та же загрузка, «отметка + uptime» убежала бы вперёд — берём
        // меньшее из двух консервативных вариантов (продолжение прежнего якоря, но не ниже отметки).
        val ambiguous = s != null && (bootCount == null || s.bootCount == null) && saved.lastElapsedMs == null &&
            elapsed >= s.anchorElapsedMs
        return when {
            network != null && (floor == null || network >= floor) -> ClockAnchor(bootCount, network, elapsed, AnchorSource.NETWORK)
            network != null -> ClockAnchor(bootCount, floor!!, elapsed, AnchorSource.NETWORK)
            floor != null -> {
                var base = addSaturating(floor, elapsed)
                if (ambiguous) base = minOf(base, maxOf(floor, trustedNow(s!!, elapsed)))
                ClockAnchor(bootCount, clampUtc(base), elapsed, AnchorSource.CONSERVATIVE)
            }
            else -> ClockAnchor(bootCount, clampUtc(systemNowMs), elapsed, AnchorSource.CONSERVATIVE)
        }
    }

    /**
     * Сетевое время для консервативного якоря. null — менять нечего (якорь уже сетевой или сети нет).
     * Сеть впереди доверенного — новый якорь по сети в этой же загрузке; не впереди — якорь прежний,
     * но помечается сетевым (время догонит само; назад оно не переставляется никогда).
     */
    fun upgradeWithNetwork(current: ClockAnchor, elapsedNowMs: Long, networkNowMs: Long?): ClockAnchor? {
        if (current.source != AnchorSource.CONSERVATIVE) return null
        val network = networkNowMs?.takeIf { isValidUtc(it) } ?: return null
        return if (network > trustedNow(current, elapsedNowMs)) {
            ClockAnchor(current.bootCount, network, elapsedNowMs, AnchorSource.NETWORK)
        } else {
            current.copy(source = AnchorSource.NETWORK)
        }
    }

    fun isSkewed(trustedMs: Long, systemMs: Long): Boolean = abs(trustedMs - systemMs) > MAX_SKEW_MS

    /** Пора ли сохранить last_trusted_ms: ни разу не сохраняли или прошла минута монотонных часов. */
    fun shouldPersist(lastPersistElapsedMs: Long?, elapsedNowMs: Long): Boolean =
        lastPersistElapsedMs == null || elapsedNowMs < lastPersistElapsedMs ||
            elapsedNowMs - lastPersistElapsedMs >= PERSIST_INTERVAL_MS

    /** Пора ли повторить неудавшуюся подготовку часов. */
    fun shouldRetryInit(lastAttemptElapsedMs: Long?, elapsedNowMs: Long): Boolean =
        lastAttemptElapsedMs == null || elapsedNowMs < lastAttemptElapsedMs ||
            elapsedNowMs - lastAttemptElapsedMs >= INIT_RETRY_MS

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
     * Шаги оплачены по суткам прежнего пояса; после смены учётного пояса границы суток сдвигаются, и уже
     * оплаченные шаги попали бы в соседние сутки. Поэтому за сутки по день смены (в новом поясе [newZone])
     * включительно новых начислений за шаги нет. Отметка только растёт.
     */
    fun stepsFrozenThrough(previous: Long?, nowMs: Long, newZone: ZoneId): Long =
        maxOf(previous ?: Long.MIN_VALUE, LedgerMath.accountingDay(nowMs, newZone))

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
        TrustedClock.KEY_BOOT_COUNT, TrustedClock.KEY_ANCHOR_UTC, TrustedClock.KEY_ANCHOR_ELAPSED,
        TrustedClock.KEY_ANCHOR_SOURCE, TrustedClock.KEY_LAST_TRUSTED, TrustedClock.KEY_LAST_ELAPSED,
    )

    /**
     * Что поменять в meta базы из копии перед подменой (ключ → значение; null — удалить).
     * Якорь, номер загрузки и last_trusted — всегда здешние. Учётный пояс сразу не меняется — остаётся
     * здешний (вместе со здешней отложенной сменой). Если в копии другой пояс (или его нет — старая копия),
     * он ставится обычной отложенной сменой с момента восстановления [nowMs], а оплата шагов замораживается
     * по сегодняшний день (итоги шагов в копии посчитаны по суткам её пояса). Здешнего пояса нет —
     * берётся пояс копии как есть.
     */
    fun restoredMeta(local: Map<String, String>, backup: Map<String, String>, nowMs: Long): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (k in LOCAL_ONLY_KEYS) out[k] = local[k]
        val localZone = local[TrustedClock.KEY_ZONE]?.takeIf { isValidZoneId(it) }
        val backupZone = backup[TrustedClock.KEY_ZONE]?.takeIf { isValidZoneId(it) }
        if (localZone == null) {
            out[TrustedClock.KEY_ZONE] = backupZone
            out[TrustedClock.KEY_PENDING_ZONE] = null
            out[TrustedClock.KEY_PENDING_SINCE] = null
            return out
        }
        out[TrustedClock.KEY_ZONE] = localZone
        if (backupZone == localZone) {
            out[TrustedClock.KEY_PENDING_ZONE] = local[TrustedClock.KEY_PENDING_ZONE]
            out[TrustedClock.KEY_PENDING_SINCE] = local[TrustedClock.KEY_PENDING_SINCE]
        } else {
            if (backupZone != null) {
                out[TrustedClock.KEY_PENDING_ZONE] = backupZone
                out[TrustedClock.KEY_PENDING_SINCE] = nowMs.toString()
            } else {
                out[TrustedClock.KEY_PENDING_ZONE] = local[TrustedClock.KEY_PENDING_ZONE]
                out[TrustedClock.KEY_PENDING_SINCE] = local[TrustedClock.KEY_PENDING_SINCE]
            }
            val frozen = backup[TrustedClock.KEY_STEPS_FROZEN]?.toLongOrNull()
            out[TrustedClock.KEY_STEPS_FROZEN] = stepsFrozenThrough(frozen, nowMs, ZoneId.of(localZone)).toString()
        }
        return out
    }

    fun isValidZoneId(id: String): Boolean = runCatching { ZoneId.of(id) }.isSuccess
}

/**
 * Единый источник «доверенного времени» для правил блокировки и журнала баланса.
 *
 * В пределах одной загрузки устройства время считается от якоря по монотонным часам:
 * `trustedNow = anchorUtc + elapsedRealtime() − anchorElapsed`, поэтому перевод системных часов
 * вперёд или назад ни сутки учёта, ни ночь не меняет. Якорь ставится один раз на загрузку
 * (номер — Settings.Global.BOOT_COUNT; без него — по последнему наблюдавшемуся elapsedRealtime)
 * и сразу сохраняется в meta; перезапуск процесса его не меняет.
 *
 * В новой загрузке якорь берётся из сетевого времени (Android 13+, SystemClock.currentNetworkTimeClock),
 * а без него — консервативно: last_trusted_ms + elapsedRealtime() (время выключенного телефона теряется,
 * доверенное время отстаёт, сутки наступают позже — безопасная сторона). Системные часы в новой загрузке
 * не участвуют, поэтому «перевести часы вперёд и перезагрузиться» доверенное время не сдвигает.
 * Пока якорь консервативный, сетевое время пробуется раз в минуту; появилось и оно впереди — якорь
 * переставляется на него. Назад доверенное время не уходит никогда: last_trusted_ms — верхняя отметка.
 * Это защита от ручного перевода часов, не криптографическая.
 *
 * Если базу не удалось прочитать, время идёт от запасного якоря в памяти («не доверено», [isReliable]),
 * а подготовка повторяется при обращении, пока не удастся. Запасной якорь ничего не закрепляет.
 *
 * Учётный часовой пояс фиксируется при первом запуске и сам не меняется: сутки лимита, журнала и шагов
 * и ночной режим считаются в нём. Смена — только по запросу владельца ([requestZoneChange]) и через
 * [TrustedClockMath.ZONE_CHANGE_DELAY_MS] доверенного времени; при смене оплата шагов замораживается
 * по день смены ([stepsFrozenThroughDay]).
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
    internal const val KEY_ANCHOR_SOURCE = "clock_anchor_source"
    internal const val KEY_LAST_TRUSTED = "clock_last_trusted_ms"
    internal const val KEY_LAST_ELAPSED = "clock_last_elapsed_ms"
    internal const val KEY_ZONE = "accounting_zone_id"
    internal const val KEY_PENDING_ZONE = "accounting_zone_pending"
    internal const val KEY_PENDING_SINCE = "accounting_zone_pending_since_ms"
    internal const val KEY_STEPS_FROZEN = "steps_frozen_through_day"

    @Volatile private var appContext: Context? = null
    /** Якорь, посчитанный по данным базы. null — подготовка ещё не удалась. */
    @Volatile private var anchor: ClockAnchor? = null
    /** Якорь посчитан, но в meta ещё не записан (ошибка записи или переставлен по сетевому времени). */
    @Volatile private var anchorDirty = false
    /** Запасной якорь, пока базу не удалось прочитать: только в памяти, до первой удачной подготовки. */
    @Volatile private var fallback: ClockAnchor? = null
    @Volatile private var zoneState: ZoneState? = null
    @Volatile private var lastPersistElapsedMs: Long? = null
    @Volatile private var lastInitAttemptElapsedMs: Long? = null
    @Volatile private var lastNetworkCheckElapsedMs: Long? = null
    /** Заморозка оплаты шагов, если смену пояса не удалось записать в базу (тогда она применена в памяти). */
    @Volatile private var stepsFrozenInMemory: Long? = null
    @Volatile private var warnedNotInit = false
    @Volatile private var warnedFallback = false

    private val persisting = AtomicBoolean(false)
    private val persistExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "TrustedClock").apply { isDaemon = true } }

    /**
     * Ставит или читает якорь и учётный пояс. Вызывается из [Store.init] после [Diary.init].
     * Не бросает: при ошибке базы время идёт от запасного якоря, а подготовка повторяется при обращении.
     */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        tryInit()
        logSkew("запуск")
    }

    /** true — часы подготовлены по базе; false — пока запасное время («не доверено»). */
    fun isReliable(): Boolean = anchor != null && zoneState != null

    /** Доверенное время (мс эпохи). Пока часы не подготовлены — запасное время (с повтором подготовки). */
    fun nowMs(): Long {
        val elapsed = SystemClock.elapsedRealtime()
        var a = anchor
        if (a == null || zoneState == null) {
            if (TrustedClockMath.shouldRetryInit(lastInitAttemptElapsedMs, elapsed)) tryInit()
            a = anchor
        }
        if (a == null) return provisionalNow(elapsed)
        val now = TrustedClockMath.trustedNow(a, elapsed)
        if (TrustedClockMath.shouldPersist(lastPersistElapsedMs, elapsed)) persistAsync(elapsed)
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
     * По какие учётные сутки включительно шаги не оплачиваются (после смены учётного пояса); null — нет.
     * Читает meta под блокировкой [Diary] (можно внутри [Diary.write]). Ошибки базы пробрасываются.
     */
    fun stepsFrozenThroughDay(): Long? {
        val stored = synchronized(Diary) { Diary.metaGet(KEY_STEPS_FROZEN)?.toLongOrNull() }
        return listOfNotNull(stored, stepsFrozenInMemory).maxOrNull()
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
            writeZoneState(next, now)
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

    /** Здешние значения meta, которые восстановление не должно перезаписать (якорь, пояс, отложенная смена). */
    internal fun localMetaForRestore(): Map<String, String> = synchronized(Diary) {
        val out = HashMap<String, String>()
        for (k in TrustedClockMath.LOCAL_ONLY_KEYS + listOf(KEY_ZONE, KEY_PENDING_ZONE, KEY_PENDING_SINCE)) {
            Diary.metaGet(k)?.let { out[k] = it }
        }
        // В базе якорь мог ещё не записаться, а last_trusted_ms отстаёт до минуты — берём текущие.
        anchor?.let { a ->
            val elapsed = SystemClock.elapsedRealtime()
            a.bootCount?.let { out[KEY_BOOT_COUNT] = it.toString() }
            out[KEY_ANCHOR_UTC] = a.anchorUtcMs.toString()
            out[KEY_ANCHOR_ELAPSED] = a.anchorElapsedMs.toString()
            out[KEY_ANCHOR_SOURCE] = a.source.name
            val stored = out[KEY_LAST_TRUSTED]?.toLongOrNull()
            out[KEY_LAST_TRUSTED] = maxOf(stored ?: Long.MIN_VALUE, TrustedClockMath.trustedNow(a, elapsed)).toString()
            out[KEY_LAST_ELAPSED] = elapsed.toString()
        }
        out
    }

    // ---------- Внутреннее ----------

    /**
     * Подготовка часов: чтение meta, якорь, запись, учётный пояс — каждый шаг со своей ошибкой.
     * true — готово. До [Diary.init] и внутри открытой транзакции ничего не делает (и ничего не закрепляет).
     */
    private fun tryInit(): Boolean {
        if (isReliable()) return true
        val ctx = appContext ?: return false
        val elapsed = SystemClock.elapsedRealtime()
        lastInitAttemptElapsedMs = elapsed
        val boot = bootCount(ctx)
        val system = System.currentTimeMillis()
        val network = networkTimeMs()
        synchronized(Diary) {
            if (isReliable()) return true
            val db = try {
                Diary.db()
            } catch (e: IllegalStateException) {
                // Diary.init ещё не вызван: запасной якорь не ставим, просто ждём.
                return false
            } catch (e: Exception) {
                Log.e(TAG, "База не открывается; время не доверено", e)
                useFallback(boot, elapsed, system, network)
                return false
            }
            // Внутри чужой транзакции не пишем: сбой записи откатил бы и её.
            if (db.inTransaction()) return false

            if (anchor == null) {
                val saved = try {
                    TrustedClockMath.parseSaved { Diary.metaGet(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось прочитать якорь; время не доверено", e)
                    useFallback(boot, elapsed, system, network)
                    return false
                }
                if (saved.rejected.isNotEmpty()) {
                    Log.w(TAG, "Недопустимые значения часов в базе отброшены: ${saved.rejected}; время неопределённое")
                }
                if (network != null && !TrustedClockMath.isValidUtc(network)) {
                    Log.w(TAG, "Сетевое время вне допустимых пределов: $network; не используется")
                }
                val a = TrustedClockMath.resolveAnchor(saved, boot, elapsed, system, network)
                val floor = TrustedClockMath.highWaterMs(saved)
                if (network != null && floor != null && TrustedClockMath.isValidUtc(network) && network < floor) {
                    Log.w(TAG, "Сетевое время ниже last_trusted на ${(floor - network) / 1000} с: остаёмся на отметке")
                }
                anchor = a
                fallback = null
                try {
                    writeClock(a, elapsed)
                    anchorDirty = false
                    lastPersistElapsedMs = elapsed
                } catch (e: Exception) {
                    // Якорь посчитан по данным базы — им пользуемся; запись повторится раз в минуту.
                    Log.e(TAG, "Не удалось записать якорь; повтор позже", e)
                    anchorDirty = true
                }
                if (a.anchorElapsedMs == elapsed && a != saved.anchor) {
                    Log.i(TAG, "Новый якорь: загрузка ${a.bootCount}, источник ${a.source}")
                }
            }
            if (zoneState == null) {
                zoneState = try {
                    readZoneState()
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось прочитать учётный пояс; пока пояс телефона", e)
                    return false
                }
            }
        }
        return true
    }

    /** Запасной якорь в памяти: сетевое время, если есть, иначе системное — с пометкой «не доверено». */
    private fun useFallback(boot: Int?, elapsed: Long, system: Long, network: Long?) {
        if (fallback != null) return
        val base = network?.takeIf { TrustedClockMath.isValidUtc(it) } ?: system
        fallback = ClockAnchor(boot, TrustedClockMath.clampUtc(base), elapsed, AnchorSource.CONSERVATIVE)
    }

    private fun provisionalNow(elapsed: Long): Long {
        val f = fallback
        if (f != null) {
            if (!warnedFallback) {
                warnedFallback = true
                Log.w(TAG, "Доверенные часы не подготовлены: запасное время (не доверено)")
            }
            return TrustedClockMath.trustedNow(f, elapsed)
        }
        if (!warnedNotInit) {
            warnedNotInit = true
            Log.w(TAG, "TrustedClock ещё не подготовлен: пока системное время (не закрепляется)")
        }
        return TrustedClockMath.clampUtc(System.currentTimeMillis())
    }

    private fun zoneAt(nowMs: Long): ZoneId {
        val state = zoneState ?: return ZoneId.systemDefault()
        val eff = TrustedClockMath.effectiveZone(state, nowMs)
        if (eff != state) {
            try {
                synchronized(Diary) {
                    val current = zoneState ?: state
                    val again = TrustedClockMath.effectiveZone(current, nowMs)
                    if (again != current) {
                        writeZoneState(again, nowMs)
                        Log.i(TAG, "Учётный пояс сменён: ${current.zoneId} → ${again.zoneId}")
                    }
                }
                Store.notifyChanged()
            } catch (e: Exception) {
                // Не записалось — применяем в памяти (и заморозку шагов тоже), запись повторится при следующем чтении после перезапуска.
                Log.e(TAG, "Не удалось сохранить смену учётного пояса", e)
                if (eff.zoneId != state.zoneId) {
                    stepsFrozenInMemory = TrustedClockMath.stepsFrozenThrough(stepsFrozenInMemory, nowMs, zoneOf(eff.zoneId))
                }
                zoneState = eff
            }
        }
        return zoneOf((zoneState ?: eff).zoneId)
    }

    /** Пишет учётный пояс; если сам пояс меняется — в той же транзакции замораживает оплату шагов по день смены. */
    private fun writeZoneState(s: ZoneState, nowMs: Long) {
        val previous = zoneState
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
            if (previous != null && previous.zoneId != s.zoneId) {
                val old = Diary.metaGet(KEY_STEPS_FROZEN)?.toLongOrNull()
                val frozen = TrustedClockMath.stepsFrozenThrough(old, nowMs, zoneOf(s.zoneId))
                Diary.metaPut(KEY_STEPS_FROZEN, frozen.toString())
                Log.i(TAG, "Оплата шагов заморожена по сутки $frozen (смена пояса)")
            }
        }
        zoneState = s
    }

    /** Под блокировкой Diary. Пояса ещё нет — фиксируется текущий пояс телефона. */
    private fun readZoneState(): ZoneState {
        var zone = Diary.metaGet(KEY_ZONE)?.takeIf { TrustedClockMath.isValidZoneId(it) }
        if (zone == null) {
            zone = ZoneId.systemDefault().id
            Diary.metaPut(KEY_ZONE, zone)
            Log.i(TAG, "Учётный пояс зафиксирован: $zone")
        }
        val pendingId = Diary.metaGet(KEY_PENDING_ZONE)?.takeIf { TrustedClockMath.isValidZoneId(it) }
        val since = Diary.metaGet(KEY_PENDING_SINCE)?.toLongOrNull()?.takeIf { TrustedClockMath.isValidUtc(it) }
        val pending = if (pendingId != null && since != null) PendingZone(pendingId, since) else null
        return ZoneState(zone, pending)
    }

    /**
     * Под блокировкой Diary: якорь, его источник и верхняя отметка last_trusted (не уменьшается)
     * вместе с моментом монотонных часов [elapsed].
     */
    private fun writeClock(a: ClockAnchor, elapsed: Long) {
        Diary.write {
            if (a.bootCount != null) Diary.metaPut(KEY_BOOT_COUNT, a.bootCount.toString()) else Diary.metaDelete(KEY_BOOT_COUNT)
            Diary.metaPut(KEY_ANCHOR_UTC, a.anchorUtcMs.toString())
            Diary.metaPut(KEY_ANCHOR_ELAPSED, a.anchorElapsedMs.toString())
            Diary.metaPut(KEY_ANCHOR_SOURCE, a.source.name)
            writeLast(a, elapsed)
        }
    }

    /** last_trusted — только вверх; last_elapsed — всегда текущий (по нему узнаётся та же загрузка). */
    private fun writeLast(a: ClockAnchor, elapsed: Long) {
        val now = TrustedClockMath.trustedNow(a, elapsed)
        val stored = Diary.metaGet(KEY_LAST_TRUSTED)?.toLongOrNull()?.takeIf { TrustedClockMath.isValidUtc(it) }
        if (stored == null || now > stored) Diary.metaPut(KEY_LAST_TRUSTED, now.toString())
        Diary.metaPut(KEY_LAST_ELAPSED, elapsed.toString())
    }

    /**
     * Раз в минуту, в фоне: сетевое время для консервативного якоря, затем запись якоря (если он ещё
     * не записан) и last_trusted_ms.
     */
    private fun persistAsync(elapsedAtCall: Long) {
        if (!persisting.compareAndSet(false, true)) return
        lastPersistElapsedMs = elapsedAtCall
        persistExecutor.execute {
            try {
                maybeUpgradeWithNetwork()
                synchronized(Diary) {
                    val a = anchor ?: return@synchronized
                    val elapsed = SystemClock.elapsedRealtime()
                    if (anchorDirty) {
                        writeClock(a, elapsed)
                        anchorDirty = false
                    } else {
                        Diary.write { writeLast(a, elapsed) }
                    }
                }
                logSkew("проверка раз в минуту")
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось сохранить last_trusted_ms", e)
            } finally {
                persisting.set(false)
            }
        }
    }

    /** Пока якорь консервативный — не чаще раза в минуту пробует сетевое время; вперёд — переставляет якорь. */
    private fun maybeUpgradeWithNetwork() {
        val a = anchor ?: return
        if (a.source != AnchorSource.CONSERVATIVE) return
        val elapsed = SystemClock.elapsedRealtime()
        val last = lastNetworkCheckElapsedMs
        if (last != null && elapsed >= last && elapsed - last < TrustedClockMath.NETWORK_RETRY_MS) return
        lastNetworkCheckElapsedMs = elapsed
        val network = networkTimeMs() ?: return
        synchronized(Diary) {
            val current = anchor ?: return
            val up = TrustedClockMath.upgradeWithNetwork(current, SystemClock.elapsedRealtime(), network) ?: return
            if (up == current) return
            val before = TrustedClockMath.trustedNow(current, up.anchorElapsedMs)
            anchor = up
            anchorDirty = true
            if (up.anchorUtcMs > before) {
                Log.i(TAG, "Сетевое время получено: доверенное время вперёд на ${(up.anchorUtcMs - before) / 1000} с")
            } else {
                Log.i(TAG, "Сетевое время получено и не впереди доверенного: якорь прежний")
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

    private fun zoneOf(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrElse { ZoneId.systemDefault() }
}
