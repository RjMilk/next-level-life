package com.nextlevellife.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.Locale

/** Колода карточек. [id] — значение столбца `deck` в card_attempts; [numeric] — ответ числом (цифровая клавиатура). */
enum class CardDeck(val id: String, val title: String, val subtitle: String, val numeric: Boolean) {
    ENGLISH("en", "Английские слова", "200 частых слов и выражений, перевод в обе стороны", false),
    MULTIPLY("mul", "Таблица умножения", "От 2 × 2 до 9 × 9", true),
    MENTAL("calc", "Устный счёт", "Сложение и вычитание в пределах 100", true),
    ;

    companion object {
        fun byId(id: String?): CardDeck? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Карточка: [id] стабилен навсегда (`en:apple`, `ru:apple`, `mul:7x8`, `add:47+38`) — по нему копится история
 * попыток и выводится расписание. [prompt] — что показать, [task] — подпись задания, [answers] — допустимые
 * ответы (первый показывается после ошибки). [requires] — карточка становится новой только после того, как
 * у этой карточки есть история (перевод RU→EN — после EN→RU того же слова).
 */
data class Card(
    val id: String,
    val deck: CardDeck,
    val prompt: String,
    val task: String,
    val answers: List<String>,
    val requires: String? = null,
)

/**
 * Первый ответ на карточку в сессии — факт обмена (card_attempts, схема 10). [author] — device_id телефона,
 * где ответили; [paidMs] — сколько начислено за попытку на телефоне-авторе (0 — без оплаты).
 */
data class CardAttempt(
    val uid: String,
    val author: String,
    val cardId: String,
    val deck: String,
    val utcMs: Long,
    val day: Long,
    val zoneId: String,
    val firstCorrect: Boolean,
    val paidMs: Long,
)

/** Почему за ответ начислено столько. */
enum class CardRewardReason {
    /** Начислено [CardReward.paidMs]. */
    PAID,
    /** Неверный первый ответ — без минут. */
    WRONG,
    /** Эту карточку сегодня уже оплатили (на этом или втором телефоне). */
    ALREADY_PAID,
    /** Дневной потолок этого телефона (или общий) исчерпан. */
    CAP_REACHED,
    /** Повтор после ошибки в той же сессии: не записывается и не оплачивается. */
    PRACTICE,
}

data class CardReward(val paidMs: Long, val reason: CardRewardReason)

/** Состояние повторения карточки по её истории: [step] — ступень интервала ([CardsMath.INTERVALS]), [dueDay] — когда повторить. */
data class CardState(val step: Int, val dueDay: Long, val lastDay: Long)

/**
 * Карточки на дневнике (diary.db, схема 10). Первый ответ на карточку в сессии — факт (author, epoch, aseq, uid):
 * пишется одной транзакцией с начислением (OpKind.CARD_REWARD, ключ `card:<uid>`) и уходит на второй телефон обменом.
 * Повторы после ошибки в той же сессии не записываются.
 */
object Cards {
    /**
     * Записывает первый ответ на [card] (попытка [uid]) и, если положено ([CardsMath.reward]), начисляет минуты —
     * одной транзакцией. Повтор с тем же [uid] ничего не меняет и возвращает записанное. Ошибки базы пробрасываются.
     */
    fun recordAttempt(uid: String, card: Card, correct: Boolean, time: TimeSnapshot = TrustedClock.snapshot()): CardReward {
        require(NutritionMath.isSafeUid(uid)) { "Недопустимый uid попытки" }
        val result = synchronized(Diary) {
            Diary.write { db ->
                stored(db, uid)?.let { return@write it }
                val me = Ledger.deviceId()
                val reward = CardsMath.reward(
                    cardId = card.id, correct = correct, me = me,
                    dayAttempts = attempts(db, "accounting_day = ?", time.day.toString()),
                    paired = Ledger.peerId() != null,
                )
                db.insertOrThrow("card_attempts", null, ContentValues().apply {
                    put("uid", uid)
                    put("author", me)
                    put("epoch", Diary.factsEpoch())
                    put("aseq", Diary.nextFactSeq())
                    put("card_id", card.id)
                    put("deck", card.deck.id)
                    put("utc_ms", time.utcMs)
                    put("accounting_day", time.day)
                    put("zone_id", time.zone.id)
                    put("first_correct", if (correct) 1 else 0)
                    put("paid_ms", reward.paidMs)
                })
                if (reward.paidMs > 0L) {
                    val inserted = Ledger.reward(
                        OpKind.CARD_REWARD, CardsMath.rewardKey(uid), reward.paidMs, 0L, "card", card.id, time.utcMs,
                        mapOf("deck" to card.deck.id, "attempt_uid" to uid), zone = time.zone,
                    )
                    // Ключ занят — откатываем, чтобы не записать оплаченную попытку без начисления.
                    check(inserted) { "Операция ${CardsMath.rewardKey(uid)} уже существует" }
                }
                reward
            }
        }
        Store.notifyChanged()
        return result
    }

    /** Все попытки обоих телефонов (для расписания). */
    fun allAttempts(): List<CardAttempt> = synchronized(Diary) { attempts(Diary.db(), null) }

    /** Попытки обоих телефонов за учётные сутки [fromDay]..[toDay]. */
    fun attemptsInRange(fromDay: Long, toDay: Long): List<CardAttempt> = synchronized(Diary) {
        attempts(Diary.db(), "accounting_day BETWEEN ? AND ?", fromDay.toString(), toDay.toString())
    }

    /** Сколько этот телефон заработал на карточках за сутки [day] и его дневной потолок. */
    fun earnedToday(day: Long): Pair<Long, Long> {
        val me = Ledger.deviceId()
        val dayAttempts = attemptsInRange(day, day)
        return CardsMath.paidBy(dayAttempts, me) to CardsMath.phoneCapMs(Ledger.peerId() != null)
    }

    // ---------- Для обмена (DiarySyncStore) ----------

    /** Вставляет копию попытки со второго телефона; дубль по uid пропускается. true — вставлена. */
    internal fun insertAttemptCopy(
        db: SQLiteDatabase, uid: String, author: String, epoch: String, aseq: Long, cardId: String, deck: String,
        utcMs: Long, day: Long, zoneId: String, firstCorrect: Boolean, paidMs: Long,
    ): Boolean {
        if (db.rawQuery("SELECT 1 FROM card_attempts WHERE uid = ? LIMIT 1", arrayOf(uid)).use { it.moveToFirst() }) return false
        db.insertOrThrow("card_attempts", null, ContentValues().apply {
            put("uid", uid); put("author", author); put("epoch", epoch); put("aseq", aseq)
            put("card_id", cardId); put("deck", deck); put("utc_ms", utcMs); put("accounting_day", day)
            put("zone_id", zoneId); put("first_correct", if (firstCorrect) 1 else 0); put("paid_ms", paidMs)
        })
        return true
    }

    private fun stored(db: SQLiteDatabase, uid: String): CardReward? =
        attempts(db, "uid = ?", uid).firstOrNull()?.let {
            CardReward(it.paidMs, if (it.paidMs > 0L) CardRewardReason.PAID else if (it.firstCorrect) CardRewardReason.ALREADY_PAID else CardRewardReason.WRONG)
        }

    private const val COLS = "uid, author, card_id, deck, utc_ms, accounting_day, zone_id, first_correct, paid_ms"

    private fun attempts(db: SQLiteDatabase, where: String?, vararg args: String): List<CardAttempt> {
        val sql = "SELECT $COLS FROM card_attempts" + (if (where != null) " WHERE $where" else "") + " ORDER BY utc_ms, uid"
        return db.rawQuery(sql, args).use { c ->
            val out = ArrayList<CardAttempt>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) out += c.toAttempt()
            out
        }
    }

    private fun Cursor.toAttempt() = CardAttempt(
        uid = getString(0), author = getString(1), cardId = getString(2), deck = getString(3), utcMs = getLong(4),
        day = getLong(5), zoneId = getString(6), firstCorrect = getInt(7) != 0, paidMs = getLong(8),
    )
}

/** Чистая логика карточек, без Android: её проверяют юнит-тесты. */
object CardsMath {
    /** За верный первый ответ на карточку, ещё не оплаченную сегодня. */
    const val REWARD_MS = 10_000L

