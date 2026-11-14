package com.nextlevellife.app.sync

import com.nextlevellife.app.data.SharedWalletMath

/** Итог одного обмена. */
data class SyncOutcome(
    /** Приветствие второго телефона: его кошелёк, доля, «тратит ли». */
    val peerHello: SyncMessage.Hello,
    /** Сколько минут пришло переносами (TRANSFER_IN) в этом обмене. */
    val receivedMs: Long,
    /** Сколько минут и доли лимита этот телефон отдал. */
    val givenWalletMs: Long,
    val givenLimitMs: Long,
)

/**
 * Один обмен с телефоном на другом конце [channel] (чистая логика поверх [SyncStore] и [SyncChannel]).
 * Обе стороны выполняют один и тот же код; очередь передачи задаёт [SyncChannel.initiator] (сначала говорит
 * открывший соединение), чтобы два больших потока не встречались одновременно.
 *
 * Ход: приветствия → журналы открывшего → журналы второго (настройки, свои операции новее курсора второго телефона,
 * факты всех авторов новее его курсоров, активность от источника) → переносы открывшего → переносы второго →
 * досылка операций открывшего (его входящие переносы) → Done.
 * Каждый пакет применяется своей транзакцией вместе с курсором, поэтому обрыв на любом шаге безопасен: применённое
 * остаётся, недошедшее придёт в следующем обмене. Переносы считаются каждой стороной только «от себя»
 * ([SharedWalletMath.plan]) по своему свежему состоянию.
 */
