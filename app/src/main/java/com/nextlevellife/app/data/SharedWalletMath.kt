package com.nextlevellife.app.data

/**
 * Правила общего кошелька и общего дневного лимита двух связанных телефонов (чистая логика, без Android).
 *
 * - Кошелёк телефона — сумма его собственных операций; кошелёк второго — сумма копии его журнала. «Общий баланс» на
 *   экране — их сумма. Потратить можно только свой кошелёк.
 * - Доля дневного лимита на учётные сутки: половина лимита + полученные переносы доли − отданные − свой расход.
 *   Несвязанный телефон — весь лимит минус свой расход. Лимит 0 — без ограничения.
 * - Переносы (минут и доли) делает только отдающий — «от себя»: так никакая последовательность обменов и обрывов
 *   не даёт потратить одни минуты дважды (отправитель записывает минус первым, получатель — плюс ровно один раз).
 */
object SharedWalletMath {
    /** Порог выравнивания простаивающих телефонов: разница меньше — ничего не переносится. */
    const val EQUALIZE_THRESHOLD_MS = 2 * 60_000L

    /**
     * Половина лимита [limitMs] для телефона [me] в паре с [peer]: поровну, а нечётный остаток — телефону с меньшим
     * device_id (одинаково на обоих телефонах).
     */
    fun halfLimitMs(limitMs: Long, me: String, peer: String): Long {
        val base = limitMs / 2
        val rest = limitMs - base * 2
        return if (rest > 0 && me < peer) base + rest else base
    }

    /**
     * Сколько ещё этот телефон может потратить в учётные сутки по своей доле лимита; null — лимита нет ([limitMs] ≤ 0).
     * [peer] = null — пары нет: весь лимит. Может быть отрицательной (потрачено больше доли), блокировка тогда
     * видит ноль. [incomingMs] — получено переносами доли за эти сутки, [outgoingMs] — отдано, [ownSpentMs] — свой расход.
     *
     * Связанный телефон: доля = min(половина + полученное − отданное − свой расход, лимит − свой расход − расход
     * второго телефона по копии [peerSpentMs]). Второе ограничение срабатывает, только когда второй телефон потратил
     * больше своей доли — в сутки сопряжения, когда он тратил до пары без ограничения половиной: так израсходованный
     * до пары лимит не выдаётся снова (иначе 120 из 120 у одного и 0 у другого дали бы ещё 60). В обычные сутки
     * (переносы дошли) первое значение не больше второго. [peerSpentMs] = null — копии журнала второго телефона ещё
     * нет (после сопряжения не было ни одного обмена): его расход неизвестен, доля не больше нуля до первого обмена.
     */
    fun shareLeftMs(
        limitMs: Long, me: String, peer: String?, incomingMs: Long, outgoingMs: Long, ownSpentMs: Long,
        peerSpentMs: Long? = 0L,
    ): Long? {
        if (limitMs <= 0L) return null
        val spent = ownSpentMs.coerceAtLeast(0L)
        if (peer == null) return limitMs - spent
        val share = halfLimitMs(limitMs, me, peer) + incomingMs.coerceAtLeast(0L) - outgoingMs.coerceAtLeast(0L) - spent
        if (peerSpentMs == null) return minOf(share, 0L)
        return minOf(share, limitMs - spent - peerSpentMs.coerceAtLeast(0L))
    }

    /**
     * Расход за сутки, который надо показать правилам блокировки ([com.nextlevellife.app.block.BlockRules]), чтобы их
     * «лимит − потрачено» совпало со своей долей [shareLeftMs]: лимит − доля. null у доли — без лимита (расход 0).
     */
    fun effectiveSpentMs(limitMs: Long, shareLeftMs: Long?): Long =
        if (shareLeftMs == null || limitMs <= 0L) 0L else (limitMs - shareLeftMs).coerceAtLeast(0L)

    /** Состояние одной стороны на момент обмена (своё — свежее, второго — из его приветствия). */
    data class Side(
        /** Сейчас тратит (открыто заблокированное приложение или только что упёрлось в блокировку). */
        val spending: Boolean,
        /** Кошелёк, который можно отдать: баланс минус неоплаченный расход. */
        val walletMs: Long,
        /** Оставшаяся доля лимита; null — лимита нет. */
        val shareLeftMs: Long?,
        /** Учётные сутки. */
        val day: Long,
    )

    /** Что этот телефон отдаёт второму: минуты кошелька и доля лимита за сутки [day]. */
    data class Give(val walletMs: Long, val limitMs: Long, val day: Long) {
        val isEmpty: Boolean get() = walletMs <= 0L && limitMs <= 0L
    }

