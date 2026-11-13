package com.nextlevellife.app.sleep

import android.content.Context
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.util.Log
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.TrustedClock
import java.util.concurrent.Executors

/**
 * Запись событий экрана для ночного перерыва. События приходят от службы блокировки
 * ([com.nextlevellife.app.block.BlockerService]) на главном потоке; время (доверенное, [TrustedClock])
 * снимается сразу, а вставка в базу идёт на отдельном однопоточном исполнителе — порядок событий
 * сохраняется, главный поток не ждёт SQLite. Ошибки записи только логируются: блокировку они не трогают.
 */
object PhoneEvents {
    private const val TAG = "PhoneEvents"

    /** Значения phone_events.source. */
    const val SOURCE_RECEIVER = "receiver"
    const val SOURCE_CONNECT = "connect"
    const val SOURCE_SHUTDOWN = "shutdown"
    const val SOURCE_RESTORE = "restore"

    /** Значения observation_gaps.reason. */
    const val GAP_SERVICE_RESTART = "service_restart"
    const val GAP_RESTORE = "restore"

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "PhoneEvents").apply { isDaemon = true } }

    /**
     * Служба подключена и события пишутся. false — наблюдения сейчас нет: всё после последнего события
     * считается пропуском, а ночи не записываются как окончательные ([SleepRepository]).
     * Становится true только после того, как записаны состояние при подключении и пропуск.
     */
    @Volatile var observing: Boolean = false
        private set

    @Volatile private var bootCount: Int? = null
    @Volatile private var bootRead = false

    /** Событие экрана из приёмника службы. Вызывать с главного потока: время снимается здесь. */
    fun record(type: PhoneEventType, source: String, interactive: Boolean?, locked: Boolean?) {
        val utc = TrustedClock.nowMs()
        val zone = TrustedClock.zone().id
        val elapsed = SystemClock.elapsedRealtime()
        executor.execute {
            try {
                insert(Ledger.deviceId(), utc, zone, elapsed, type, source, interactive, locked)
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось записать событие $type", e)
            }
        }
    }

    /**
     * Служба подключилась: записать пропуск наблюдения от последнего известного события (если он был,
     * [SleepMath.connectGap]) и текущее состояние экрана событием STATE с source = connect.
     * Прошлое не выдумывается: до этого момента состояние берётся только из записанных событий.
     */
    fun onServiceConnected(context: Context, interactive: Boolean?, locked: Boolean?) {
        val app = context.applicationContext
        val utc = TrustedClock.nowMs()
        val zone = TrustedClock.zone().id
        val elapsed = SystemClock.elapsedRealtime()
        executor.execute {
            try {
                val boot = bootCount(app)
                val device = Ledger.deviceId()
                synchronized(Diary) {
                    val last = Diary.lastPhoneEvent(device)
                    SleepMath.connectGap(last, utc, boot)?.let {
                        Diary.addObservationGap(device, it.startMs, it.endMs, GAP_SERVICE_RESTART)
                        Log.i(TAG, "Пропуск наблюдения ${(it.endMs - it.startMs) / 60_000} мин")
                    }
                    insert(device, utc, zone, elapsed, PhoneEventType.STATE, SOURCE_CONNECT, interactive, locked)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось записать подключение службы", e)
            } finally {
                observing = true
            }
        }
    }

    /** Служба остановлена: дальше наблюдения нет. */
    fun onServiceDisconnected() {
        observing = false
    }

    /**
     * База подменена восстановлением копии: события после снимка копии потеряны. Записывается пропуск
     * от последнего события в восстановленной базе до сейчас и состояние «пользуются» (восстановление
     * запускают из приложения — телефон в руках). Вызывать под блокировкой [Diary] или без неё.
     */
    fun onDatabaseReplaced() {
        val utc = TrustedClock.nowMs()
        val zone = TrustedClock.zone().id
        val elapsed = SystemClock.elapsedRealtime()
        executor.execute {
            try {
                val device = Ledger.deviceId()
                synchronized(Diary) {
                    val last = Diary.lastPhoneEvent(device)
                    if (last != null && utc > last.utcMs) Diary.addObservationGap(device, last.utcMs, utc, GAP_RESTORE)
                    insert(device, utc, zone, elapsed, PhoneEventType.STATE, SOURCE_RESTORE, interactive = true, locked = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось отметить восстановление копии", e)
            }
        }
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
