package com.nextlevellife.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Карточки: колоды, нормализация ответов, интервалы, выбор сессии, награда с потолком, сессия, схема 10. */
class CardsMathTest {
    private val me = "aaa"
    private val peer = "bbb"
    private val day = 20_000L

    private fun attempt(card: String, author: String = me, d: Long = day, correct: Boolean = true, paid: Long = 0L, t: Long = 0L) =
        CardAttempt("u-$card-$author-$d-$t", author, card, card.substringBefore(':'), d * 86_400_000L + t, d, "UTC", correct, paid)

    // ---------- Колоды ----------

    @Test
    fun decksAreStableAndUnique() {
        assertEquals(400, CardDecks.english.size)
        assertEquals(64, CardDecks.multiply.size)
        assertEquals(CardDecks.MENTAL_EACH * 2, CardDecks.mental.size)
        val all = CardDeck.entries.flatMap { CardDecks.cards(it) }
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (c in all) {
            assertTrue(c.id, CardsMath.isValidCardId(c.id))
            assertTrue(c.id, c.answers.isNotEmpty())
            // Каждый допустимый ответ принимается своей же карточкой.
            for (a in c.answers) assertTrue("${c.id}: $a", CardsMath.isCorrect(c, a))
            c.requires?.let { assertNotNull(it, CardDecks.byId(it)) }
        }
        // Задания не повторяются: по слову ясно, какой перевод нужен.
        val en = CardDecks.english.filter { it.id.startsWith("en:") }
        val ru = CardDecks.english.filter { it.id.startsWith("ru:") }
        assertEquals(200, en.size)
        assertEquals(en.size, en.map { it.prompt }.toSet().size)
        assertEquals(ru.size, ru.map { it.prompt }.toSet().size)
        // id не меняются: история попыток привязана к ним.
        assertEquals("en:apple", CardDecks.english.first().id)
        assertEquals("ru:apple", CardDecks.byId("ru:apple")!!.id)
        assertEquals("en:open", CardDecks.byId("en:open")!!.id)
        assertEquals("to open", CardDecks.byId("en:open")!!.prompt)
        assertEquals("en:good_morning", CardDecks.byId("en:good_morning")!!.id)
        assertEquals("56", CardDecks.byId("mul:7x8")!!.answers.single())
        assertEquals("7 × 8", CardDecks.byId("mul:7x8")!!.prompt)
    }

    @Test
    fun mentalArithmeticIsDeterministicAndWithin100() {
        val first = CardDecks.mental.map { it.id }
        assertEquals(first.take(4), listOf(first[0], first[1], first[2], first[3]))
        assertTrue(first[0].startsWith("add:") && first[1].startsWith("sub:"))
        for (c in CardDecks.mental) {
            val n = c.answers.single().toInt()
            assertTrue(c.id, n in 0..100)
            assertEquals(c, CardDecks.mentalCard(c.id))
        }
        assertEquals("85", CardDecks.mentalCard("add:47+38")!!.answers.single())
        assertEquals("54", CardDecks.mentalCard("sub:83-29")!!.answers.single())
        assertEquals("83 − 29", CardDecks.mentalCard("sub:83-29")!!.prompt)
        assertNull(CardDecks.mentalCard("add:47-38"))
        assertNull(CardDecks.mentalCard("add:70+38"))
        assertNull(CardDecks.mentalCard("sub:3-29"))
    }

    // ---------- Ответы ----------

    @Test
    fun normalization() {
        assertEquals("open", CardsMath.normalize("  To   Open! ", english = true))
        assertEquals("apple", CardsMath.normalize("The apple.", english = true))
        assertEquals("apple", CardsMath.normalize("an Apple", english = true))
        assertEquals("доброе утро", CardsMath.normalize(" Доброе,  утро ", english = false))
        assertEquals("зеленый", CardsMath.normalize("Зелёный", english = false))
        // Русская «a» и прочее без артиклей: для русского ответа ничего не срезается.
        assertEquals("a", CardsMath.normalize("a", english = true))
        assertEquals("to", CardsMath.normalize("to", english = true))
    }

    @Test
    fun answerChecking() {
        val apple = CardDecks.byId("en:apple")!!
        assertTrue(CardsMath.isCorrect(apple, "Яблоко"))
        assertTrue(CardsMath.isCorrect(apple, " яблоко. "))
        assertFalse(CardsMath.isCorrect(apple, "груша"))
        assertFalse(CardsMath.isCorrect(apple, ""))
        val back = CardDecks.byId("ru:apple")!!
        assertTrue(CardsMath.isCorrect(back, "an apple"))
        assertTrue(CardsMath.isCorrect(back, "APPLE"))
        val green = CardDecks.byId("en:green")!!
        assertTrue(CardsMath.isCorrect(green, "зеленый"))
        val big = CardDecks.byId("ru:big")!!
        assertTrue(CardsMath.isCorrect(big, "large"))
        val open = CardDecks.byId("ru:open")!!
        assertTrue(CardsMath.isCorrect(open, "open"))
        assertTrue(CardsMath.isCorrect(open, "to open"))
        val mul = CardDecks.byId("mul:7x8")!!
        assertTrue(CardsMath.isCorrect(mul, "56"))
        assertTrue(CardsMath.isCorrect(mul, " 5 6 "))
        assertFalse(CardsMath.isCorrect(mul, "57"))
        assertFalse(CardsMath.isCorrect(mul, "56a"))
        assertFalse(CardsMath.isCorrect(mul, ""))
        assertEquals("быстрый, быстро", CardsMath.answerText(CardDecks.byId("en:fast")!!))
    }

