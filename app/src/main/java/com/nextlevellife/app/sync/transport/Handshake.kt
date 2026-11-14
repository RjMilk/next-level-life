package com.nextlevellife.app.sync.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Открытые (до шифрования) кадры рукопожатий: 2 байта длины + тело не больше [MAX_PLAIN]. Транскрипт —
 * последовательность кадров как на проводе (длина + тело); его хеш подтверждают обе стороны.
 *
 * Первый кадр клиента общий для всех видов соединений: магия "NLLS", версия протокола, вид
 * ([KIND_SESSION] — канал между связанными телефонами, [KIND_PAIR] — сопряжение по QR).
 */
internal object Wire {
    const val MAGIC = 0x4E4C4C53 // "NLLS"
    const val PROTOCOL_VERSION = 1
    const val KIND_SESSION = 1
    const val KIND_PAIR = 2
    const val NONCE_BYTES = 32
    const val MAC_BYTES = 32
    private const val MAX_PLAIN = 1024

    /** Отказ в рукопожатии. Текст никогда не уходит второй стороне. */
    class HandshakeException(message: String) : IOException(message)

    /** Записывает кадр и дописывает его в транскрипт. */
    fun writeFrame(out: OutputStream, body: ByteArray, transcript: ByteArrayOutputStream?) {
        require(body.size <= MAX_PLAIN)
        val framed = ByteArray(body.size + 2)
        framed[0] = (body.size ushr 8).toByte()
        framed[1] = body.size.toByte()
        System.arraycopy(body, 0, framed, 2, body.size)
        out.write(framed)
        out.flush()
        transcript?.write(framed)
    }

    /** Читает кадр и дописывает его в транскрипт; конец потока или лишняя длина — [HandshakeException]. */
    fun readFrame(input: InputStream, transcript: ByteArrayOutputStream?): ByteArray {
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) throw HandshakeException("Соединение закрыто")
        val len = (hi shl 8) or lo
        if (len > MAX_PLAIN) throw HandshakeException("Кадр слишком длинный")
        val body = ByteArray(len)
        DataInputStream(input).readFully(body)
        transcript?.write(hi)
        transcript?.write(lo)
        transcript?.write(body)
        return body
    }

    fun build(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.block() }
        return bytes.toByteArray()
    }

    /** Разбор тела кадра: любая ошибка формата (в том числе лишние байты в конце) — [HandshakeException]. */
    fun <T> parse(body: ByteArray, block: DataInputStream.() -> T): T {
        val input = DataInputStream(ByteArrayInputStream(body))
        val result = try {
            input.block()
        } catch (e: HandshakeException) {
            throw e
        } catch (e: Exception) {
            throw HandshakeException("Неверный формат кадра")
        }
        if (input.available() != 0) throw HandshakeException("Лишние байты в кадре")
        return result
    }

    fun DataInputStream.readBytes(n: Int): ByteArray = ByteArray(n).also { readFully(it) }

    fun DataInputStream.readDeviceId(): String =
        readUTF().also { if (!Names.isValidDeviceId(it)) throw HandshakeException("Неверный device_id") }

    fun DataInputStream.readName(): String =
        Names.cleanName(readUTF()) ?: throw HandshakeException("Пустое имя")

    fun random(random: SecureRandom, n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun label(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }
}

/** Ключи сессии и вид соединения после успешного рукопожатия. */
internal class SessionKeys(
    val peerDeviceId: String,
    val purpose: SessionHandshake.Purpose,
    val sendKey: ByteArray,
    val recvKey: ByteArray,
)

/**
 * Рукопожатие сессии между связанными телефонами (общий постоянный ключ пары).
 *
 * 1. Клиент → сервер: MAGIC, версия, KIND_SESSION, назначение, device_id клиента, nonceC (32 байта).
 * 2. Сервер проверяет, что device_id — связанный телефон; иначе молча закрывает соединение.
 *    Сервер → клиент: device_id сервера, nonceS. Клиент проверяет, что это тот, к кому он шёл.
 * 3. Ключи: PRK = HKDF-Extract(nonceC ‖ nonceS, ключ пары);
 *    info = "nll-session" ‖ версия ‖ назначение ‖ id клиента ‖ id сервера ‖ SHA-256(транскрипт);
 *    ключ клиент→сервер = Expand(info ‖ "c2s"), сервер→клиент = Expand(info ‖ "s2c"), подтверждения = Expand(info ‖ "confirm").
 * 4. Клиент → сервер: HMAC(confirm, "client" ‖ хеш транскрипта). Сервер проверяет и решает, принимать ли
 *    соединение ([acceptAfterConfirm]); при отказе закрывает. Сервер → клиент: HMAC(confirm, "server" ‖ хеш).
 *    Клиент проверяет. Неверный ключ пары у любой стороны — обрыв.
 */
internal object SessionHandshake {

    /** Зачем открыто соединение. Входит в транскрипт и в ключи. */
    enum class Purpose(val code: Int) {
        /** Канал для обмена данными ([com.nextlevellife.app.sync.SyncChannel]). */
        SYNC(0),

        /** Проверка связи: после рукопожатия соединение сразу закрывается, обмен не будится. */
        PING(1),

