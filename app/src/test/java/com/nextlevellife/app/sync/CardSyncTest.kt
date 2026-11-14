package com.nextlevellife.app.sync

import com.nextlevellife.app.data.CardRewardReason
import com.nextlevellife.app.data.CardsMath
import com.nextlevellife.app.stats.StatsMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Обмен ответами на карточки (протокол 3): разовая оплата и потолок с учётом второго телефона, обрыв, восстановление. */
class CardSyncTest {
    private class Side(val error: Throwable?)

    private fun exchange(a: MemorySyncStore, b: MemorySyncStore, breakAfter: Int = Int.MAX_VALUE, pageSize: Int = 400): Pair<Side, Side> {
        val pair = MemoryChannelPair(a.deviceId, b.deviceId, breakAfter)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val fa = pool.submit(Callable { runCatching { SyncSession(a, pair.forA, pageSize = pageSize).run() } })
            val fb = pool.submit(Callable { runCatching { SyncSession(b, pair.forB, pageSize = pageSize).run() } })
            return Side(fa.get(30, TimeUnit.SECONDS).exceptionOrNull()) to Side(fb.get(30, TimeUnit.SECONDS).exceptionOrNull())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun ok(r: Pair<Side, Side>) {
        r.first.error?.let { throw AssertionError("A: $it", it) }
        r.second.error?.let { throw AssertionError("B: $it", it) }
    }

    private fun mul(i: Int) = "mul:${2 + i / 8}x${2 + i % 8}"

    @Test
    fun cardPaidOnceAcrossPhones() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        assertEquals(CardRewardReason.PAID, a.answerCard("mul:7x8", true).reason)
        ok(exchange(a, b))
        // Та же карточка в тот же день на втором телефоне — без минут; другая — с минутами.
        assertEquals(CardRewardReason.ALREADY_PAID, b.answerCard("mul:7x8", true).reason)
        assertEquals(CardRewardReason.PAID, b.answerCard("mul:7x9", true).reason)
        assertEquals(10_000L, a.cardEarned())
        assertEquals(10_000L, b.cardEarned())
        ok(exchange(a, b))
        // Попытки обоих телефонов — у обоих; начисления копии в свой кошелёк не попадают.
        assertEquals(a.cardAttempts().map { it.uid }.toSet(), b.cardAttempts().map { it.uid }.toSet())
        assertEquals(3, a.cardAttempts().size)
        assertEquals(10_000L, a.cardEarned())
        // Назавтра карточку можно оплатить снова.
        b.day += 1
        assertEquals(CardRewardReason.PAID, b.answerCard("mul:7x8", true).reason)
    }