    /** Потолок минут за карточки за учётные сутки — на оба телефона вместе. */
    const val DAY_CAP_MS = 300_000L

    /** Карточек в сессии (сначала к повторению, затем новые). */
    const val SESSION_SIZE = 20

    /** Интервалы повторения, дней: верный ответ поднимает на ступень, ошибка — снова на первую. */
    val INTERVALS = listOf(1, 3, 7, 14, 30)

    /** Сколько раз карточка после ошибки возвращается в ту же сессию. */
    const val MAX_RETRIES = 3

    /** Через сколько карточек возвращается ошибочная. */
    const val RETRY_GAP = 3

    private val CARD_ID = Regex("^[a-z]{2,4}:[a-z0-9_+x-]{1,48}$")

    /** Ключ операции журнала за попытку [attemptUid]. */
    fun rewardKey(attemptUid: String): String = "card:$attemptUid"

    /** id карточки допустим (для приёма фактов со второго телефона). */
    fun isValidCardId(id: String): Boolean = CARD_ID.matches(id)

    // ---------- Ответы ----------

    private val ARTICLES = listOf("to ", "the ", "a ", "an ")

    /**
     * Нормализация ответа: нижний регистр, ё → е, знаки препинания и кавычки — пробелом, пробелы — одним;
     * для английского ещё без частицы и артиклей в начале (to, the, a, an).
     */
    fun normalize(raw: String, english: Boolean): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase(Locale.ROOT)) {
            when {
                ch == 'ё' -> sb.append('е')
                ch == '\'' || ch == '’' -> Unit
                ch.isLetterOrDigit() || ch == '-' -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        var s = sb.toString().trim().replace(Regex("\\s+"), " ")
        if (english) {
            var changed = true
            while (changed) {
                changed = false
                for (a in ARTICLES) if (s.startsWith(a) && s.length > a.length) {
                    s = s.substring(a.length)
                    changed = true
                }
            }
        }
        return s
    }

    /** Английский ли ожидается ответ на карточку (направление RU→EN). */
    private fun expectsEnglish(card: Card): Boolean = card.id.startsWith("ru:")

    /** Верен ли ответ [raw] на карточку [card]: число — по значению, слова — после [normalize] среди допустимых. */
    fun isCorrect(card: Card, raw: String): Boolean {
        if (card.deck.numeric) {
            val digits = raw.replace(Regex("\\s"), "")
            if (!digits.matches(Regex("^\\d{1,5}$"))) return false
            return card.answers.any { it.toIntOrNull() == digits.toInt() }
        }
        val english = expectsEnglish(card)
        val given = normalize(raw, english)
        if (given.isEmpty()) return false
        return card.answers.any { normalize(it, english) == given }
    }

    /** Правильный ответ для показа после ошибки: «яблоко», «быстрый, быстро». */
    fun answerText(card: Card): String = card.answers.take(3).joinToString(", ")

    // ---------- Повторение ----------

    /** Следующее состояние карточки после первого ответа в сутки [day]. */
    fun next(s: CardState?, day: Long, correct: Boolean): CardState = when {
        !correct -> CardState(0, day + INTERVALS[0], day)
        s == null -> CardState(0, day + INTERVALS[0], day)
        // Ответ раньше срока (второй телефон в тот же день) ступень не поднимает.
        day < s.dueDay -> s.copy(lastDay = maxOf(s.lastDay, day))
        else -> {
            val step = minOf(s.step + 1, INTERVALS.lastIndex)
            CardState(step, day + INTERVALS[step], day)
        }
    }

    /** Расписание по истории попыток обоих телефонов (по суткам и времени): id карточки → состояние. */
    fun schedule(attempts: List<CardAttempt>): Map<String, CardState> {
        val out = HashMap<String, CardState>()
        for (a in attempts.sortedWith(compareBy({ it.day }, { it.utcMs }, { it.uid }))) {
            out[a.cardId] = next(out[a.cardId], a.day, a.firstCorrect)
        }
        return out
    }

    /** Карточки колоды, которые пора повторить в сутки [today]: раньше срок — раньше в списке. */
    fun due(cards: List<Card>, states: Map<String, CardState>, today: Long): List<Card> =
        cards.filter { (states[it.id]?.dueDay ?: Long.MAX_VALUE) <= today }
            .sortedBy { states.getValue(it.id).dueDay }

    /** Новые карточки колоды по порядку: без истории; перевод RU→EN — только после EN→RU того же слова. */
    fun fresh(cards: List<Card>, states: Map<String, CardState>): List<Card> =
        cards.filter { it.id !in states && (it.requires == null || it.requires in states) }

    /** Сессия: до [size] карточек — сначала к повторению, затем новые. */
    fun pickSession(cards: List<Card>, states: Map<String, CardState>, today: Long, size: Int = SESSION_SIZE): List<String> =
        (due(cards, states, today) + fresh(cards, states)).take(size).map { it.id }

    // ---------- Награда ----------

    /** Дневной потолок одного телефона: в паре — половина общего (без связи два телефона его не превысят). */
    fun phoneCapMs(paired: Boolean): Long = if (paired) DAY_CAP_MS / 2 else DAY_CAP_MS

    /** Сколько начислено попытками автора [author] (своими — на этом телефоне). */
    fun paidBy(attempts: List<CardAttempt>, author: String): Long =
        attempts.filter { it.author == author }.sumOf { it.paidMs.coerceAtLeast(0L) }

    /**
     * Награда за первый ответ на карточку [cardId]: [dayAttempts] — все попытки этих учётных суток на обоих телефонах
     * (свои и копии второго), [me] — этот телефон. 10 с — если ответ верный, карточку сегодня ещё не оплачивали
     * и не исчерпан потолок этого телефона ([phoneCapMs]) и общий ([DAY_CAP_MS]).
     */
    fun reward(cardId: String, correct: Boolean, me: String, dayAttempts: List<CardAttempt>, paired: Boolean): CardReward {
        if (!correct) return CardReward(0L, CardRewardReason.WRONG)
        if (dayAttempts.any { it.cardId == cardId && it.paidMs > 0L }) return CardReward(0L, CardRewardReason.ALREADY_PAID)
        val all = dayAttempts.sumOf { it.paidMs.coerceAtLeast(0L) }
        val left = minOf(phoneCapMs(paired) - paidBy(dayAttempts, me), DAY_CAP_MS - all)
        if (left <= 0L) return CardReward(0L, CardRewardReason.CAP_REACHED)
        return CardReward(minOf(REWARD_MS, left), CardRewardReason.PAID)
    }

    // ---------- Тексты ----------

    /** «1 мин 10 с», «2 мин 30 с», «5 мин», «40 с», «0 с». */
    fun durationText(ms: Long): String {
        val sec = ms.coerceAtLeast(0L) / 1000
        val m = sec / 60
        val s = sec % 60
        return when {
            m == 0L -> "$s с"
            s == 0L -> "$m мин"
            else -> "$m мин $s с"
        }
    }

    /** «Сегодня заработано 1 мин 10 с из 2 мин 30 с». */
    fun earnedLine(earnedMs: Long, capMs: Long): String =
        "Сегодня заработано ${durationText(earnedMs)} из ${durationText(capMs)}"

    /** Итог ответа: «+10 с», «Без минут: карточка уже оплачена сегодня» и т. п. */
    fun rewardText(r: CardReward): String = when (r.reason) {
        CardRewardReason.PAID -> "+${durationText(r.paidMs)}"
        CardRewardReason.WRONG -> "Без минут: ответ неверный"
        CardRewardReason.ALREADY_PAID -> "Без минут: карточка уже оплачена сегодня"
        CardRewardReason.CAP_REACHED -> "Без минут: потолок на сегодня, знание растёт"
        CardRewardReason.PRACTICE -> "Повтор — без минут"
    }

    /** «К повторению 12 · новых 380». */
    fun deckLine(due: Int, fresh: Int): String = "К повторению $due · новых $fresh"

    /** «карточка», «карточки», «карточек». */
    fun cardsWord(n: Long): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> "карточек"
            m10 == 1L -> "карточка"
            m10 in 2..4 -> "карточки"
            else -> "карточек"
        }
    }
}

