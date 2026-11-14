package com.nextlevellife.app.sync

import android.os.SystemClock
import android.util.Log
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Состояние обмена для экранов. */
data class SyncUiState(
    /** Связанный телефон; null — пары нет. */
    val peer: PairedPeer? = null,
    val running: Boolean = false,
    /** Последний удачный обмен (мс эпохи). */
    val lastSuccessMs: Long? = null,
    /** Последняя попытка не нашла второй телефон в сети. */
    val notFound: Boolean = false,
    /** Ошибка последней попытки, понятная человеку; null — всё в порядке. */
    val error: String? = null,
)

/**
 * Обмен со вторым телефоном (объект процесса). Транспорт подключает [attach]; без него телефон работает
 * как несвязанный. Один обмен за раз: исходящий ([requestSync], неблокирующий) и входящие каналы обслуживает
 * один и тот же [SyncSession]. После неудачи повтор — не раньше чем через [RETRY_MS] (по elapsedRealtime),
 * кроме явной просьбы пользователя.
 *
 * Встречные соединения (оба телефона позвали друг друга сразу): телефон с большим device_id отклоняет входящее
 * и продолжает своё, с меньшим — ждёт конца своего (оно оборвётся отказом) и обслуживает входящее.
 */
object SyncEngine {
    private const val TAG = "SyncEngine"

    /** Повтор после неудачи — не раньше. */
    const val RETRY_MS = 2 * 60_000L

    /** Сколько ждать соединения со вторым телефоном. */
    private const val OPEN_TIMEOUT_MS = 5_000L

    /** Сколько входящий канал ждёт конца своего исходящего обмена. */
    private const val INCOMING_WAIT_S = 15L

    @Volatile private var link: SyncLink? = null
    private val lock = ReentrantLock()
    private val queued = AtomicBoolean(false)
    @Volatile private var lastFailureElapsed: Long? = null
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sync").apply { isDaemon = true } }

    /** Хранилище обмена: на дневнике. */
    private val store by lazy { DiarySyncStore() }

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state

    /** Подключить транспорт: входящие каналы, слушатель сопряжения, текущая пара. Store должен быть готов. */
    fun attach(link: SyncLink) {
        this.link = link
        link.setIncomingHandler(::onIncoming)
        link.setPairingListener(::onPairingChanged)
        onPairingChanged(link.peer())
    }

    /** Связанный телефон по транспорту; null — пары нет или транспорт не подключён. */
    fun peer(): PairedPeer? = link?.peer()

    /**
     * Попросить обмен ([reason] — для лога). Не блокирует; уже идёт или стоит в очереди — ничего не делает.
     * После неудачи — не раньше [RETRY_MS], если не [force] (кнопка «Синхронизировать сейчас»).
     */
    fun requestSync(reason: String, force: Boolean = false) {
        val l = link ?: return
        if (l.peer() == null) return
        val failed = lastFailureElapsed
        if (!force && failed != null && SystemClock.elapsedRealtime() - failed < RETRY_MS) return
        if (!queued.compareAndSet(false, true)) return
        executor.execute {
            queued.set(false)
            runOutgoing(reason)
        }
    }

    private fun runOutgoing(reason: String) {
        val l = link ?: return
        val peer = l.peer() ?: return
        if (!lock.tryLock()) return // идёт входящий обмен — это тот же обмен
        try {
            _state.update { it.copy(peer = peer, running = true) }
            val channel = try {
                l.open(OPEN_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось открыть канал", e)
                null
            }
            if (channel == null) {
                lastFailureElapsed = SystemClock.elapsedRealtime()
                _state.update { it.copy(running = false, notFound = true) }
                return
            }
            Log.i(TAG, "Обмен ($reason)")
            channel.use { runSession(it, peer) }
        } finally {
            _state.update { it.copy(running = false) }
            lock.unlock()
        }
    }

    private fun onIncoming(channel: SyncChannel) {
        channel.use { ch ->
            val peer = link?.peer()
            if (peer == null || ch.peerDeviceId != peer.deviceId) {
                Log.w(TAG, "Входящий канал не от связанного телефона")
                return
            }
            val me = runCatching { Ledger.deviceId() }.getOrNull() ?: return
            val acquired = if (me > ch.peerDeviceId) lock.tryLock() else lock.tryLock(INCOMING_WAIT_S, TimeUnit.SECONDS)
            if (!acquired) return
            try {
                _state.update { it.copy(peer = peer, running = true) }
                runSession(ch, peer)
            } finally {
                _state.update { it.copy(running = false) }
                lock.unlock()
            }
        }
    }

    private fun runSession(channel: SyncChannel, peer: PairedPeer) {
        try {
            if (Ledger.peerId() != peer.deviceId) onPairingChanged(peer)
            val outcome = SyncSession(store, channel).run()
            // Обмен дошёл до конца: все страницы журнала второго телефона приняты — копия полная.
            Ledger.markPeerCopyComplete(peer.deviceId)
            lastFailureElapsed = null
            // Второй телефон получил здешние настройки: смена источника активности, сделанная здесь, вступает в силу.
            com.nextlevellife.app.data.SharedSettings.confirmAfterSync()
            _state.update { it.copy(lastSuccessMs = System.currentTimeMillis(), notFound = false, error = null) }
            if (outcome.receivedMs > 0 || outcome.givenWalletMs > 0 || outcome.givenLimitMs > 0) {
                Log.i(TAG, "Перенос: получено ${outcome.receivedMs}, отдано ${outcome.givenWalletMs} мин. и ${outcome.givenLimitMs} лимита (мс)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Обмен не удался", e)
            lastFailureElapsed = SystemClock.elapsedRealtime()
            _state.update { it.copy(notFound = false, error = errorText(e)) }
        }
    }

    /** Связали, разорвали или сменили второй телефон. */
    private fun onPairingChanged(peer: PairedPeer?) {
        try {
            val old = Ledger.peerId()
            if (peer == null) {
                if (old != null) store.unpair()
                Ledger.setPeer(null)
            } else if (old != peer.deviceId) {
                store.forgetOtherPeers(peer.deviceId)
                Ledger.setPeer(peer.deviceId)
                Store.notifyChanged()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать смену пары", e)
        }
        _state.update { SyncUiState(peer = peer, lastSuccessMs = if (peer?.deviceId == it.peer?.deviceId) it.lastSuccessMs else null) }
        if (peer != null) requestSync("сопряжение", force = true)
    }

    private fun errorText(e: Exception): String = when (e) {
        is SyncProtocolException -> "Телефоны не договорились: ${e.message}"
        is java.io.IOException -> "Связь прервалась"
        else -> "Ошибка обмена: ${e.message ?: e.javaClass.simpleName}"
    }
}
