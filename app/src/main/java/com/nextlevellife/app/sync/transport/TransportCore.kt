package com.nextlevellife.app.sync.transport

import com.nextlevellife.app.sync.PairedPeer
import com.nextlevellife.app.sync.SyncChannel
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Связанный телефон вместе с ключом пары (ключ наружу из транспорта не выходит).
 */
internal class PeerKey(val deviceId: String, val name: String, val key: ByteArray)

/** То, что ядру транспорта нужно от приложения (на Android — [SyncTransport] и [PairStore]). */
internal interface TransportHost {
    fun myDeviceId(): String
    fun myName(): String
    fun pairedPeer(): PeerKey?

    /** Сопряжение завершено (обе роли). [address] — где второй телефон слушает, если известно. */
    fun onPaired(peer: PairedPeer, key: ByteArray, address: InetSocketAddress?)

    /** Второй телефон сообщил о разрыве связи. */
    fun onUnpairedByPeer()

    /** Обработчик входящих каналов; null — входящие отклоняются. */
    fun incomingHandler(): ((SyncChannel) -> Unit)?

    fun log(message: String, error: Throwable? = null) {}
}

/** Итог проверки/сопряжения для экрана. */
internal enum class PairOutcome { OK, NOT_FOUND, EXPIRED, BUSY, FAILED }

/**
 * Протокол транспорта без Android: обслуживание входящего сокета (сессия или сопряжение) и исходящие
 * подключения. Потоки, NSD, хранение пары и сеть — снаружи ([SyncTransport]). Все тайм-ауты — здесь.
 */