    // ---------- Интервалы ----------

    @Test
    fun intervalsGrowAndResetOnError() {
        var s: CardState? = null
        var d = 0L
        val dues = ArrayList<Long>()
        repeat(7) {
            s = CardsMath.next(s, d, correct = true)
            dues += s!!.dueDay - d
            d = s!!.dueDay
        }
        assertEquals(listOf(1L, 3L, 7L, 14L, 30L, 30L, 30L), dues)
        // Ошибка — снова на 1 день, следующий верный — 3 дня.
        s = CardsMath.next(s, d, correct = false)
        assertEquals(CardState(0, d + 1, d), s)
        s = CardsMath.next(s, d + 1, correct = true)
        assertEquals(d + 1 + 3, s!!.dueDay)
        // Ответ раньше срока ступень не поднимает.
        val early = CardsMath.next(s, d + 2, correct = true)
        assertEquals(s!!.step, early.step)
        assertEquals(s!!.dueDay, early.dueDay)
    }

    @Test
    fun scheduleMergesBothPhones() {
        // Оба телефона ответили на одну карточку в один день: ступень одна, повтор через день.
        val states = CardsMath.schedule(
            listOf(attempt("mul:2x2", me, t = 5), attempt("mul:2x2", peer, t = 9), attempt("mul:2x3", correct = false)),
        )
        assertEquals(CardState(0, day + 1, day), states["mul:2x2"])
        assertEquals(day + 1, states.getValue("mul:2x3").dueDay)
        // Через день — верно: 3 дня.
        val later = CardsMath.schedule(
            listOf(attempt("mul:2x2", me), attempt("mul:2x2", peer, d = day + 1)),
        )
        assertEquals(day + 1 + 3, later.getValue("mul:2x2").dueDay)
    }

    @Test
    fun sessionTakesDueFirstThenNew() {
        val cards = CardDecks.multiply
        val history = listOf(
            attempt("mul:5x5", d = day - 3), // повтор через 1 день: срок давно прошёл
            attempt("mul:2x2", d = day - 1), // срок — сегодня
            attempt("mul:2x3", d = day), // срок — завтра
        )
        val states = CardsMath.schedule(history)
        val ids = CardsMath.pickSession(cards, states, day)
        assertEquals(CardsMath.SESSION_SIZE, ids.size)
        assertEquals(listOf("mul:5x5", "mul:2x2"), ids.take(2))
        assertFalse("mul:2x3" in ids)
        // Новые — по порядку колоды, без уже начатых.
        assertEquals("mul:2x4", ids[2])
        assertEquals(2, CardsMath.due(cards, states, day).size)
        assertEquals(cards.size - 3, CardsMath.fresh(cards, states).size)
        // RU→EN становится новой только после EN→RU того же слова.
        val en = CardDecks.english
        assertTrue(CardsMath.fresh(en, emptyMap()).none { it.id.startsWith("ru:") })
        val afterApple = CardsMath.schedule(listOf(attempt("en:apple", d = day - 5)))
        assertTrue("ru:apple" in CardsMath.fresh(en, afterApple).map { it.id })
        assertEquals("en:apple", CardsMath.pickSession(en, afterApple, day).first())
    }

    // ---------- Награда ----------

    @Test
    fun rewardOncePerCardPerDayAcrossPhones() {
        assertEquals(CardReward(10_000, CardRewardReason.PAID), CardsMath.reward("mul:7x8", true, me, emptyList(), paired = true))
        assertEquals(CardRewardReason.WRONG, CardsMath.reward("mul:7x8", false, me, emptyList(), true).reason)
        // Оплачена на втором телефоне — здесь без минут.
        val peerPaid = listOf(attempt("mul:7x8", peer, paid = 10_000))
        assertEquals(CardReward(0, CardRewardReason.ALREADY_PAID), CardsMath.reward("mul:7x8", true, me, peerPaid, true))
        // Верный ответ без оплаты (потолок) не мешает оплатить карточку позже.
        val unpaid = listOf(attempt("mul:7x8", peer, paid = 0))
        assertEquals(CardRewardReason.PAID, CardsMath.reward("mul:7x8", true, me, unpaid, true).reason)
    }

