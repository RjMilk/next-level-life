package com.nextlevellife.app.sync

import com.nextlevellife.app.data.CardsMath
import com.nextlevellife.app.data.LedgerMath
import com.nextlevellife.app.data.NutritionMath
import com.nextlevellife.app.data.OpKind
import com.nextlevellife.app.data.SharedWalletMath
import com.nextlevellife.app.data.TrustedClockMath

/** Своё состояние для приветствия и правила переноса (после погашения текущего расхода). */
data class LocalState(
    val opsEpoch: String,
    val factsEpoch: String,
    val day: Long,
    val zoneId: String,
    val spending: Boolean,
    /** Кошелёк, который можно отдать: баланс минус неоплаченный расход. */
    val walletMs: Long,
    /** Оставшаяся доля дневного лимита; null — без лимита. */
    val shareLeftMs: Long?,
    val healthConnected: Boolean,
)

/**
 * Хранилище глазами обмена: журналы, курсоры, применение пакетов. Реализации — на дневнике ([DiarySyncStore])
 * и в памяти (тесты). Каждый apply — одна транзакция: пакет и курсор применяются вместе или не применяются вовсе.
 * Импорт идемпотентен и не начисляет наград.
 */
interface SyncStore {
    /** device_id этой установки. */
    val deviceId: String

    /** Своё состояние для обмена с [peerId]. */
    fun localState(peerId: String): LocalState

    /** Докуда принята копия журнала операций [peerId] (null — копии нет). */
    fun opsCursor(peerId: String): LogCursor?

    /** Что есть из фактов: по (автор, поколение) — наибольший номер. */
    fun factCursors(): List<LogCursor>

    /** Свои операции с rev больше [afterRev] и не больше [maxRev], по возрастанию, не больше [limit]. */
    fun opsAfter(afterRev: Long, maxRev: Long, limit: Int): List<OpRecord>

    /** Наибольший rev своего журнала сейчас (верхняя граница выгрузки). */
    fun maxOpsRev(): Long

    /** Факты автора [author] поколения [epoch] с номером больше [afterAseq], по возрастанию, не больше [limit]. */
    fun factsAfter(author: String, epoch: String, afterAseq: Long, limit: Int): List<FactRecord>

    /**
     * Пакет операций [peerId] поколения [epoch]; [reset] — сначала стереть прежнюю копию и курсор этого автора.
     * В той же транзакции — курсор и входящие переносы (TRANSFER_IN) по всем исходящим [peerId], адресованным сюда.
     * Возвращает, сколько минут получено переносами.
     */
    fun applyOps(peerId: String, epoch: String, reset: Boolean, rows: List<OpRecord>): Long

    /** Пакет фактов: проверка автора ([SyncRules.acceptFact]), без дублей по uid. Возвращает затронутые сутки. */
    fun applyFacts(peerId: String, rows: List<FactRecord>): Set<Long>

    /** Этот телефон — источник активности (по общей настройке; без пары — да). */
    fun isActivitySource(peerId: String): Boolean

    /** Активность за последние дни для второго телефона; null — нечего или не источник. */
    fun activityExport(): ActivityExport?

    /** Заменить дни активности копией от [peerId] (только если он источник, а этот телефон — нет). */
    fun applyActivity(peerId: String, export: ActivityExport): Set<Long>

    /** Все общие настройки (недостающие заполняются из текущих значений версией 0). */
    fun settings(): List<SharedWalletMath.Setting>

    /** Слить общие настройки второго телефона; победившие значения применить. true — что-то изменилось. */
    fun applySettings(remote: List<SharedWalletMath.Setting>): Boolean

    /** Если источник активности ещё не выбран — записать выбор по умолчанию ([SharedWalletMath.defaultActivitySource]). */
    fun ensureActivitySource(peerId: String, myHealth: Boolean, peerHealth: Boolean)

    /** Кошелёк [peerId] по копии его журнала. */
    fun peerWalletMs(peerId: String): Long

    /** Оставшаяся доля лимита [peerId] по копии его журнала и переносам; null — без лимита или неизвестно. */
    fun peerShareLeftMs(peerId: String): Long?

    /** Перенести [ms] минут на [peerId] (TRANSFER_OUT), не больше того, что можно отдать сейчас. Сколько перенесено. */
    fun giveWallet(peerId: String, ms: Long): Long

    /** Перенести [ms] своей доли лимита за сутки [day] на [peerId] (limit_moves), не больше оставшейся доли. */
    fun giveLimit(peerId: String, day: Long, ms: Long): Long

    /** После обмена (успешного или нет): пересчитать зависимое. [days] — сутки, в которых появились факты или активность. */
    fun onImported(days: Set<Long>, changed: Boolean)
}

