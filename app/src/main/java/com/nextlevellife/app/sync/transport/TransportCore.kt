package com.nextlevellife.app.sync.transport

import com.nextlevellife.app.sync.PairedPeer
import com.nextlevellife.app.sync.SyncChannel
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Semaphore

/**
 * Связанный телефон вместе с ключом пары (ключ наружу из транспорта не выходит).
 */
internal class PeerKey(val deviceId: String, val name: String, val key: ByteArray)

/** То, что ядру транспорта нужно от приложения (на Android — [SyncTransport] и [PairStore]). */
internal interface TransportHost {
    fun myDeviceId(): String
    fun myName(): String
    fun pairedPeer(): PeerKey?

    /**
     * Сопряжение завершено (обе роли). [address] — где второй телефон слушает, если известно.
     * Сразу после вызова ядро закрывает всё, что было открыто по прежнему сопряжению.
     */
    fun onPaired(peer: PairedPeer, key: ByteArray, address: InetSocketAddress?)

    /** Второй телефон сообщил о разрыве связи: забыть пару (ядро затем закроет каналы). */
    fun onUnpairedByPeer()

    /** Обработчик входящих каналов; null — входящие отклоняются. */
    fun incomingHandler(): ((SyncChannel) -> Unit)?

    fun log(message: String, error: Throwable? = null) {}
}

/** Итог проверки/сопряжения для экрана. */
internal enum class PairOutcome { OK, NOT_FOUND, EXPIRED, BUSY, FAILED }

/** Отмена сопряжения с экрана: закрывает сокет попытки, результат не сохраняется. */
internal class PairCancel {
    @Volatile var cancelled = false
        private set
    private var socket: Closeable? = null

    @Synchronized
    fun cancel() {
        cancelled = true
        runCatching { socket?.close() }
    }

    @Synchronized
    internal fun attach(s: Closeable) {
        socket = s
        if (cancelled) runCatching { s.close() }
    }
}

/**
 * Протокол транспорта без Android: обслуживание входящего сокета (сессия или сопряжение) и исходящие
 * подключения. Потоки, NSD, хранение пары и сеть — снаружи ([SyncTransport]).
 *
 * Сроки: рукопожатие целиком — не дольше [handshakeTimeoutMs] (сторож [Watchdog] закрывает сокет, даже если
 * клиент шлёт по байту); каждая операция канала — не дольше [readTimeoutMs]. Неаутентифицированных
 * рукопожатий одновременно не больше [maxHandshakes], лишние соединения сразу закрываются.
 *
 * Поколение сопряжения: [invalidatePairing] (разрыв, новое сопряжение) закрывает все каналы и начатые
 * рукопожатия прежнего ключа; соединение принимается и канал отдаётся наружу, только если поколение
 * не сменилось с начала рукопожатия.
 */
