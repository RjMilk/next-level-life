package com.nextlevellife.app.sync.transport

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Одноразовый токен сопряжения на показывающем QR телефоне. Живёт [ttlMs] (5 минут) и только пока его
 * не отозвали ([cancel] — экран закрыт). После успешного сопряжения или истечения срока недействителен.
 * Время — монотонные часы ([clock], по умолчанию elapsedRealtime-подобный System.nanoTime).
 */
internal class PairingTokens(
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = TTL_MS,
) {
    private class Active(val token: ByteArray, val expiresAt: Long, var failures: Int = 0)

    private var active: Active? = null

    /** Новый токен; прежний отзывается. */
    @Synchronized
    fun issue(): ByteArray {
        val token = Wire.random(random, PairingInvite.TOKEN_BYTES)
        active = Active(token, clock() + ttlMs)
        return token.copyOf()
    }

    @Synchronized
    fun cancel() {
        active = null
    }

    /** Сколько осталось жить текущему токену, мс; 0 — токена нет или он истёк. */
    @Synchronized
    fun remainingMs(): Long {
        val a = active ?: return 0L
        return (a.expiresAt - clock()).coerceAtLeast(0L)
    }

    /** Действующий токен (копия) или null. Истёкший сразу забывается. */
    @Synchronized
    fun current(): ByteArray? {
        val a = active ?: return null
        if (clock() >= a.expiresAt) {
            active = null
            return null
        }
        return a.token.copyOf()
    }

    /**
     * Израсходовать токен после успешного подтверждения: true — он ещё был действующим и тем же самым
     * (параллельная попытка уже не пройдёт).
     */
    @Synchronized
    fun consume(token: ByteArray): Boolean {
        val a = active ?: return false
        if (clock() >= a.expiresAt || !Hkdf.constantTimeEquals(a.token, token)) return false
        active = null
        return true
    }

    /** Неверное подтверждение: после [MAX_FAILURES] токен отзывается — подбор по сети бессмысленен. */
    @Synchronized
    fun registerFailure(token: ByteArray) {
        val a = active ?: return
        if (!Hkdf.constantTimeEquals(a.token, token)) return
        a.failures++
        if (a.failures >= MAX_FAILURES) active = null
    }

    companion object {
        const val TTL_MS = 5 * 60_000L
        const val MAX_FAILURES = 3
    }
}

/** Итог сопряжения на одной стороне: второй телефон и общий постоянный ключ пары (32 байта). */
internal class PairResult(val peerDeviceId: String, val peerName: String, val pairKey: ByteArray)

/**
 * Рукопожатие сопряжения по токену из QR. Телефон A показал QR и ждёт (сервер), телефон B отсканировал и
 * подключился (клиент).
 *
 * 1. B → A: MAGIC, версия, KIND_PAIR, device_id B, имя B, nonceB.
 * 2. A → B: статус ([STATUS_OK] / [STATUS_NO_INVITE] — нет действующего QR / [STATUS_BUSY] — уже связан);
 *    при OK: device_id A, имя A, nonceA. B проверяет, что device_id совпадает с QR.
 * 3. PRK = HKDF-Extract(nonceB ‖ nonceA, токен); info = "nll-pair" ‖ версия ‖ id B ‖ id A ‖ SHA-256(транскрипт);
 *    ключ подтверждения = Expand(info ‖ "confirm"), постоянный ключ пары = Expand(info ‖ "pair-key").
 * 4. B → A: HMAC(confirm, "client" ‖ хеш); A проверяет и расходует токен. A → B: HMAC(confirm, "server" ‖ хеш);
 *    B проверяет. Так обе стороны доказывают знание токена, а сам токен по сети не передаётся.
 */
internal object PairingHandshake {
    const val STATUS_OK = 0
    const val STATUS_NO_INVITE = 1
    const val STATUS_BUSY = 2

    /** A ответил, что QR больше не действует (или его не было). */
    class InviteRejected(val busy: Boolean) : IOException(if (busy) "Телефон уже связан" else "QR устарел")

