package com.nextlevellife.app.sync.transport

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869) на HmacSHA256 из javax.crypto. Ключи сессии и ключ пары выводятся только через него.
 */
object Hkdf {
    private const val HASH_LEN = 32

    /** Шаг извлечения: PRK = HMAC(salt, ikm). Пустая соль — 32 нулевых байта, как в RFC. */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val s = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmac(s, ikm)
    }

    /** Шаг расширения: [length] байт (не больше 255 × 32) из PRK и контекста [info]. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..255 * HASH_LEN) { "Слишком длинный выход HKDF" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - pos)
            System.arraycopy(previous, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    /** Извлечение и расширение одним вызовом. */
    fun derive(salt: ByteArray?, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    /** HMAC-SHA256. */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Сравнение за постоянное время: подтверждения ключа не сравниваются побайтно с ранним выходом. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