    @Test
    fun offlinePhonesTogetherStayWithinDayCap() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        // Без связи весь день: каждый телефон в паре оплачивает не больше половины.
        repeat(40) { a.answerCard(mul(it), true) }
        repeat(40) { b.answerCard(mul(63 - it), true) }
        assertEquals(150_000L, a.cardEarned())
        assertEquals(150_000L, b.cardEarned())
        assertEquals(CardsMath.DAY_CAP_MS, a.cardEarned() + b.cardEarned())
        ok(exchange(a, b))
        assertEquals(CardRewardReason.CAP_REACHED, a.answerCard("add:47+38", true).reason)
        // Без пары — весь потолок на один телефон.
        val solo = MemorySyncStore("ccc")
        repeat(40) { solo.answerCard(mul(it), true, paired = false) }
        assertEquals(CardsMath.DAY_CAP_MS, solo.cardEarned())
    }

    @Test
    fun knowledgeCountsDistinctCorrectFromBothPhones() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        repeat(5) { a.answerCard(mul(it), true) }
        a.answerCard(mul(5), false)
        repeat(5) { b.answerCard(mul(it + 3), true) } // 3 из них — те же карточки
        ok(exchange(a, b))
        // Различные верные: mul(0..7) = 8 → 2 очка, у обоих телефонов одинаково.
        for (s in listOf(a, b)) {
            val k = StatsMath.knowledge(StatsMath.knowledgeInput(s.cardAttempts().filter { it.day == s.day }))
            assertEquals(2, k.points)
            assertEquals(8L, k.sources.single().amount)
        }
    }

    @Test
    fun brokenExchangeResumesWithoutDuplicates() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        repeat(50) { a.answerCard(mul(it), it % 4 != 0) }
        repeat(30) { b.answerCard("add:${11 + it}+11", true) }
        for (cut in listOf(3, 5, 8, 11)) {
            val r = exchange(a, b, breakAfter = cut, pageSize = 10)
            assertTrue(r.first.error != null || r.second.error != null)
        }
        ok(exchange(a, b, pageSize = 10))
        assertEquals(80, a.cardAttempts().size)
        assertEquals(a.facts.keys, b.facts.keys)
        // Повторная доставка тех же фактов ничего не меняет.
        val before = b.facts.toMap()
        b.applyFacts("aaa", a.facts.values.toList())
        assertEquals(before, b.facts)
    }

    @Test
    fun restoreKeepsPaidAttemptsAndUsedQuota() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        val backup = a.snapshot()
        repeat(15) { a.answerCard(mul(it), true) }
        assertEquals(150_000L, a.cardEarned())
        a.restoreFrom(backup)
        // Минуты после копии пропали вместе с журналом, но оплаченные попытки остались: потолок не обнуляется,
        // и оплаченные карточки второй раз не платятся.
        assertEquals(0L, a.cardEarned())
        assertEquals(15, a.cardAttempts().size)
        assertEquals(CardRewardReason.CAP_REACHED, a.answerCard("add:47+38", true).reason)
        assertEquals(CardRewardReason.ALREADY_PAID, a.answerCard(mul(0), true).reason)
        // Попытки прежнего поколения доходят до второго телефона, новые — тоже.
        ok(exchange(a, b))
        assertEquals(a.cardAttempts().map { it.uid }.toSet(), b.cardAttempts().map { it.uid }.toSet())
        assertEquals(CardRewardReason.ALREADY_PAID, b.answerCard(mul(1), true).reason)
    }

    @Test
    fun acceptRules() {
        val me = "aaa"
        val epoch = "f-cur"
        val good = CardAttemptFact("a-1", "bbb", "e", 1, "mul:7x8", "mul", 1_700_000_000_000, 19_676, "Europe/Moscow", true, 10_000)
        assertTrue(SyncRules.acceptFact(good, me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(good.copy(author = "ccc"), me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(good.copy(author = me, epoch = epoch), me, epoch, "bbb"))
        assertTrue(SyncRules.acceptFact(good.copy(author = me, epoch = "old"), me, epoch, "bbb"))
        // Оплата: не больше одной награды и только за верный ответ.
        assertFalse(SyncRules.acceptFact(good.copy(paidMs = 10_001), me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(good.copy(paidMs = -1), me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(good.copy(firstCorrect = false), me, epoch, "bbb"))
        assertTrue(SyncRules.acceptFact(good.copy(firstCorrect = false, paidMs = 0), me, epoch, "bbb"))
        for (bad in listOf("", "mul", "MUL:7x8", "mul:7 8", "mul:../x", "x".repeat(70))) {
            assertFalse(bad, SyncRules.acceptFact(good.copy(cardId = bad), me, epoch, "bbb"))
        }
        assertFalse(SyncRules.acceptFact(good.copy(uid = "../a"), me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(good.copy(zoneId = "Нет/Такого"), me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(good.copy(utcMs = 0), me, epoch, "bbb"))
        // День должен согласоваться с моментом ответа (допуск — сутки).
        assertTrue(SyncRules.acceptFact(good.copy(day = 19_677), me, epoch, "bbb"))
        assertTrue(SyncRules.acceptFact(good.copy(day = 19_675), me, epoch, "bbb"))
        for (bad in listOf(19_678L, 19_674L, 0L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertFalse(bad.toString(), SyncRules.acceptFact(good.copy(day = bad), me, epoch, "bbb"))
        }
    }

    @Test
    fun codecRoundTripAndVersion() {
        assertEquals(3, SyncCodec.PROTOCOL_VERSION)
        val m = SyncMessage.Facts(
            listOf(
                CardAttemptFact("a1", "a", "e", 1, "en:apple", "en", 1_700_000_000_000, 19_700, "Europe/Moscow", true, 10_000),
                CardAttemptFact("a2", "a", "e", 2, "add:47+38", "calc", 1_700_000_100_000, 19_700, "UTC", false, 0),
                MealFact("m1", "b", "e2", 3, 1_700_000_200_000, 19_701, "UTC", false, null),
            ),
        )
        assertEquals(m, SyncCodec.decode(SyncCodec.encode(m)))
    }
}