/**
 * Сессия карточек (чистое состояние, без Android). [cardIds] — карточки сессии по порядку, [queue] — что впереди
 * (первые показы и повторы после ошибки), [firstResults] — первый ответ на карточку (верный или нет), [retries] —
 * сколько раз карточка уже возвращалась, [paidMs] — сколько начислено за сессию.
 */
data class CardSession(
    val cardIds: List<String>,
    val queue: List<String>,
    val firstResults: Map<String, Boolean> = emptyMap(),
    val retries: Map<String, Int> = emptyMap(),
    val paidMs: Long = 0L,
) {
    val current: String? get() = queue.firstOrNull()

    /** Текущая карточка показана впервые (её ответ записывается и может быть оплачен). */
    val isFirstShow: Boolean get() = current != null && current !in firstResults

    val done: Int get() = firstResults.size
    val total: Int get() = cardIds.size
    val correctFirst: Int get() = firstResults.count { it.value }
    val finished: Boolean get() = queue.isEmpty()

    /** «7/20» — сколько карточек отвечено впервые из всех в сессии. */
    val progressText: String get() = "$done/$total"

    /**
     * Ответ на текущую карточку. Ошибка возвращает её через [CardsMath.RETRY_GAP] карточек (не больше
     * [CardsMath.MAX_RETRIES] раз); повтор не меняет первый результат.
     */
    fun answered(correct: Boolean): CardSession {
        val id = current ?: return this
        val results = if (id in firstResults) firstResults else firstResults + (id to correct)
        var rest = queue.drop(1)
        var tries = retries
        val used = retries[id] ?: 0
        if (!correct && used < CardsMath.MAX_RETRIES) {
            val at = minOf(CardsMath.RETRY_GAP, rest.size)
            rest = rest.take(at) + id + rest.drop(at)
            tries = retries + (id to used + 1)
        }
        return copy(queue = rest, firstResults = results, retries = tries)
    }

    fun withPaid(ms: Long): CardSession = copy(paidMs = paidMs + ms.coerceAtLeast(0L))

    /** Строки для сохранения состояния экрана (поворот, раскрытие Fold). */
    fun save(): ArrayList<String> = arrayListOf(
        cardIds.joinToString(","),
        queue.joinToString(","),
        firstResults.entries.joinToString(",") { "${it.key}=${if (it.value) 1 else 0}" },
        retries.entries.joinToString(",") { "${it.key}=${it.value}" },
        paidMs.toString(),
    )

    companion object {
        fun start(ids: List<String>): CardSession = CardSession(ids, ids)

        /** Обратное [save]; повреждённое — null. */
        fun restore(saved: List<String>?): CardSession? = runCatching {
            if (saved == null || saved.size != 5) return null
            fun list(s: String) = if (s.isEmpty()) emptyList() else s.split(',')
            fun pairs(s: String) = list(s).associate { val (k, v) = it.split('='); k to v }
            CardSession(
                cardIds = list(saved[0]),
                queue = list(saved[1]),
                firstResults = pairs(saved[2]).mapValues { it.value == "1" },
                retries = pairs(saved[3]).mapValues { it.value.toInt() },
                paidMs = saved[4].toLong(),
            )
        }.getOrNull()
    }
}