class SyncSession(
    private val store: SyncStore,
    private val channel: SyncChannel,
    private val maxMessageBytes: Int = SyncChannel.MAX_MESSAGE_BYTES,
    private val pageSize: Int = 400,
) {
    private val days = HashSet<Long>()
    private var changed = false
    private var receivedMs = 0L

    /** Что уже есть у второго телефона: растёт по мере отправки. */
    private class Known(cursors: List<LogCursor>, me: String, opsEpoch: String) {
        val opsCursor = cursors.firstOrNull { it.log == LogCursor.OPS && it.author == me }
        var opsReset = SyncRules.needsReset(opsCursor, opsEpoch)
        var opsPos = if (opsReset) 0L else opsCursor!!.pos
        val facts = HashMap<Pair<String, String>, Long>().apply {
            for (c in cursors) if (c.log == LogCursor.FACTS) put(c.author to c.epoch, maxOf(c.pos, get(c.author to c.epoch) ?: 0L))
        }
    }

    /** Выполнить обмен. Обрыв связи — IOException; нарушение протокола — [SyncProtocolException]. */
    fun run(): SyncOutcome {
        val peer = channel.peerDeviceId
        try {
            val local = store.localState(peer)
            val hello = SyncMessage.Hello(
                protocol = SyncCodec.PROTOCOL_VERSION,
                deviceId = store.deviceId,
                opsEpoch = local.opsEpoch,
                factsEpoch = local.factsEpoch,
                day = local.day,
                zoneId = local.zoneId,
                spending = local.spending,
                walletMs = local.walletMs,
                shareLeftMs = local.shareLeftMs,
                healthConnected = local.healthConnected,
                cursors = listOfNotNull(store.opsCursor(peer)) + store.factCursors(),
            )
            val peerHello = if (channel.initiator) {
                send(hello)
                receive() as? SyncMessage.Hello ?: throw SyncProtocolException("Ожидалось приветствие")
            } else {
                (receive() as? SyncMessage.Hello ?: throw SyncProtocolException("Ожидалось приветствие")).also { send(hello) }
            }
            if (peerHello.protocol != SyncCodec.PROTOCOL_VERSION) {
                throw SyncProtocolException("Другая версия обмена: ${peerHello.protocol}, здесь ${SyncCodec.PROTOCOL_VERSION}")
            }
            if (peerHello.deviceId != peer) throw SyncProtocolException("Приветствие не от связанного телефона")
            store.ensureActivitySource(peer, local.healthConnected, peerHello.healthConnected)
            val known = Known(peerHello.cursors, store.deviceId, local.opsEpoch)

            // Журналы.
            if (channel.initiator) {
                sendLogs(peer, local.opsEpoch, known)
                receiveStream(peer)
            } else {
                receiveStream(peer)
                sendLogs(peer, local.opsEpoch, known)
            }
            // Переносы.
            var given = 0L to 0L
            if (channel.initiator) {
                given = sendGive(peer, peerHello, local.opsEpoch, known)
                receiveStream(peer)
            } else {
                receiveStream(peer)
                given = sendGive(peer, peerHello, local.opsEpoch, known)
            }
            // Досылка: входящие переносы, которые открывший записал, принимая переносы второго, — чтобы копии
            // журналов на обоих телефонах после обмена совпадали с самими журналами.
            if (channel.initiator) {
                sendOps(local.opsEpoch, known)
                send(SyncMessage.EndLogs)
                send(SyncMessage.Done)
                if (receive() != SyncMessage.Done) throw SyncProtocolException("Ожидалось завершение")
            } else {
                receiveStream(peer)
                if (receive() != SyncMessage.Done) throw SyncProtocolException("Ожидалось завершение")
                send(SyncMessage.Done)
            }
            return SyncOutcome(peerHello, receivedMs, given.first, given.second)
        } finally {
            store.onImported(days, changed || receivedMs > 0L)
        }
    }

    // ---------- Отправка ----------

    private fun sendLogs(peer: String, opsEpoch: String, known: Known) {
        send(SyncMessage.Settings(store.settings()))
        sendOps(opsEpoch, known)
        sendFacts(known)
        if (store.isActivitySource(peer)) store.activityExport()?.let { send(SyncMessage.Activity(it)) }
        send(SyncMessage.EndLogs)
    }

    /** Переносы «от себя» по свежему своему состоянию и приветствию второго телефона, затем их досылка. */
    private fun sendGive(peer: String, peerHello: SyncMessage.Hello, opsEpoch: String, known: Known): Pair<Long, Long> {
        val me = store.localState(peer)
        // Кошелёк второго — по копии его журнала (она уже свежая): копия не учитывает его долги, так что оценка
        // не меньше настоящей, и отдаётся не больше нужного.
        val give = SharedWalletMath.plan(
            SharedWalletMath.Side(me.spending, me.walletMs, me.shareLeftMs, me.day),
            // Доля второго — тоже по копии (в приветствии она могла быть посчитана ещё без копии здешнего журнала).
            SharedWalletMath.Side(
                peerHello.spending, store.peerWalletMs(peer), store.peerShareLeftMs(peer) ?: peerHello.shareLeftMs, peerHello.day,
            ),
        )
        val wallet = if (give.walletMs > 0L) store.giveWallet(peer, give.walletMs) else 0L
        val limit = if (give.limitMs > 0L) store.giveLimit(peer, give.day, give.limitMs) else 0L
        sendOps(opsEpoch, known)
        sendFacts(known)
        send(SyncMessage.EndLogs)
        return wallet to limit
    }

    /** Свои операции новее того, что есть у второго телефона, до верхней границы на момент начала. */
    private fun sendOps(epoch: String, known: Known) {
        val maxRev = store.maxOpsRev()
        var after = known.opsPos
        var reset = known.opsReset
        while (true) {
            val page = store.opsAfter(after, maxRev, pageSize)
            if (page.isEmpty()) break
            for (part in packets(page) { SyncMessage.Ops(epoch, reset, it) }) {
                send(SyncMessage.Ops(epoch, reset, part))
                reset = false
            }
            after = page.last().rev
        }
        // Пустой журнал при смене поколения: второй телефон всё равно должен стереть старую копию.
        if (reset) send(SyncMessage.Ops(epoch, true, emptyList()))
        known.opsPos = after
        known.opsReset = false
    }

    /** Факты всех авторов, которые есть здесь, новее курсоров второго телефона. */
    private fun sendFacts(known: Known) {
        for (c in store.factCursors()) {
            val key = c.author to c.epoch
            var after = known.facts[key] ?: 0L
            if (c.pos <= after) continue
            while (true) {
                val page = store.factsAfter(c.author, c.epoch, after, pageSize)
                if (page.isEmpty()) break
                for (part in packets(page) { SyncMessage.Facts(it) }) send(SyncMessage.Facts(part))
                after = page.last().aseq
            }
            known.facts[key] = after
        }
    }

    /** Режет записи на сообщения не больше [maxMessageBytes] (делением пополам). */
    private fun <T> packets(rows: List<T>, make: (List<T>) -> SyncMessage): List<List<T>> {
        if (rows.isEmpty()) return emptyList()
        if (SyncCodec.encode(make(rows)).size <= maxMessageBytes) return listOf(rows)
        if (rows.size == 1) throw SyncProtocolException("Запись не помещается в сообщение")
        val mid = rows.size / 2
        return packets(rows.subList(0, mid), make) + packets(rows.subList(mid, rows.size), make)
    }

    // ---------- Приём ----------

    /** Принимает поток журналов (или переносов) второго телефона до [SyncMessage.EndLogs]. */
    private fun receiveStream(peer: String) {
        while (true) {
            when (val m = receive()) {
                is SyncMessage.Settings -> if (store.applySettings(m.rows)) changed = true
                is SyncMessage.Ops -> {
                    receivedMs += store.applyOps(peer, m.epoch, m.reset, m.rows)
                    if (m.rows.isNotEmpty() || m.reset) changed = true
                }
                is SyncMessage.Facts -> {
                    days += store.applyFacts(peer, m.rows)
                    if (m.rows.isNotEmpty()) changed = true
                }
                is SyncMessage.Activity -> {
                    val d = store.applyActivity(peer, m.export)
                    if (d.isNotEmpty()) changed = true
                    days += d
                }
                SyncMessage.EndLogs -> return
                else -> throw SyncProtocolException("Неожиданное сообщение: ${m.javaClass.simpleName}")
            }
        }
    }

    private fun send(m: SyncMessage) {
        val bytes = SyncCodec.encode(m)
        if (bytes.size > maxMessageBytes) throw SyncProtocolException("Сообщение больше предела")
        channel.send(bytes)
    }

    private fun receive(): SyncMessage = SyncCodec.decode(channel.receive())
}
