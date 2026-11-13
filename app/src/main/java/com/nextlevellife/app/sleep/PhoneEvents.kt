package com.nextlevellife.app.sleep

import android.content.Context
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.util.Log
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.TrustedClock
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Запись событий экрана для ночного перерыва. События приходят от службы блокировки
 * ([com.nextlevellife.app.block.BlockerService]) на главном потоке; время (доверенное, [TrustedClock])
 * снимается сразу, а вставка в базу идёт на отдельном однопоточном исполнителе — «очереди записи».
 * Порядок событий сохраняется, главный поток не ждёт SQLite. Ошибки записи только логируются:
 * блокировку они не трогают.
 *
 * Через ту же очередь идут расчёт окончательных ночей ([SleepRepository]) и пауза на время восстановления
 * копии ([pauseForRestore]): всё, что пишет или читает сырые данные ночей, выполняется строго по очереди.
 */
object PhoneEvents {
    private const val TAG = "PhoneEvents"

    /** Значения phone_events.source. */
    const val SOURCE_RECEIVER = "receiver"
    const val SOURCE_CONNECT = "connect"
    const val SOURCE_SHUTDOWN = SleepMath.SOURCE_SHUTDOWN
    const val SOURCE_RESTORE = "restore"
    const val SOURCE_SERVICE = "service"
    const val SOURCE_INTERRUPT = "interrupt"

    /** Значения observation_gaps.reason. */
    const val GAP_SERVICE_RESTART = "service_restart"
    const val GAP_RESTORE = "restore"

    /** Пульс службы: одна строка HEARTBEAT раз в 5 минут, пока служба жива. */
    const val HEARTBEAT_MS = 5 * 60_000L

    /** Как долго очередь может стоять на паузе восстановления, если её забыли снять. */
    private const val PAUSE_MAX_MS = 2 * 60_000L
    /** Сколько ждать, пока очередь дойдёт до паузы (допишет начатое). */
    private const val PAUSE_WAIT_MS = 10_000L