/** Колоды: карточки задаются кодом, их id не меняются (по ним копится история). */
object CardDecks {
    fun cards(deck: CardDeck): List<Card> = when (deck) {
        CardDeck.ENGLISH -> english
        CardDeck.MULTIPLY -> multiply
        CardDeck.MENTAL -> mental
    }

    /** Карточка по id; неизвестная (например, из более новой версии на втором телефоне) — null. */
    fun byId(id: String): Card? = all[id]

    private val all: Map<String, Card> by lazy {
        CardDeck.entries.flatMap { cards(it) }.associateBy { it.id }
    }

    /** id слова: английское без частицы и артикля, пробелы — «_» (`good_morning`). */
    fun slug(en: String): String = CardsMath.normalize(en, english = true).replace(' ', '_').replace("-", "")

    /** Английские слова: EN→RU (`en:…`) по порядку частоты, затем RU→EN (`ru:…`) того же слова. */
    val english: List<Card> by lazy {
        val forward = ArrayList<Card>()
        val back = ArrayList<Card>()
        for (line in WORDS) {
            val (en, ru) = line.split('|').let { it[0].split('/') to it[1].split('/') }
            val slug = slug(en[0])
            forward += Card("en:$slug", CardDeck.ENGLISH, en[0], "Переведи на русский", ru)
            back += Card("ru:$slug", CardDeck.ENGLISH, ru[0], "Переведи на английский", en, requires = "en:$slug")
        }
        forward + back
    }