/** Правила приёма, общие для всех реализаций хранилища (чистая логика). */
object SyncRules {
    /**
     * Принимается ли факт от второго телефона [peer]. Свои факты текущего поколения пишет только этот телефон:
     * «свой» факт текущего поколения с другого телефона — подделка или ошибка. Свои подходы других поколений
     * (потерянные при восстановлении копии) и подходы других авторов принимаются; дубли отсекает uid
     * (независимо от поколения).
     */
    fun acceptFact(f: FactRecord, me: String, myFactsEpoch: String, peer: String): Boolean = when (f) {
        // Перенос доли лимита принимается только от второго телефона и только адресованный сюда, с положительной
        // долей. Свои переносы с провода не принимаются никогда (даже «потерянные»): при восстановлении копии они
        // переносятся локально, а подделка «от моего имени» с отрицательной долей обнулила бы мои отданные.
        is LimitMoveFact -> f.author == peer && f.toDevice == me && f.author != f.toDevice && f.ms > 0L
        // Подходы и прохождения: без отрицательных чисел; свои — только прежних поколений (возврат потерянных
        // при восстановлении; наград импорт не начисляет).
        is SetFact -> f.reps >= 0 && f.earnedMs >= 0L && f.xp >= 0L && f.heldMs >= 0L && f.durationMs >= 0L &&
            !(f.author == me && f.epoch == myFactsEpoch)
        is RunFact -> f.totalReps >= 0 && f.earnedMs >= 0L && f.xp >= 0L && f.durationMs >= 0L &&
            (f.rounds ?: 0) >= 0 && (f.extraReps ?: 0) >= 0 && !(f.author == me && f.epoch == myFactsEpoch)
        // Приёмы пищи и удаления: только этой пары (автор — я или второй телефон), свои — только прежних поколений.
        // uid приёма — имя файла фото, поэтому только безопасные символы; заметка — не длиннее предела.
        is MealFact -> (f.author == me || f.author == peer) && !(f.author == me && f.epoch == myFactsEpoch) &&
            NutritionMath.isSafeUid(f.uid) && f.capturedUtcMs > 0L && TrustedClockMath.isValidZoneId(f.zoneId) &&
            (f.note?.length ?: 0) <= NutritionMath.MAX_NOTE_CHARS
        is MealDeleteFact -> (f.author == me || f.author == peer) && !(f.author == me && f.epoch == myFactsEpoch) &&
            NutritionMath.isSafeUid(f.uid) && NutritionMath.isSafeUid(f.mealUid)
        // Ответы на карточки — как приёмы пищи (только этой пары, свои — только прежних поколений), плюс проверка
        // оплаты: не больше одной награды за попытку и только за верный ответ. Получатель по ним не начисляет.
        is CardAttemptFact -> (f.author == me || f.author == peer) && !(f.author == me && f.epoch == myFactsEpoch) &&
            NutritionMath.isSafeUid(f.uid) && CardsMath.isValidCardId(f.cardId) && f.deck.length in 1..16 &&
            f.utcMs > 0L && TrustedClockMath.isValidZoneId(f.zoneId) &&
            f.paidMs in 0L..CardsMath.REWARD_MS && (f.firstCorrect || f.paidMs == 0L) &&
            dayMatches(f.utcMs, f.zoneId, f.day)
    }

    /**
     * Учётные сутки [day] согласуются с моментом [utcMs] в поясе [zoneId] (допуск — сутки): чужой факт с днём
     * вроде Long.MIN_VALUE сломал бы расчёт характеристик.
     */
    fun dayMatches(utcMs: Long, zoneId: String, day: Long): Boolean {
        val zone = runCatching { java.time.ZoneId.of(zoneId) }.getOrNull() ?: return false
        val real = runCatching { com.nextlevellife.app.data.LedgerMath.accountingDay(utcMs, zone) }.getOrNull()
            ?: return false
        return day in (real - 1)..(real + 1)
    }

    /** Понятная причина отказа, когда версии обмена у телефонов разные. */
    fun protocolMismatch(peer: Int, mine: Int): String = when {
        peer < mine -> "на втором телефоне старая версия приложения (обмен $peer, здесь $mine) — обнови его"
        else -> "на этом телефоне старая версия приложения (обмен $mine, на втором $peer) — обнови его"
    }

    /**
     * Исходящие переносы второго телефона, адресованные [me], по которым здесь ещё нет входящего
     * (ключ [LedgerMath.transferInKey] не в [haveKeys]).
     */
    fun missingTransferIns(peerOps: Collection<OpRecord>, me: String, haveKeys: Set<String>): List<OpRecord> =
        peerOps.filter {
            it.kind == OpKind.TRANSFER_OUT.name && it.sourceId == me && it.deltaMs < 0L &&
                LedgerMath.transferInKey(it.id) !in haveKeys
        }.distinctBy { it.id }

    /** Наибольший номер по каждому (журнал, автор, поколение) из нескольких наборов курсоров. */
    fun mergeCursors(vararg sets: Collection<LogCursor>): List<LogCursor> {
        val out = LinkedHashMap<Triple<String, String, String>, LogCursor>()
        for (set in sets) for (c in set) {
            val k = Triple(c.log, c.author, c.epoch)
            val old = out[k]
            if (old == null || c.pos > old.pos) out[k] = c
        }
        return out.values.toList()
    }

    /**
     * Нужно ли второму телефону получить журнал операций заново: его курсор [cursor] — от другого поколения
     * (или курсора нет) при текущем поколении [myEpoch].
     */
    fun needsReset(cursor: LogCursor?, myEpoch: String): Boolean = cursor == null || cursor.epoch != myEpoch
}