    /** Сторона B (отсканировала QR). */
    fun client(
        input: InputStream,
        output: OutputStream,
        myId: String,
        myName: String,
        invite: PairingInvite,
        random: SecureRandom,
    ): PairResult {
        val transcript = ByteArrayOutputStream()
        val nonceB = Wire.random(random, Wire.NONCE_BYTES)
        Wire.writeFrame(
            output,
            Wire.build {
                writeInt(Wire.MAGIC)
                writeByte(Wire.PROTOCOL_VERSION)
                writeByte(Wire.KIND_PAIR)
                writeUTF(myId)
                writeUTF(myName)
                write(nonceB)
            },
            transcript,
        )
        val reply = Wire.readFrame(input, transcript)
        val status = reply.firstOrNull()?.toInt() ?: throw Wire.HandshakeException("Пустой ответ")
        if (status == STATUS_NO_INVITE || status == STATUS_BUSY) throw InviteRejected(status == STATUS_BUSY)
        val (serverId, serverName, nonceA) = Wire.parse(reply) {
            if (readUnsignedByte() != STATUS_OK) throw Wire.HandshakeException("Неизвестный статус")
            with(Wire) { Triple(readDeviceId(), readName(), readBytes(Wire.NONCE_BYTES)) }
        }
        if (serverId != invite.deviceId) throw Wire.HandshakeException("Не тот телефон")
        val th = Hkdf.sha256(transcript.toByteArray())
        val keys = derive(invite.token, nonceB, nonceA, myId, serverId, th)
        Wire.writeFrame(output, Hkdf.hmac(keys.first, Wire.concat(Wire.label("client"), th)), null)
        val serverMac = Wire.readFrame(input, null)
        val expected = Hkdf.hmac(keys.first, Wire.concat(Wire.label("server"), th))
        if (!Hkdf.constantTimeEquals(serverMac, expected)) throw Wire.HandshakeException("Неверное подтверждение")
        return PairResult(serverId, serverName, keys.second)
    }

    /**
     * Сторона A (показывает QR). [rest] — тело первого кадра после MAGIC/версии/вида (кадр уже в [transcript]).
     * [alreadyPaired] — у A уже есть пара: отказ со статусом BUSY.
     */
    fun server(
        input: InputStream,
        output: OutputStream,
        transcript: ByteArrayOutputStream,
        rest: ByteArray,
        myId: String,
        myName: String,
        tokens: PairingTokens,
        alreadyPaired: Boolean,
        random: SecureRandom,
    ): PairResult {
        val (clientId, clientName, nonceB) = Wire.parse(rest) {
            with(Wire) { Triple(readDeviceId(), readName(), readBytes(Wire.NONCE_BYTES)) }
        }
        if (alreadyPaired) {
            Wire.writeFrame(output, byteArrayOf(STATUS_BUSY.toByte()), null)
            throw Wire.HandshakeException("Уже связан")
        }
        val token = tokens.current()
        if (token == null) {
            Wire.writeFrame(output, byteArrayOf(STATUS_NO_INVITE.toByte()), null)
            throw Wire.HandshakeException("Нет действующего QR")
        }
        if (clientId == myId) throw Wire.HandshakeException("Сопряжение с самим собой")
        val nonceA = Wire.random(random, Wire.NONCE_BYTES)
        Wire.writeFrame(
            output,
            Wire.build {
                writeByte(STATUS_OK)
                writeUTF(myId)
                writeUTF(myName)
                write(nonceA)
            },
            transcript,
        )
        val th = Hkdf.sha256(transcript.toByteArray())
        val keys = derive(token, nonceB, nonceA, clientId, myId, th)
        val clientMac = Wire.readFrame(input, null)
        val expected = Hkdf.hmac(keys.first, Wire.concat(Wire.label("client"), th))
        if (!Hkdf.constantTimeEquals(clientMac, expected)) {
            tokens.registerFailure(token)
            throw Wire.HandshakeException("Неверное подтверждение")
        }
        if (!tokens.consume(token)) throw Wire.HandshakeException("QR уже использован")
        Wire.writeFrame(output, Hkdf.hmac(keys.first, Wire.concat(Wire.label("server"), th)), null)
        return PairResult(clientId, clientName, keys.second)
    }

    /** (ключ подтверждения, постоянный ключ пары). */
    private fun derive(
        token: ByteArray,
        nonceB: ByteArray,
        nonceA: ByteArray,
        clientId: String,
        serverId: String,
        transcriptHash: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val prk = Hkdf.extract(Wire.concat(nonceB, nonceA), token)
        val info = Wire.build {
            writeUTF("nll-pair")
            writeByte(Wire.PROTOCOL_VERSION)
            writeUTF(clientId)
            writeUTF(serverId)
            write(transcriptHash)
        }
        return Hkdf.expand(prk, Wire.concat(info, Wire.label("confirm")), 32) to
            Hkdf.expand(prk, Wire.concat(info, Wire.label("pair-key")), 32)
    }
}