    /**
     * Правило переноса при обмене — каждая сторона считает только про себя (перенос только «от себя»):
     * - я простаиваю, второй тратит — отдаю весь свой положительный кошелёк и всю оставшуюся долю лимита
     *   (долю — только если учётные сутки совпадают);
     * - оба простаивают — если мой кошелёк больше на [EQUALIZE_THRESHOLD_MS] и больше, отдаю половину разницы;
     *   так же для оставшихся долей лимита;
     * - я трачу (второй тоже или нет) — ничего не отдаю.
     */
    fun plan(me: Side, peer: Side): Give {
        if (me.spending) return Give(0L, 0L, me.day)
        val sameDay = me.day == peer.day
        val myShare = me.shareLeftMs
        val peerShare = peer.shareLeftMs
        return if (peer.spending) {
            Give(
                walletMs = me.walletMs.coerceAtLeast(0L),
                limitMs = if (sameDay && myShare != null && peerShare != null) myShare.coerceAtLeast(0L) else 0L,
                day = me.day,
            )
        } else {
            Give(
                walletMs = halfOfLead(me.walletMs, peer.walletMs),
                limitMs = if (sameDay && myShare != null && peerShare != null) halfOfLead(myShare, peerShare) else 0L,
                day = me.day,
            )
        }
    }

    /** Половина превышения [mine] над [theirs], если оно не меньше порога; иначе 0. Отдаётся не больше своего. */
    private fun halfOfLead(mine: Long, theirs: Long): Long {
        val m = mine.coerceAtLeast(0L)
        val lead = m - theirs.coerceAtLeast(0L)
        if (lead < EQUALIZE_THRESHOLD_MS) return 0L
        return (lead / 2).coerceAtMost(m)
    }

    // ---------- Общие настройки ----------

    /** Одна общая настройка: значение, версия (счётчик изменений) и автор последнего изменения. */
    data class Setting(val key: String, val value: String, val version: Long, val author: String)

    /** Ключи, у которых побеждает минимум (учётные дни начала), а не последняя версия. */
    val MIN_KEYS = setOf("saitama_since", "stats_penalty_since")

    /**
     * Победитель слияния двух значений одного ключа: большая версия, при равенстве — больший автор (device_id).
     * Для [MIN_KEYS] — меньшее число (нечисловое значение проигрывает числовому).
     */
    fun merge(a: Setting?, b: Setting?): Setting? {
        if (a == null) return b
        if (b == null) return a
        require(a.key == b.key)
        if (a.key in MIN_KEYS) {
            val av = a.value.toLongOrNull()
            val bv = b.value.toLongOrNull()
            return when {
                av == null -> b
                bv == null -> a
                av < bv -> a
                bv < av -> b
                else -> if (a.author >= b.author) a else b
            }
        }
        return when {
            a.version != b.version -> if (a.version > b.version) a else b
            else -> if (a.author >= b.author) a else b
        }
    }

    /** Слияние наборов: по каждому ключу — [merge]. Результат по ключам. */
    fun mergeAll(local: Collection<Setting>, remote: Collection<Setting>): Map<String, Setting> {
        val out = HashMap<String, Setting>()
        for (s in local) out[s.key] = merge(out[s.key], s)!!
        for (s in remote) out[s.key] = merge(out[s.key], s)!!
        return out
    }

    /** Версия локального изменения: больше всех известных версий этого ключа. */
    fun nextVersion(known: Collection<Setting>, key: String): Long =
        (known.filter { it.key == key }.maxOfOrNull { it.version } ?: 0L) + 1

    /**
     * Источник активности по умолчанию при появлении пары: телефон с доступом к Health Connect; у обоих или
     * ни у кого — с меньшим device_id. Одинаков на обоих телефонах.
     */
    /**
     * Начисляет ли телефон [me] за активность: без пары ([peer] = null) — да; в паре — только выбранный источник
     * [source], и только если здешняя смена источника уже подтверждена обменом ([pendingChange] = false). Источник
     * не выбран — никто: два телефона никогда не начисляют за одни шаги одновременно.
     */
    fun isActivitySource(me: String, peer: String?, source: String?, pendingChange: Boolean): Boolean =
        peer == null || (source == me && !pendingChange)

    fun defaultActivitySource(me: String, meHealth: Boolean, peer: String, peerHealth: Boolean): String = when {
        meHealth && !peerHealth -> me
        peerHealth && !meHealth -> peer
        else -> minOf(me, peer)
    }
}
