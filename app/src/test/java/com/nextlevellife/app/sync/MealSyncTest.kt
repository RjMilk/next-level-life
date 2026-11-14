package com.nextlevellife.app.sync

import com.nextlevellife.app.data.NutritionMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Обмен приёмами пищи и удалениями (протокол 2): в памяти, с повтором, обрывом и восстановлением копии. */
class MealSyncTest {
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

    private fun uids(s: MemorySyncStore) = s.visibleMeals().map { it.uid }.toSet()

    @Test
    fun mealsAndDeletesTravelBothWays() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        val m1 = a.addMeal("овсянка")
        val m2 = a.addMeal()
        val m3 = b.addMeal("суп")
        a.addSet(10)
        ok(exchange(a, b))
        assertEquals(setOf(m1.uid, m2.uid, m3.uid), uids(a))
        assertEquals(uids(a), uids(b))
        // Заметка и признак фото дошли как есть; автор — снимавший телефон.
        val copy = b.facts[m1.uid] as MealFact
        assertEquals("овсянка", copy.note)
        assertTrue(copy.hasPhoto)
        assertEquals("aaa", copy.author)

        // Удаление чужого приёма на втором телефоне доходит до автора.
        b.deleteMeal(m1.uid)
        a.deleteMeal(m3.uid)
        ok(exchange(a, b))
        assertEquals(setOf(m2.uid), uids(a))
        assertEquals(setOf(m2.uid), uids(b))
    }

    @Test
    fun repeatIsIdempotent() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        val m = a.addMeal()
        a.deleteMeal(m.uid)
        a.addMeal("кофе")
        ok(exchange(a, b))
        val before = b.facts.toMap()
        ok(exchange(a, b))
        ok(exchange(b, a))
        assertEquals(before, b.facts)
        assertEquals(3, a.facts.size)
        assertEquals(1, b.visibleMeals().size)
        // Повторная доставка тех же фактов напрямую ничего не меняет.
        b.applyFacts("aaa", a.facts.values.toList())
        assertEquals(before, b.facts)
    }

    @Test
    fun deleteArrivingBeforeMealHidesIt() {
        val a = MemorySyncStore("aaa")
        val meal = a.addMeal()
        val del = a.deleteMeal(meal.uid)
        val b = MemorySyncStore("bbb")
        // Сначала удаление (другой пакет), потом приём.
        b.applyFacts("aaa", listOf(del))
        b.applyFacts("aaa", listOf(meal))
        assertTrue(b.visibleMeals().isEmpty())
    }

    @Test
    fun brokenExchangeResumesWithoutDuplicates() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        val meals = List(60) { a.addMeal("приём $it") }
        meals.filterIndexed { i, _ -> i % 3 == 0 }.forEach { a.deleteMeal(it.uid) }
        repeat(40) { b.addMeal() }
        for (cut in listOf(3, 5, 8, 11)) {
            val r = exchange(a, b, breakAfter = cut, pageSize = 10)
            assertTrue(r.first.error != null || r.second.error != null)
        }
        ok(exchange(a, b, pageSize = 10))
        assertEquals(a.facts.keys, b.facts.keys)
        assertEquals(40 + 40, a.visibleMeals().size)
        assertEquals(uids(a), uids(b))
    }

    @Test
    fun restoreKeepsDeletesAndLostMealsComeBack() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        val kept = a.addMeal()
        val backup = a.snapshot()
        val lost = a.addMeal("после копии")
        ok(exchange(a, b))
        a.deleteMeal(kept.uid)
        a.restoreFrom(backup)
        // Восстановление переносит текущие приёмы и удаления: удалённый не воскресает.
        assertEquals(setOf(lost.uid), uids(a))
        ok(exchange(a, b))
        assertEquals(setOf(lost.uid), uids(b))
        assertEquals(setOf(lost.uid), uids(a))
        // Новые приёмы нового поколения тоже доходят.
        val fresh = a.addMeal()
        ok(exchange(a, b))
        assertTrue(fresh.uid in uids(b))
    }

    @Test
    fun acceptRules() {
        val me = "aaa"
        val epoch = "f-cur"
        val good = MealFact("m-1", "bbb", "e", 1, 1_700_000_000_000, 19_700, "Europe/Moscow", true, "суп")
        assertTrue(SyncRules.acceptFact(good, me, epoch, "bbb"))
        // Чужой третий автор — нет.
        assertFalse(SyncRules.acceptFact(good.copy(author = "ccc"), me, epoch, "bbb"))
        // Свой текущего поколения — подделка; свой прежнего — возврат потерянного.
        assertFalse(SyncRules.acceptFact(good.copy(author = me, epoch = epoch), me, epoch, "bbb"))
        assertTrue(SyncRules.acceptFact(good.copy(author = me, epoch = "old"), me, epoch, "bbb"))
        // uid идёт в имя файла: только безопасные символы.
        for (bad in listOf("../x", "a/b", "a.b", "", "a\\b", "-a", "x".repeat(65))) {
            assertFalse(bad, SyncRules.acceptFact(good.copy(uid = bad), me, epoch, "bbb"))
        }
        assertFalse(SyncRules.acceptFact(good.copy(zoneId = "Нет/Такого"), me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(good.copy(note = "x".repeat(NutritionMath.MAX_NOTE_CHARS + 1)), me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(good.copy(capturedUtcMs = 0), me, epoch, "bbb"))

        val del = MealDeleteFact("d-1", "bbb", "e", 2, "m-1", 19_700, 1)
        assertTrue(SyncRules.acceptFact(del, me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(del.copy(author = "ccc"), me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(del.copy(author = me, epoch = epoch), me, epoch, "bbb"))
        assertFalse(SyncRules.acceptFact(del.copy(mealUid = "../m"), me, epoch, "bbb"))
    }

    @Test
    fun codecRoundTripAndVersion() {
        assertTrue(SyncCodec.PROTOCOL_VERSION >= 2)
        val m = SyncMessage.Facts(
            listOf(
                MealFact("m1", "a", "e", 1, 1_700_000_000_000, 19_700, "Europe/Moscow", true, "гречка с курицей"),
                MealFact("m2", "a", "e", 2, 1_700_000_100_000, 19_700, "UTC", false, null),
                MealDeleteFact("d1", "b", "e2", 3, "m1", 19_701, 1_700_000_200_000),
            ),
        )
        assertEquals(m, SyncCodec.decode(SyncCodec.encode(m)))
    }

    @Test
    fun olderPeerVersionFailsWithClearReason() {
        val a = MemorySyncStore("aaa")
        val pair = MemoryChannelPair("aaa", "bbb")
        val pool = Executors.newSingleThreadExecutor()
        try {
            val f = pool.submit(Callable { runCatching { SyncSession(a, pair.forA).run() } })
            val hello = SyncCodec.decode(pair.forB.receive()) as SyncMessage.Hello
            pair.forB.send(SyncCodec.encode(hello.copy(protocol = 1, deviceId = "bbb", cursors = emptyList())))
            val e = f.get(10, TimeUnit.SECONDS).exceptionOrNull()
            assertNotNull(e)
            assertTrue(e is SyncProtocolException)
            assertTrue(e!!.message!!, "на втором телефоне старая версия приложения" in e.message!!)
        } finally {
            pool.shutdownNow()
        }
        assertTrue("на этом телефоне" in SyncRules.protocolMismatch(3, 2))
    }
}
