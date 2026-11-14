package com.nextlevellife.app.sync.transport

/**
 * Не больше одного канала со вторым телефоном одновременно.
 *
 * - Исходящее ([beginOutgoing]) начинается, только если слот свободен; иначе `open` возвращает null.
 * - Входящее решается после проверки подтверждения клиента ([acceptIncoming]): при свободном слоте —
 *   принимается; при уже открытом канале — отклоняется; при встречном подключении (наше исходящее ещё
 *   в рукопожатии) побеждает подключение стороны с меньшим device_id. Вторая сторона решает так же, поэтому
 *   из двух встречных остаётся ровно одно. Проигравшее исходящее отменяется (закрывается его сокет).
 */
internal class ChannelSlot(private val myId: () -> String) {

    /** Исходящая попытка. [cancel] закрывает её сокет, если победило встречное входящее. */
    inner class Outgoing internal constructor(private val cancelAction: () -> Unit) {
        @Volatile internal var cancelled = false
        internal var established = false

        internal fun cancel() {
            cancelled = true
            runCatching(cancelAction)
        }
    }

    /** Принятое входящее. */
    inner class Incoming internal constructor()

    private var current: Any? = null

    @Synchronized
    fun isBusy(): Boolean = current != null

    /** Занять слот под исходящее; null — занято. */
    @Synchronized
    fun beginOutgoing(cancelAction: () -> Unit): Outgoing? {
        if (current != null) return null
        return Outgoing(cancelAction).also { current = it }
    }

    /** Рукопожатие исходящего завершено: true — канал наш; false — попытку отменило встречное входящее. */
    @Synchronized
    fun outgoingEstablished(attempt: Outgoing): Boolean {
        if (current !== attempt || attempt.cancelled) return false
        attempt.established = true
        return true
    }

    /** Решение по входящему каналу от [peerId]; null — отклонить. */
    fun acceptIncoming(peerId: String): Incoming? {
        val toCancel: Outgoing
        val ticket: Incoming
        synchronized(this) {
            val cur = current
            when {
                cur == null -> {
                    ticket = Incoming()
                    current = ticket
                    return ticket
                }
                cur is Outgoing && !cur.established && !cur.cancelled && peerId < myId() -> {
                    // Встречное подключение: побеждает инициатор с меньшим device_id — это второй телефон.
                    toCancel = cur
                    ticket = Incoming()
                    current = ticket
                }
                else -> return null
            }
        }
        toCancel.cancel()
        return ticket
    }

    /** Освободить слот (канал закрыт или попытка не удалась). Чужой билет ничего не освобождает. */
    @Synchronized
    fun release(ticket: Any) {
        if (current === ticket) current = null
    }
}