internal class TransportCore(
    private val host: TransportHost,
    val tokens: PairingTokens = PairingTokens(),
    private val random: SecureRandom = SecureRandom(),
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val handshakeTimeoutMs: Int = HANDSHAKE_TIMEOUT_MS,
    private val maxHandshakes: Int = MAX_HANDSHAKES,
) {
    val slot = ChannelSlot { host.myDeviceId() }

    private val handshakes = Semaphore(maxHandshakes)
    private val genLock = Any()
    private var generation = 0L

    /** Открытые сессионные сокеты (рукопожатия и каналы) с поколением, в котором они начаты. */
    private val live = HashMap<Closeable, Long>()

    /** Закрыть всё, что открыто по прежнему ключу пары. Звать после изменения пары в хранилище. */
    fun invalidatePairing() {
        val toClose: List<Closeable>
        synchronized(genLock) {
            generation++
            toClose = live.keys.toList()
            live.clear()
        }
        toClose.forEach { runCatching { it.close() } }
    }

    /** Зарегистрировать сокет в текущем поколении; вернуть это поколение. */
    private fun track(c: Closeable): Long = synchronized(genLock) {
        live[c] = generation
        generation
    }

    private fun untrack(c: Closeable) = synchronized(genLock) { live.remove(c) }

    /** Поколение не сменилось и сокет ещё не закрыт разрывом. */
    private fun stillValid(c: Closeable, gen: Long): Boolean = synchronized(genLock) { generation == gen && live[c] == gen }

    // ---------- Входящие ----------

    /**
     * Обслужить принятое соединение на текущем (фоновом) потоке. Для канала SYNC вызывает обработчик
     * входящих и возвращается, когда он закончит; сокет закрывается всегда.
     */
    fun serve(socket: Socket) {
        if (!handshakes.tryAcquire()) {
            host.log("Слишком много одновременных подключений — закрыто")
            runCatching { socket.close() }
            return
        }
        var permitHeld = true
        val releasePermit = {
            if (permitHeld) {
                permitHeld = false
                handshakes.release()
            }
        }
        var handedOver = false
        val guard = Watchdog.arm(handshakeTimeoutMs.toLong(), socket)
        try {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val transcript = ByteArrayOutputStream()
            val hello = Wire.readFrame(input, transcript)
            if (hello.size < 6) throw Wire.HandshakeException("Короткий кадр")
            val magic = ((hello[0].toInt() and 0xFF) shl 24) or ((hello[1].toInt() and 0xFF) shl 16) or
                ((hello[2].toInt() and 0xFF) shl 8) or (hello[3].toInt() and 0xFF)
            if (magic != Wire.MAGIC || hello[4].toInt() != Wire.PROTOCOL_VERSION) throw Wire.HandshakeException("Чужой протокол")
            val rest = hello.copyOfRange(6, hello.size)
            when (hello[5].toInt()) {
                Wire.KIND_SESSION -> handedOver = serveSession(socket, input, output, transcript, rest, guard, releasePermit)
                Wire.KIND_PAIR -> servePairing(input, output, transcript, rest, guard)
                else -> throw Wire.HandshakeException("Неизвестный вид соединения")
            }
        } catch (e: IOException) {
            host.log("Входящее соединение отклонено: ${if (guard.fired) "истёк срок рукопожатия" else e.message}")
        } catch (e: RuntimeException) {
            host.log("Сбой входящего соединения", e)
        } finally {
            guard.disarm()
            releasePermit()
            if (!handedOver) {
                untrack(socket)
                runCatching { socket.close() }
            }
        }
    }

    /** true — канал отдан обработчику. */
    private fun serveSession(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        transcript: ByteArrayOutputStream,
        rest: ByteArray,
        guard: Watchdog.Guard,
        releasePermit: () -> Unit,
    ): Boolean {
        // Сначала регистрация в поколении, потом чтение пары: разрыв после этого закроет и этот сокет.
        val gen = track(socket)
        val peer = host.pairedPeer()
        var ticket: ChannelSlot.Incoming? = null
        var handler: ((SyncChannel) -> Unit)? = null
        val keys = try {
            SessionHandshake.server(
                input, output, transcript, rest, host.myDeviceId(),
                keyFor = { id -> if (peer != null && peer.deviceId == id) peer.key else null },
                acceptAfterConfirm = { peerId, purpose ->
                    when {
                        !stillValid(socket, gen) -> false
                        purpose != SessionHandshake.Purpose.SYNC -> true
                        else -> {
                            handler = host.incomingHandler()
                            if (handler != null) ticket = slot.acceptIncoming(peerId)
                            ticket != null
                        }
                    }
                },
                random = random,
            )
        } catch (e: IOException) {
            // Слот заняли, а подтверждение не ушло (обрыв) — освободить.
            ticket?.let { slot.release(it) }
            throw e
        }
        guard.disarm()
        when (keys.purpose) {
            SessionHandshake.Purpose.PING -> return false
            SessionHandshake.Purpose.UNPAIR -> {
                host.onUnpairedByPeer()
                invalidatePairing()
                return false
            }
            SessionHandshake.Purpose.SYNC -> {
                val t = ticket!!
                val channel = SecureChannel(
                    input, output, keys.sendKey, keys.recvKey, keys.peerDeviceId, initiator = false,
                    operationTimeoutMs = readTimeoutMs.toLong(),
                ) {
                    runCatching { socket.close() }
                    untrack(socket)
                    slot.release(t)
                }
                if (!stillValid(socket, gen)) {
                    channel.close()
                    throw Wire.HandshakeException("Сопряжение сменилось")
                }
                // Рукопожатие закончено: место неаутентифицированных освобождается до работы обработчика.
                releasePermit()
                try {
                    handler!!.invoke(channel)
                } catch (e: Exception) {
                    host.log("Обработчик входящего канала упал", e)
                    channel.close()
                }
                return true
            }
        }
    }

    private fun servePairing(
        input: InputStream,
        output: OutputStream,
        transcript: ByteArrayOutputStream,
        rest: ByteArray,
        guard: Watchdog.Guard,
    ) {
        val result = PairingHandshake.server(
            input, output, transcript, rest,
            host.myDeviceId(), host.myName(), tokens,
            alreadyPaired = host.pairedPeer() != null,
            random = random,
        )
        guard.disarm()
        // Адрес B неизвестен (его исходящий порт не порт сервера) — B найдётся через NSD.
        host.onPaired(PairedPeer(result.peerDeviceId, result.peerName), result.pairKey, null)
        // Сначала новая пара в хранилище, потом закрыть всё начатое раньше: канала по старому ключу не останется.
        invalidatePairing()
        host.log("Сопряжение с ${result.peerName} завершено")
    }

    // ---------- Исходящие ----------

    /**
     * Подключиться к [address] и открыть канал назначения [purpose]; подключение и рукопожатие вместе —
     * не дольше [timeoutMs] (сторож закрывает сокет). null — слот занят (только для SYNC), встречное
     * подключение победило или сопряжение сменилось. Сбой связи или рукопожатия — [IOException].
     */
    fun connect(address: InetSocketAddress, purpose: SessionHandshake.Purpose, timeoutMs: Int = connectTimeoutMs): SecureChannel? {
        if (timeoutMs <= 0) throw SocketTimeoutException("Нет времени на подключение")
        val socket = Socket()
        val gen = track(socket)
        val peer = host.pairedPeer()
        if (peer == null) {
            untrack(socket)
            return null
        }
        val attempt = if (purpose == SessionHandshake.Purpose.SYNC) {
            slot.beginOutgoing { runCatching { socket.close() } } ?: run {
                untrack(socket)
                return null
            }
        } else {
            null
        }
        var ok = false
        val guard = Watchdog.arm(minOf(timeoutMs, handshakeTimeoutMs).toLong(), socket)
        try {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            socket.connect(address, minOf(timeoutMs, connectTimeoutMs))
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val keys = SessionHandshake.client(input, output, host.myDeviceId(), peer.deviceId, peer.key, purpose, random)
            guard.disarm()
            if (guard.fired) throw SocketTimeoutException("Истёк срок подключения")
            if (attempt != null && !slot.outgoingEstablished(attempt)) return null
            val channel = SecureChannel(
                input, output, keys.sendKey, keys.recvKey, keys.peerDeviceId, initiator = true,
                operationTimeoutMs = readTimeoutMs.toLong(),
            ) {
                runCatching { socket.close() }
                untrack(socket)
                if (attempt != null) slot.release(attempt)
            }
            // Перед отдачей наружу: пару не разорвали и не заменили, пока шло рукопожатие.
            if (!stillValid(socket, gen)) {
                channel.close()
                return null
            }
            ok = true
            return channel
        } catch (e: IOException) {
            if (attempt?.cancelled == true || !stillValid(socket, gen)) return null
            if (guard.fired) throw SocketTimeoutException("Истёк срок подключения")
            throw e
        } finally {
            guard.disarm()
            if (!ok) {
                runCatching { socket.close() }
                untrack(socket)
                if (attempt != null) slot.release(attempt)
            }
        }
    }

    /** Проверка связи: рукопожатие PING и сразу закрытие. */
    fun ping(address: InetSocketAddress, timeoutMs: Int = connectTimeoutMs): Boolean = try {
        connect(address, SessionHandshake.Purpose.PING, timeoutMs)?.close() != null
    } catch (e: IOException) {
        false
    }

    /** Сообщить второму телефону о разрыве связи. true — он подтвердил. */
    fun notifyUnpair(address: InetSocketAddress, timeoutMs: Int = connectTimeoutMs): Boolean = try {
        connect(address, SessionHandshake.Purpose.UNPAIR, timeoutMs)?.close() != null
    } catch (e: IOException) {
        false
    }

    /**
     * Сторона B: подключиться к A по [address] и пройти сопряжение по [invite] не дольше [timeoutMs].
     * При успехе пара уже передана в [TransportHost.onPaired] с адресом A. Отменённая попытка ([cancel])
     * ничего не сохраняет.
     */
    fun pair(
        address: InetSocketAddress,
        invite: PairingInvite,
        timeoutMs: Int = connectTimeoutMs,
        cancel: PairCancel? = null,
    ): PairOutcome {
        if (host.pairedPeer() != null) return PairOutcome.BUSY
        if (timeoutMs <= 0) return PairOutcome.NOT_FOUND
        Socket().use { socket ->
            cancel?.attach(socket)
            val guard = Watchdog.arm(minOf(timeoutMs, handshakeTimeoutMs).toLong(), socket)
            return try {
                socket.soTimeout = readTimeoutMs
                socket.tcpNoDelay = true
                socket.connect(address, minOf(timeoutMs, connectTimeoutMs))
                val input = BufferedInputStream(socket.getInputStream())
                val result = PairingHandshake.client(input, socket.getOutputStream(), host.myDeviceId(), host.myName(), invite, random)
                guard.disarm()
                synchronized(cancel ?: this) {
                    if (cancel?.cancelled == true) return PairOutcome.FAILED
                    host.onPaired(PairedPeer(result.peerDeviceId, result.peerName), result.pairKey, address)
                    invalidatePairing()
                }
                PairOutcome.OK
            } catch (e: PairingHandshake.InviteRejected) {
                if (e.busy) PairOutcome.BUSY else PairOutcome.EXPIRED
            } catch (e: Wire.HandshakeException) {
                // Неверный токен A не объясняет: просто закрывает соединение.
                host.log("Сопряжение не удалось: ${e.message}")
                if (cancel?.cancelled == true) PairOutcome.FAILED else PairOutcome.EXPIRED
            } catch (e: IOException) {
                host.log("Сопряжение: нет связи: ${e.message}")
                if (cancel?.cancelled == true) PairOutcome.FAILED else PairOutcome.NOT_FOUND
            } finally {
                guard.disarm()
            }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 15_000
        const val HANDSHAKE_TIMEOUT_MS = 8_000
        const val MAX_HANDSHAKES = 4
    }
}