internal class TransportCore(
    private val host: TransportHost,
    val tokens: PairingTokens = PairingTokens(),
    private val random: SecureRandom = SecureRandom(),
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {
    val slot = ChannelSlot { host.myDeviceId() }

    // ---------- Входящие ----------

    /**
     * Обслужить принятое соединение на текущем (фоновом) потоке. Для канала SYNC вызывает обработчик
     * входящих и возвращается, когда он закончит; сокет закрывается всегда.
     */
    fun serve(socket: Socket) {
        var handedOver = false
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
                Wire.KIND_SESSION -> handedOver = serveSession(socket, input, output, transcript, rest)
                Wire.KIND_PAIR -> servePairing(socket, input, output, transcript, rest)
                else -> throw Wire.HandshakeException("Неизвестный вид соединения")
            }
        } catch (e: IOException) {
            host.log("Входящее соединение отклонено: ${e.message}")
        } catch (e: RuntimeException) {
            host.log("Сбой входящего соединения", e)
        } finally {
            if (!handedOver) runCatching { socket.close() }
        }
    }

    /** true — канал отдан обработчику и уже закрыт им (или его закрытием). */
    private fun serveSession(
        socket: Socket,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        transcript: ByteArrayOutputStream,
        rest: ByteArray,
    ): Boolean {
        val peer = host.pairedPeer()
        var ticket: ChannelSlot.Incoming? = null
        var handler: ((SyncChannel) -> Unit)? = null
        val keys = try {
            SessionHandshake.server(
                input, output, transcript, rest, host.myDeviceId(),
                keyFor = { id -> if (peer != null && peer.deviceId == id) peer.key else null },
                acceptAfterConfirm = { peerId, purpose ->
                    when (purpose) {
                        SessionHandshake.Purpose.PING, SessionHandshake.Purpose.UNPAIR -> true
                        SessionHandshake.Purpose.SYNC -> {
                            handler = host.incomingHandler()
                            if (handler == null) {
                                false
                            } else {
                                ticket = slot.acceptIncoming(peerId)
                                ticket != null
                            }
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
        when (keys.purpose) {
            SessionHandshake.Purpose.PING -> return false
            SessionHandshake.Purpose.UNPAIR -> {
                host.onUnpairedByPeer()
                return false
            }
            SessionHandshake.Purpose.SYNC -> {
                val t = ticket!!
                val channel = SecureChannel(input, output, keys.sendKey, keys.recvKey, keys.peerDeviceId, initiator = false) {
                    runCatching { socket.close() }
                    slot.release(t)
                }
                try {
                    handler!!.invoke(channel)
                } catch (e: Exception) {
                    host.log("Обработчик входящего канала упал", e)
                    channel.close()
                }
                // Обработчик сам закрывает канал; если он вернул управление и держит канал — это его дело.
                return true
            }
        }
    }

    private fun servePairing(
        socket: Socket,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        transcript: ByteArrayOutputStream,
        rest: ByteArray,
    ) {
        val result = PairingHandshake.server(
            input, output, transcript, rest,
            host.myDeviceId(), host.myName(), tokens,
            alreadyPaired = host.pairedPeer() != null,
            random = random,
        )
        // Адрес B неизвестен (его исходящий порт не порт сервера) — B найдётся через NSD.
        host.onPaired(PairedPeer(result.peerDeviceId, result.peerName), result.pairKey, null)
        host.log("Сопряжение с ${result.peerName} завершено")
        runCatching { socket.close() }
    }

    // ---------- Исходящие ----------

    /**
     * Подключиться к [address] и открыть канал назначения [purpose]. null — слот занят (только для SYNC)
     * или встречное подключение победило. Сбой связи или рукопожатия — [IOException].
     */
    fun connect(address: InetSocketAddress, purpose: SessionHandshake.Purpose, timeoutMs: Int = connectTimeoutMs): SecureChannel? {
        val peer = host.pairedPeer() ?: return null
        val socket = Socket()
        val attempt = if (purpose == SessionHandshake.Purpose.SYNC) {
            slot.beginOutgoing { runCatching { socket.close() } } ?: return null
        } else {
            null
        }
        var ok = false
        try {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            socket.connect(address, timeoutMs.coerceIn(1, connectTimeoutMs))
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val keys = SessionHandshake.client(input, output, host.myDeviceId(), peer.deviceId, peer.key, purpose, random)
            if (attempt != null && !slot.outgoingEstablished(attempt)) return null
            val channel = SecureChannel(input, output, keys.sendKey, keys.recvKey, keys.peerDeviceId, initiator = true) {
                runCatching { socket.close() }
                if (attempt != null) slot.release(attempt)
            }
            ok = true
            return channel
        } catch (e: IOException) {
            if (attempt?.cancelled == true) return null
            throw e
        } finally {
            if (!ok) {
                runCatching { socket.close() }
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
     * Сторона B: подключиться к A по [address] и пройти сопряжение по [invite]. При успехе пара уже
     * передана в [TransportHost.onPaired] с адресом A.
     */
    fun pair(address: InetSocketAddress, invite: PairingInvite, timeoutMs: Int = connectTimeoutMs): PairOutcome {
        if (host.pairedPeer() != null) return PairOutcome.BUSY
        Socket().use { socket ->
            return try {
                socket.soTimeout = readTimeoutMs
                socket.tcpNoDelay = true
                socket.connect(address, timeoutMs.coerceIn(1, connectTimeoutMs))
                val input = BufferedInputStream(socket.getInputStream())
                val result = PairingHandshake.client(input, socket.getOutputStream(), host.myDeviceId(), host.myName(), invite, random)
                host.onPaired(PairedPeer(result.peerDeviceId, result.peerName), result.pairKey, address)
                PairOutcome.OK
            } catch (e: PairingHandshake.InviteRejected) {
                if (e.busy) PairOutcome.BUSY else PairOutcome.EXPIRED
            } catch (e: Wire.HandshakeException) {
                // Неверный токен A не объясняет: просто закрывает соединение.
                host.log("Сопряжение не удалось: ${e.message}")
                PairOutcome.EXPIRED
            } catch (e: IOException) {
                host.log("Сопряжение: нет связи: ${e.message}")
                PairOutcome.NOT_FOUND
            }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