    @Volatile private var writerThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhoneEvents").apply { isDaemon = true }.also { writerThread = it }
    }

    /**
     * Служба подключена и события пишутся. false — наблюдения сейчас нет: всё после последнего события
     * считается пропуском, а ночи не записываются как окончательные ([SleepRepository]).
     * Становится true только после успешной записи подключения (пропуск и состояние) для действующего
     * поколения подключения; остановка службы сразу делает её false.
     */
    @Volatile var observing: Boolean = false
        private set

    /** Поколение подключения службы: растёт при каждом подключении и остановке. */
    private val generation = AtomicInteger(0)
    /** Поколение, подключение которого записано (только на потоке записи). */
    private var confirmedGeneration = -1

    @Volatile private var bootCount: Int? = null
    @Volatile private var bootRead = false

    /** Событие экрана из приёмника службы. Вызывать с главного потока: время снимается здесь. */
    fun record(type: PhoneEventType, source: String, interactive: Boolean?, locked: Boolean?) {
        val utc = TrustedClock.nowMs()
        val zone = TrustedClock.zone().id
        val elapsed = SystemClock.elapsedRealtime()
        executor.execute {
            try {
                val device = Ledger.deviceId()
                synchronized(Diary) {
                    insert(device, utc, zone, elapsed, type, source, interactive, locked)
                    invalidateFinal(device, utc)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось записать событие $type", e)
            }
        }
    }

    /**
     * Служба подключилась: записать пропуск наблюдения от последнего подтверждённого наблюдения
     * (событие, пульс, остановка службы; [SleepMath.connectGap]) и текущее состояние экрана событием STATE
     * с source = connect. Прошлое не выдумывается: до этого момента состояние берётся только из записанных событий.
     */
    fun onServiceConnected(context: Context, interactive: Boolean?, locked: Boolean?) {
        val gen = generation.incrementAndGet()
        observing = false
        val app = context.applicationContext
        val utc = TrustedClock.nowMs()
        val zone = TrustedClock.zone().id
        val elapsed = SystemClock.elapsedRealtime()
        executor.execute { writeConnect(app, gen, utc, zone, elapsed, interactive, locked) }
    }

    /** На потоке записи: пропуск и состояние при подключении; успех для действующего поколения — наблюдаем. */
    private fun writeConnect(app: Context, gen: Int, utc: Long, zone: String, elapsed: Long, interactive: Boolean?, locked: Boolean?) {
        if (generation.get() != gen) return // служба уже остановлена или подключилась заново
        try {
            val boot = bootCount(app)
            val device = Ledger.deviceId()
            synchronized(Diary) {
                val recent = Diary.recentPhoneEvents(device, SleepMath.CONNECT_RECENT_EVENTS)
                SleepMath.connectGap(recent, utc, boot)?.let {
                    Diary.addObservationGap(device, it.startMs, it.endMs, GAP_SERVICE_RESTART)
                    invalidateFinal(device, it.startMs)
                    Log.i(TAG, "Пропуск наблюдения ${(it.endMs - it.startMs) / 60_000} мин")
                }
                insert(device, utc, zone, elapsed, PhoneEventType.STATE, SOURCE_CONNECT, interactive, locked)
                invalidateFinal(device, utc)
            }
            confirmedGeneration = gen
            if (generation.get() == gen) observing = true
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать подключение службы", e)
        }
    }

    /**
     * Пульс службы (её тик раз в [HEARTBEAT_MS], главный поток): одна строка HEARTBEAT — наблюдение
     * подтверждено до этого момента. Если подключение этого поколения записать не удалось, вместо пульса
     * повторяется запись подключения (с текущим состоянием экрана [interactive]/[locked]).
     */
    fun heartbeat(context: Context, interactive: Boolean?, locked: Boolean?) {
        val gen = generation.get()
        val app = context.applicationContext
        val utc = TrustedClock.nowMs()
        val zone = TrustedClock.zone().id
        val elapsed = SystemClock.elapsedRealtime()
        executor.execute {
            if (generation.get() != gen) return@execute
            if (confirmedGeneration != gen) {
                writeConnect(app, gen, utc, zone, elapsed, interactive, locked)
                return@execute
            }
            try {
                val device = Ledger.deviceId()
                synchronized(Diary) {
                    insert(device, utc, zone, elapsed, PhoneEventType.HEARTBEAT, SOURCE_SERVICE, null, null)
                    invalidateFinal(device, utc)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось записать пульс", e)
            }
        }
    }

    /**
     * Служба остановлена (onDestroy): момент записывается событием SERVICE_STOP — от него, а не от последнего
     * события экрана, считается пропуск при следующем подключении. Поколение растёт, наблюдения больше нет.
     */
    fun onServiceStopped() {
        generation.incrementAndGet()
        observing = false
        recordServiceStop(SOURCE_SERVICE)
    }

    /**
     * Служба прервана (onInterrupt): момент записывается событием SERVICE_STOP с source = interrupt.
     * Служба при этом не отключается и события экрана продолжает получать, поэтому наблюдение не прекращается.
     */
    fun onServiceInterrupted() {
        recordServiceStop(SOURCE_INTERRUPT)
    }

    private fun recordServiceStop(source: String) {
        val utc = TrustedClock.nowMs()
        val zone = TrustedClock.zone().id
        val elapsed = SystemClock.elapsedRealtime()
        executor.execute {
            try {
                val device = Ledger.deviceId()
                synchronized(Diary) {
                    insert(device, utc, zone, elapsed, PhoneEventType.SERVICE_STOP, source, null, null)
                    invalidateFinal(device, utc)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось записать остановку службы", e)
            }
        }
    }

    /**
     * Часовое обслуживание (тик службы): на потоке записи — сначала финализация ночей, потом чистка
     * сырых данных ([SleepRepository.maintainOnWriter]).
     */
    fun maintain() {
        executor.execute {
            try {
                SleepRepository.maintainOnWriter()
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось обслужить ночные данные", e)
            }
        }
    }

    /**
     * Выполнить [block] на потоке записи и дождаться результата (не дольше [timeoutMs]): это барьер —
     * всё, что было поставлено в очередь раньше, уже записано. С самого потока записи — сразу.
     * Не вызывать с главного потока.
     */
    fun <T> onWriter(timeoutMs: Long, block: () -> T): T {
        if (Thread.currentThread() === writerThread) return block()
        val future = executor.submit(Callable { block() })
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    // ---------- Восстановление копии ----------

    /** Пауза очереди записи на время подмены базы; снять — [resume]. */
    class WritePause internal constructor() {
        internal val started = CountDownLatch(1)
        internal val released = CountDownLatch(1)
        @Volatile internal var pausedUtc = 0L
        @Volatile internal var pausedElapsed = 0L
        @Volatile internal var pausedZone = ""
        @Volatile internal var replaced = false

        /**
         * Снять паузу. [databaseReplaced] = true — база подменена: первой задачей, до всех событий,
         * накопившихся за паузу, записывается пропуск от последнего события восстановленной базы.
         */
        fun resume(databaseReplaced: Boolean) {
            replaced = databaseReplaced
            released.countDown()
        }
    }

    /**
     * Ставит очередь записи на паузу перед подменой базы восстановлением копии и ждёт, пока она допишет
     * начатое. Вызывать НЕ под блокировкой [Diary] (очередь может ждать её) и обязательно снять паузу
     * ([WritePause.resume]) в finally. Событие, пришедшее во время паузы, ждёт в очереди и попадёт уже
     * в новую базу — после пропуска.
     */
    fun pauseForRestore(): WritePause {
        val pause = WritePause()
        executor.execute {
            pause.pausedUtc = TrustedClock.nowMs()
            pause.pausedZone = TrustedClock.zone().id
            pause.pausedElapsed = SystemClock.elapsedRealtime()
            pause.started.countDown()
            if (!pause.released.await(PAUSE_MAX_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "Пауза восстановления не снята вовремя")
            if (pause.replaced) writeRestoreGap(pause.pausedUtc, pause.pausedZone, pause.pausedElapsed)
        }
        if (!pause.started.await(PAUSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Очередь записи не дошла до паузы: восстановление идёт без неё")
        }
        return pause
    }

    /**
     * База подменена: события после снимка копии потеряны. Граница восстановленной истории — последнее
     * событие восстановленной базы (новых записей ещё не было: очередь на паузе); пропуск — от неё до момента
     * паузы, затем состояние «пользуются» (восстановление запускают из приложения — телефон в руках).
     * События, накопившиеся за паузу, пишутся после этого.
     */
    private fun writeRestoreGap(utc: Long, zone: String, elapsed: Long) {
        try {
            val device = Ledger.deviceId()
            synchronized(Diary) {
                val last = Diary.lastPhoneEvent(device)
                if (last != null && utc > last.utcMs) {
                    Diary.addObservationGap(device, last.utcMs, utc, GAP_RESTORE)
                    invalidateFinal(device, last.utcMs)
                }
                insert(device, utc, zone, elapsed, PhoneEventType.STATE, SOURCE_RESTORE, interactive = true, locked = false)
                invalidateFinal(device, utc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось отметить восстановление копии", e)
        }
    }

    // ---------- Запись ----------

    /**
     * Событие или пропуск с моментом [utcMs] попал в ночь, которая уже окончательная: её итог (и следующих)
     * удаляется и пересчитается ([SleepMath.invalidatedFromDay]). Обычное событие «сейчас» ничего не трогает.
     */
    private fun invalidateFinal(device: String, utcMs: Long) {
        val snap = TrustedClock.snapshot()
        val from = SleepMath.invalidatedFromDay(utcMs, snap.utcMs, snap.zone, snap.day) ?: return
        val deleted = Diary.deleteSleepNightsFrom(device, from)
        if (deleted > 0) Log.i(TAG, "Запоздалые данные: итоги $deleted ноч. пересчитаются")
    }

    private fun insert(
        device: String, utc: Long, zone: String, elapsed: Long,
        type: PhoneEventType, source: String, interactive: Boolean?, locked: Boolean?,
    ) {
        val boot = bootCount
        // Одно и то же событие (загрузка, момент монотонных часов, тип, источник) записывается один раз.
        val key = "$device:${boot ?: "?"}:$elapsed:${type.name}:$source"
        Diary.addPhoneEvent(device, boot, elapsed, utc, zone, type, source, interactive, locked, key)
    }

    /** Номер загрузки устройства: читается один раз за процесс (в пределах процесса он не меняется). */
    private fun bootCount(context: Context): Int? {
        if (bootRead) return bootCount
        bootCount = try {
            SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT)
        } catch (e: Exception) {
            Log.w(TAG, "Номер загрузки недоступен", e)
            null
        }
        bootRead = true
        return bootCount
    }
}