    @Test
    fun dailyCapIsSplitWhenPaired() {
        assertEquals(150_000L, CardsMath.phoneCapMs(paired = true))
        assertEquals(300_000L, CardsMath.phoneCapMs(paired = false))
        val mine15 = (1..15).map { attempt("add:${10 + it}+11", paid = 10_000) }
        assertEquals(CardRewardReason.CAP_REACHED, CardsMath.reward("mul:2x2", true, me, mine15, paired = true).reason)
        assertEquals(CardRewardReason.PAID, CardsMath.reward("mul:2x2", true, me, mine15, paired = false).reason)
        // Без пары: 300 с на телефон, но общий потолок с попытками второго телефона (давняя пара) — тоже 300 с.
        val peer20 = (1..20).map { attempt("sub:${50 + it}-11", peer, paid = 10_000) }
        assertEquals(CardRewardReason.CAP_REACHED, CardsMath.reward("mul:2x2", true, me, mine15 + peer20, paired = false).reason)
        val peer14 = peer20.take(14)
        assertEquals(CardReward(10_000, CardRewardReason.PAID), CardsMath.reward("mul:2x2", true, me, mine15 + peer14, paired = false))
        // Остаток меньше награды — доплачивается остаток.
        val odd = mine15.take(14) + attempt("mul:9x9", paid = 5_000)
        assertEquals(CardReward(5_000, CardRewardReason.PAID), CardsMath.reward("mul:2x2", true, me, odd, paired = true))
    }

    @Test
    fun texts() {
        assertEquals("Сегодня заработано 1 мин 10 с из 2 мин 30 с", CardsMath.earnedLine(70_000, 150_000))
        assertEquals("Сегодня заработано 0 с из 5 мин", CardsMath.earnedLine(0, 300_000))
        assertEquals("+10 с", CardsMath.rewardText(CardReward(10_000, CardRewardReason.PAID)))
        assertEquals("К повторению 3 · новых 61", CardsMath.deckLine(3, 61))
        assertEquals("card:abc", CardsMath.rewardKey("abc"))
        assertEquals(listOf("карточка", "карточки", "карточек", "карточек"), listOf(1L, 3L, 5L, 11L).map(CardsMath::cardsWord))
    }

    // ---------- Сессия ----------

    @Test
    fun sessionRequeuesMistakesWithoutChangingFirstResult() {
        var s = CardSession.start(listOf("a:1", "a:2", "a:3", "a:4", "a:5"))
        assertEquals("0/5", s.progressText)
        assertTrue(s.isFirstShow)
        s = s.answered(false) // a:1 — ошибка: вернётся через 3 карточки
        assertEquals(listOf("a:2", "a:3", "a:4", "a:1", "a:5"), s.queue)
        assertEquals("1/5", s.progressText)
        s = s.answered(true).answered(true).answered(true) // a:2, a:3, a:4
        assertEquals("a:1", s.current)
        assertFalse(s.isFirstShow)
        s = s.answered(true) // повтор — первый результат остаётся «неверно»
        assertEquals(false, s.firstResults["a:1"])
        s = s.answered(true)
        assertTrue(s.finished)
        assertEquals(4, s.correctFirst)
        assertEquals("5/5", s.progressText)
    }

    @Test
    fun sessionRetriesAreLimitedAndStateSurvivesSave() {
        var s = CardSession.start(listOf("a:1"))
        repeat(CardsMath.MAX_RETRIES + 1) { s = s.answered(false) }
        assertTrue(s.finished)
        assertEquals(CardsMath.MAX_RETRIES, s.retries["a:1"])
        val mid = CardSession.start(listOf("mul:2x2", "add:47+38", "sub:83-29")).answered(false).withPaid(10_000)
        assertEquals(mid, CardSession.restore(mid.save()))
        assertNull(CardSession.restore(listOf("x")))
        assertNull(CardSession.restore(null))
    }

    // ---------- Схема ----------

    @Test
    fun migration10AddsCardAttempts() {
        assertEquals(10, Diary.SCHEMA_VERSION)
        val sql = Diary.MIGRATIONS[9].joinToString("\n")
        for (part in listOf(
            "CREATE TABLE card_attempts", "uid TEXT PRIMARY KEY NOT NULL", "author TEXT NOT NULL", "epoch TEXT NOT NULL",
            "aseq INTEGER NOT NULL", "card_id TEXT NOT NULL", "deck TEXT NOT NULL", "utc_ms INTEGER NOT NULL",
            "accounting_day INTEGER NOT NULL", "zone_id TEXT NOT NULL", "first_correct INTEGER NOT NULL",
            "paid_ms INTEGER NOT NULL DEFAULT 0",
            "CREATE INDEX idx_card_attempts_author ON card_attempts(author, epoch, aseq)",
        )) assertTrue(part, part in sql)
        // Только новая таблица: прежние данные шаг не трогает. Без UPSERT (Android 10: SQLite 3.22).
        assertTrue(Diary.MIGRATIONS[9].all { it.startsWith("CREATE ") })
        assertTrue("ON CONFLICT" !in sql && "RETURNING" !in sql)
        assertTrue(BackupMath.requiredTables(10).contains("card_attempts"))
        assertFalse(BackupMath.requiredTables(9).contains("card_attempts"))
    }
}