        /** Второй телефон разорвал связь: сервер забывает пару. */
        UNPAIR(2);

        companion object {
            fun of(code: Int): Purpose = entries.firstOrNull { it.code == code } ?: throw Wire.HandshakeException("Неизвестное назначение")
        }
    }

    fun clientHello(purpose: Purpose, myId: String, nonce: ByteArray): ByteArray = Wire.build {
        writeInt(Wire.MAGIC)
        writeByte(Wire.PROTOCOL_VERSION)
        writeByte(Wire.KIND_SESSION)
        writeByte(purpose.code)
        writeUTF(myId)
        write(nonce)
    }

    /** Клиентская сторона. Бросает [IOException] при любом отказе. */
    fun client(
        input: InputStream,
        output: OutputStream,
        myId: String,
        expectedPeerId: String,
        pairKey: ByteArray,
        purpose: Purpose,
        random: SecureRandom,
    ): SessionKeys {
        val transcript = ByteArrayOutputStream()
        val nonceC = Wire.random(random, Wire.NONCE_BYTES)
        Wire.writeFrame(output, clientHello(purpose, myId, nonceC), transcript)
        val serverHello = Wire.readFrame(input, transcript)
        val (serverId, nonceS) = Wire.parse(serverHello) {
            with(Wire) { readDeviceId() to readBytes(Wire.NONCE_BYTES) }
        }
        if (serverId != expectedPeerId) throw Wire.HandshakeException("Чужой телефон")
        val th = Hkdf.sha256(transcript.toByteArray())
        val keys = derive(pairKey, nonceC, nonceS, purpose, myId, serverId, th)
        Wire.writeFrame(output, Hkdf.hmac(keys.confirm, Wire.concat(Wire.label("client"), th)), null)
        val serverMac = Wire.readFrame(input, null)
        val expected = Hkdf.hmac(keys.confirm, Wire.concat(Wire.label("server"), th))
        if (!Hkdf.constantTimeEquals(serverMac, expected)) throw Wire.HandshakeException("Неверное подтверждение")
        return SessionKeys(serverId, purpose, sendKey = keys.c2s, recvKey = keys.s2c)
    }

    /**
     * Серверная сторона; первый кадр клиента ([hello], уже прочитан и записан в [transcript]) разобран
     * диспетчером до MAGIC/версии/вида включительно, [rest] — остаток тела.
     * [keyFor] — ключ пары для device_id клиента (null — не связан). [acceptAfterConfirm] вызывается после
     * проверки подтверждения клиента: false — соединение отклоняется (занято).
     */
    fun server(
        input: InputStream,
        output: OutputStream,
        transcript: ByteArrayOutputStream,
        rest: ByteArray,
        myId: String,
        keyFor: (String) -> ByteArray?,
        acceptAfterConfirm: (peerId: String, purpose: Purpose) -> Boolean,
        random: SecureRandom,
    ): SessionKeys {
        val (purpose, clientId, nonceC) = Wire.parse(rest) {
            with(Wire) { Triple(Purpose.of(readUnsignedByte()), readDeviceId(), readBytes(Wire.NONCE_BYTES)) }
        }
        val pairKey = keyFor(clientId) ?: throw Wire.HandshakeException("Телефон не связан")
        val nonceS = Wire.random(random, Wire.NONCE_BYTES)
        Wire.writeFrame(output, Wire.build { writeUTF(myId); write(nonceS) }, transcript)
        val th = Hkdf.sha256(transcript.toByteArray())
        val keys = derive(pairKey, nonceC, nonceS, purpose, clientId, myId, th)
        val clientMac = Wire.readFrame(input, null)
        val expected = Hkdf.hmac(keys.confirm, Wire.concat(Wire.label("client"), th))
        if (!Hkdf.constantTimeEquals(clientMac, expected)) throw Wire.HandshakeException("Неверное подтверждение")
        if (!acceptAfterConfirm(clientId, purpose)) throw Wire.HandshakeException("Занято")
        Wire.writeFrame(output, Hkdf.hmac(keys.confirm, Wire.concat(Wire.label("server"), th)), null)
        return SessionKeys(clientId, purpose, sendKey = keys.s2c, recvKey = keys.c2s)
    }

    private class Derived(val c2s: ByteArray, val s2c: ByteArray, val confirm: ByteArray)

    private fun derive(
        pairKey: ByteArray,
        nonceC: ByteArray,
        nonceS: ByteArray,
        purpose: Purpose,
        clientId: String,
        serverId: String,
        transcriptHash: ByteArray,
    ): Derived {
        val prk = Hkdf.extract(Wire.concat(nonceC, nonceS), pairKey)
        val info = Wire.build {
            writeUTF("nll-session")
            writeByte(Wire.PROTOCOL_VERSION)
            writeByte(purpose.code)
            writeUTF(clientId)
            writeUTF(serverId)
            write(transcriptHash)
        }
        fun key(label: String) = Hkdf.expand(prk, Wire.concat(info, Wire.label(label)), 32)
        return Derived(key("c2s"), key("s2c"), key("confirm"))
    }
}
