package com.nextlevellife.app.sync.transport

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Содержимое QR-кода сопряжения: `nll-pair:1:<deviceId>:<токен base64url без '='>:<имя в URL-кодировке>`.
 *
 * Токен — 32 случайных байта, одноразовый, живёт 5 минут и только пока на показывающем телефоне открыт экран.
 * В двоеточиях частей быть не может: device_id — UUID, base64url без ':', имя закодировано (':' → %3A).
 */
class PairingInvite(val deviceId: String, val token: ByteArray, val name: String) {

    /** Текст для QR-кода. */
    fun format(): String {
        val t = Base64.getUrlEncoder().withoutPadding().encodeToString(token)
        // URLEncoder кодирует пробел плюсом; %20 понятнее и одинаково читается URLDecoder.
        // Имя обрезается заранее, чтобы QR оставался небольшим и читался с первого раза.
        val n = URLEncoder.encode(Names.cleanName(name) ?: "Телефон", "UTF-8").replace("+", "%20")
        return "$PREFIX:$VERSION:$deviceId:$t:$n"
    }

    override fun equals(other: Any?): Boolean =
        other is PairingInvite && other.deviceId == deviceId && other.name == name && other.token.contentEquals(token)

    override fun hashCode(): Int = deviceId.hashCode() * 31 + token.contentHashCode()

    override fun toString(): String = "PairingInvite($deviceId, $name)"

    companion object {
        const val PREFIX = "nll-pair"
        const val VERSION = 1
        const val TOKEN_BYTES = 32

        /** Разбор текста QR; чужой или испорченный текст — null. */
        fun parse(text: String?): PairingInvite? {
            if (text == null || text.length > 512) return null
            val parts = text.trim().split(':', limit = 5)
            if (parts.size != 5 || parts[0] != PREFIX || parts[1] != VERSION.toString()) return null
            val deviceId = parts[2]
            if (!Names.isValidDeviceId(deviceId)) return null
            val token = try {
                Base64.getUrlDecoder().decode(parts[3])
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (token.size != TOKEN_BYTES) return null
            val name = try {
                URLDecoder.decode(parts[4], "UTF-8")
            } catch (e: IllegalArgumentException) {
                return null
            }
            val clean = Names.cleanName(name) ?: return null
            return PairingInvite(deviceId, token, clean)
        }
    }
}

/** Проверка идентификаторов и имён, приходящих из QR и по сети. */
object Names {
    const val MAX_NAME = 64
    private const val MAX_ID = 64

    /** device_id — UUID; допускаем латиницу, цифры и дефис, 8–64 символа. */
    fun isValidDeviceId(id: String): Boolean =
        id.length in 8..MAX_ID && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

    /** Имя телефона без управляющих символов, обрезанное до [MAX_NAME]; пустое — null. */
    fun cleanName(name: String): String? {
        var s = name.filter { !it.isISOControl() }.trim().take(MAX_NAME)
        // Не оставлять половину суррогатной пары (эмодзи на границе обрезки).
        if (s.isNotEmpty() && s.last().isHighSurrogate()) s = s.dropLast(1)
        return s.trim().ifEmpty { null }
    }
}