    /** Таблица умножения 2–9 × 2–9 (`mul:7x8`). */
    val multiply: List<Card> by lazy {
        (2..9).flatMap { a -> (2..9).map { b -> Card("mul:${a}x$b", CardDeck.MULTIPLY, "$a × $b", "Сколько будет?", listOf("${a * b}")) } }
    }

    /**
     * Устный счёт в пределах 100: сложение (`add:47+38`) и вычитание (`sub:83-29`), по очереди. Набор задан
     * детерминированно (свой генератор с постоянным зерном), ответ вычисляется из id — у всех установок одинаков.
     */
    val mental: List<Card> by lazy {
        var seed = 20_261_114L
        fun next(n: Int): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) % n).toInt()
        }
        val adds = LinkedHashSet<String>()
        val subs = LinkedHashSet<String>()
        var guard = 0
        while ((adds.size < MENTAL_EACH || subs.size < MENTAL_EACH) && guard++ < 100_000) {
            val a = 11 + next(79) // 11..89
            val b = 11 + next(100 - a - 10) // 11..(100 − a)
            if (adds.size < MENTAL_EACH && a % 10 != 0 && b % 10 != 0 && a + b <= 100) adds += "$a+$b"
            val c = 20 + next(81) // 20..100
            val d = 11 + next(c - 11) // 11..(c − 1)
            if (subs.size < MENTAL_EACH && d % 10 != 0 && c % 10 != 0 && d < c) subs += "$c-$d"
        }
        val a = adds.map { mentalCard("add:$it")!! }
        val s = subs.map { mentalCard("sub:$it")!! }
        a.zip(s).flatMap { listOf(it.first, it.second) }
    }

    /** Сколько примеров каждого вида в «Устном счёте». */
    const val MENTAL_EACH = 60

    /** Карточка счёта по id: `add:47+38` → «47 + 38», ответ 85; неверный id — null. */
    fun mentalCard(id: String): Card? {
        val m = Regex("^(add|sub):(\\d{1,3})([+-])(\\d{1,3})$").matchEntire(id) ?: return null
        val (kind, x, op, y) = m.destructured
        val a = x.toInt()
        val b = y.toInt()
        val add = kind == "add"
        if (add != (op == "+")) return null
        val result = if (add) a + b else a - b
        if (result < 0 || result > 100) return null
        return Card(id, CardDeck.MENTAL, "$a ${if (add) "+" else "−"} $b", "Сколько будет?", listOf("$result"))
    }

    /**
     * 200 частых слов и выражений A1–A2: «английский/варианты|русский/варианты». Первый английский вариант — id
     * и задание EN→RU, первый русский — задание RU→EN; остальные — тоже верные ответы.
     */
    private val WORDS = listOf(
        "apple|яблоко", "water|вода", "bread|хлеб", "milk|молоко", "tea|чай", "coffee|кофе", "egg|яйцо",
        "fish|рыба", "meat|мясо", "cheese|сыр", "sugar|сахар", "salt|соль", "soup|суп", "juice|сок",
        "cake|торт/пирожное/кекс", "dog|собака/пёс", "cat|кошка/кот", "bird|птица",
        "house/home|дом", "room|комната", "kitchen|кухня", "window|окно", "door|дверь", "table|стол", "chair|стул",
        "bed|кровать", "book|книга", "pen|ручка", "school|школа", "teacher|учитель/учительница",
        "student|студент/студентка/ученик/ученица", "friend|друг/подруга", "family|семья", "mother/mom/mum|мать/мама",
        "father/dad|отец/папа", "brother|брат", "sister|сестра", "son|сын", "daughter|дочь/дочка", "child|ребёнок",
        "man|мужчина", "woman|женщина", "boy|мальчик", "girl|девочка/девушка", "name|имя", "city/town|город",
        "street|улица", "car|машина/автомобиль", "bus|автобус", "train|поезд", "plane/airplane|самолёт",
        "ticket|билет", "money|деньги", "shop/store|магазин", "job/work|работа", "day|день", "night|ночь",
        "morning|утро", "evening|вечер", "week|неделя", "month|месяц", "year|год", "time|время", "hour|час",
        "minute|минута", "today|сегодня", "tomorrow|завтра", "yesterday|вчера", "now|сейчас/теперь",
        "always|всегда", "never|никогда", "often|часто", "sometimes|иногда", "here|здесь/тут", "there|там",
        "sun|солнце", "moon|луна", "star|звезда", "sky|небо", "rain|дождь", "snow|снег", "wind|ветер",
        "tree|дерево", "flower|цветок", "river|река", "sea|море", "mountain|гора", "forest|лес", "head|голова",
        "hand/arm|рука", "eye|глаз", "ear|ухо", "nose|нос", "mouth|рот", "leg/foot|нога", "heart|сердце",
        "colour/color|цвет", "red|красный", "blue|синий/голубой", "green|зелёный", "yellow|жёлтый",
        "black|чёрный", "white|белый", "big/large|большой/крупный", "small/little|маленький/небольшой",
        "new|новый", "old|старый", "good|хороший/хорошо", "bad|плохой/плохо", "hot|горячий/жаркий/жарко",
        "cold|холодный/холодно", "happy|счастливый/радостный", "sad|грустный/печальный", "fast/quick|быстрый/быстро",
        "slow|медленный/медленно", "easy|лёгкий/простой/легко", "difficult/hard|трудный/сложный/трудно/сложно",
        "beautiful|красивый", "long|длинный/долгий", "short|короткий", "young|молодой", "tired|усталый/уставший",
        "hungry|голодный", "rich|богатый","clean|чистый", "dirty|грязный", "full|полный",
        "empty|пустой", "to open|открывать/открыть", "to close|закрывать/закрыть", "to go|идти/ходить/ехать/ездить",
        "to come|приходить/прийти/приезжать/приехать", "to see|видеть/увидеть", "to look/to watch|смотреть/посмотреть",
        "to hear|слышать/услышать", "to listen|слушать/послушать", "to speak/to talk|говорить/разговаривать",
        "to say/to tell|сказать/рассказать/рассказывать", "to read|читать/прочитать", "to write|писать/написать",
        "to eat|есть/кушать/съесть", "to drink|пить/выпить", "to sleep|спать", "to run|бегать/бежать",
        "to walk|гулять/ходить пешком/идти пешком", "to sit|сидеть", "to stand|стоять", "to buy|покупать/купить",
        "to sell|продавать/продать", "to give|давать/дать", "to take|брать/взять", "to help|помогать/помочь",
        "to know|знать", "to think|думать", "to want|хотеть", "to like|нравиться", "to love|любить",
        "to live|жить", "to work|работать", "to play|играть", "to learn/to study|учить/учиться/изучать",
        "to begin/to start|начинать/начать", "to finish/to end|заканчивать/закончить/кончать",
        "to wait|ждать/подождать", "to find|находить/найти", "to ask|спрашивать/спросить/просить",
        "to answer|отвечать/ответить", "to understand|понимать/понять", "to remember|помнить/запомнить/вспомнить",
        "to forget|забывать/забыть", "to swim|плавать", "to cook|готовить/варить", "to wash|мыть/стирать/помыть",
        "to sing|петь", "to dance|танцевать", "to fly|летать/лететь", "to travel|путешествовать",
        "hello/hi|привет/здравствуйте/здравствуй", "goodbye/bye|до свидания/пока", "thank you/thanks|спасибо",
        "please|пожалуйста", "yes|да", "no|нет", "sorry|извините/простите/извини/прости",
        "good morning|доброе утро", "good night|спокойной ночи", "how are you|как дела/как у тебя дела/как поживаешь",
        "weather|погода", "music|музыка", "game|игра", "phone/telephone|телефон", "computer|компьютер",
        "question|вопрос", "word|слово", "people|люди", "doctor|врач/доктор",
        "breakfast|завтрак", "lunch|обед", "dinner|ужин", "birthday|день рождения",
    )
}
