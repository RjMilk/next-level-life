package com.nextlevellife.app.sync.transport

import com.nextlevellife.app.sync.SyncChannel
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрованный канал после рукопожатия сессии.
 *
 * Кадр: 4 байта длины шифротекста (big-endian) + AES-256-GCM(сообщение) с тегом 16 байт.
 * Nonce = 4 нулевых байта + 8 байт счётчика кадров этого направления; AAD = длина ‖ счётчик.
 * У направлений разные ключи ([sendKey] / [recvKey]), поэтому одинаковые счётчики у двух сторон не дают
 * повтора nonce. Счётчик неявный: подмена, перестановка, повтор или выкидывание кадра ломают тег —
 * канал закрывается, [receive] бросает [IOException] без подробностей.
 *
 * [send] и [receive] можно звать с разных потоков; каждый из них сам по себе последовательный.
 */
internal class SecureChannel(
    input: InputStream,
    output: OutputStream,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    override val peerDeviceId: String,
    override val initiator: Boolean,
    /** Срок одной операции [send]/[receive] целиком (0 — без срока); истёк — канал закрывается. */
    private val operationTimeoutMs: Long = 0L,
    private val onClose: () -> Unit,
) : SyncChannel {
    private val input = DataInputStream(input)
    private val output = BufferedOutputStream(output, 64 * 1024)
    private val sendLock = Any()
    private val recvLock = Any()
    private var sendCounter = 0L
    private var recvCounter = 0L
    private val closed = AtomicBoolean(false)
    private val sendSpec = SecretKeySpec(sendKey, "AES")
    private val recvSpec = SecretKeySpec(recvKey, "AES")

    override fun send(message: ByteArray) {
        if (message.size > SyncChannel.MAX_MESSAGE_BYTES) throw IOException("Сообщение больше предела")
        synchronized(sendLock) {
            if (closed.get()) throw IOException("Канал закрыт")
            val counter = sendCounter++
            val length = message.size + TAG_BYTES
            val guard = arm()
            try {
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(Cipher.ENCRYPT_MODE, sendSpec, GCMParameterSpec(TAG_BYTES * 8, nonce(counter)))
                cipher.updateAAD(aad(length, counter))
                val sealed = cipher.doFinal(message)
                output.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
                output.write(sealed)
                output.flush()
            } catch (e: IOException) {
                close()
                throw timedOut(guard) ?: e
            } catch (e: Exception) {
                close()
                throw IOException("Не удалось зашифровать кадр")
            } finally {
                guard?.disarm()
            }
        }
    }

    private fun arm(): Watchdog.Guard? = if (operationTimeoutMs > 0) Watchdog.arm(operationTimeoutMs, this) else null

    private fun timedOut(guard: Watchdog.Guard?): IOException? =
        if (guard?.fired == true) SocketTimeoutException("Время операции истекло") else null

    override fun receive(): ByteArray = synchronized(recvLock) {
        if (closed.get()) throw IOException("Канал закрыт")
        val guard = arm()
        try {
            val length = input.readInt()
            if (length < TAG_BYTES || length > MAX_FRAME_BYTES) throw IOException("Канал нарушен")
            val sealed = ByteArray(length)
            input.readFully(sealed)
            val counter = recvCounter++
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, recvSpec, GCMParameterSpec(TAG_BYTES * 8, nonce(counter)))
            cipher.updateAAD(aad(length, counter))
            try {
                cipher.doFinal(sealed)
            } catch (e: AEADBadTagException) {
                throw IOException("Канал нарушен")
            }
        } catch (e: IOException) {
            close()
            throw timedOut(guard) ?: e
        } catch (e: Exception) {
            close()
            throw IOException("Канал нарушен")
        } finally {
            guard?.disarm()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            sendKey.fill(0)
            recvKey.fill(0)
            onClose()
        }
    }

    val isClosed: Boolean get() = closed.get()

    companion object {
        const val TAG_BYTES = 16
        /** Наибольший кадр на проводе: предел сообщения + тег GCM. */
        const val MAX_FRAME_BYTES = SyncChannel.MAX_MESSAGE_BYTES + TAG_BYTES
        private const val AES_GCM = "AES/GCM/NoPadding"

        internal fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

        internal fun aad(length: Int, counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(length).putLong(counter).array()
    }
}
