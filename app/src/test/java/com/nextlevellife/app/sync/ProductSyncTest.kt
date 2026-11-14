package com.nextlevellife.app.sync

import com.nextlevellife.app.data.ProductsMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Обмен своими продуктами и позициями приёмов (протокол 4): в памяти, с порядком прихода, удалениями и копией. */
class ProductSyncTest {
    private fun exchange(a: MemorySyncStore, b: MemorySyncStore, breakAfter: Int = Int.MAX_VALUE, pageSize: Int = 400): List<Throwable?> {
        val pair = MemoryChannelPair(a.deviceId, b.deviceId, breakAfter)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val fa = pool.submit(Callable { runCatching { SyncSession(a, pair.forA, pageSize = pageSize).run() } })
            val fb = pool.submit(Callable { runCatching { SyncSession(b, pair.forB, pageSize = pageSize).run() } })
            return listOf(fa.get(30, TimeUnit.SECONDS).exceptionOrNull(), fb.get(30, TimeUnit.SECONDS).exceptionOrNull())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun ok(errors: List<Throwable?>) {
        errors.forEachIndexed { i, e -> if (e != null) throw AssertionError("${"AB"[i]}: $e", e) }
    }

    @Test
    fun productAndEntryTravelBothWays() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        val meal = a.addMeal("обед")
        val rice = a.saveProduct("Рис", barcode = "4006381333931", kcal = 130, proteinDg = 27, fatDg = 3, carbsDg = 280, portionG = 200)
        val e1 = a.addEntry(meal.uid, rice) // порция по умолчанию
        ok(exchange(a, b))
        assertEquals(listOf("Рис"), b.liveProducts().map { it.name })
        assertEquals("4006381333931", b.liveProducts().single().barcode)
        val line = b.visibleEntries().single()
        assertEquals(e1.uid, line.entry.uid)
        assertEquals(200, line.entry.grams)
        assertEquals("Рис", line.product?.name)
        assertEquals("260 ккал", ProductsMath.kcalText(ProductsMath.totals(b.visibleEntries()))!!.replace(' ', ' '))

        // Второй телефон добавляет позицию к тому же приёму и правит карточку: прошлая позиция не меняется.
        val rice2 = b.saveProduct("Рис круглый", productUid = rice.productUid, kcal = 330, portionG = 100)
        val e2 = b.addEntry(meal.uid, rice2, grams = 50)
        ok(exchange(a, b))
        assertEquals(listOf("Рис круглый"), a.liveProducts().map { it.name })
        val lines = a.visibleEntries().associateBy { it.entry.uid }
        assertEquals("Рис", lines[e1.uid]!!.product!!.name)
        assertEquals(130, lines[e1.uid]!!.product!!.kcal)
        assertEquals("Рис круглый", lines[e2.uid]!!.product!!.name)
        assertEquals(50, lines[e2.uid]!!.entry.grams)
        assertEquals(a.visibleEntries(), b.visibleEntries())
    }

    @Test
    fun entryBeforeProductAndMealIsKept() {
        val a = MemorySyncStore("aaa")
        val meal = a.addMeal()
        val p = a.saveProduct("Кефир", kcal = 50)
        val e = a.addEntry(meal.uid, p, grams = 250)
        val b = MemorySyncStore("bbb")
        // Сначала позиция (отдельным пакетом): ни приёма, ни карточки ещё нет — позиция хранится, но не видна.
        b.applyFacts("aaa", listOf(e))
        assertTrue(e.uid in b.facts)
        assertTrue(b.visibleEntries().isEmpty())
        // Пришёл приём — позиция видна, карточки пока нет: подписана и не считается.
        b.applyFacts("aaa", listOf(meal))
        val early = b.visibleEntries().single()
        assertNull(early.product)
        assertEquals("Продукт ещё не пришёл со второго телефона", ProductsMath.entryName(early))
        assertEquals(0, ProductsMath.totals(b.visibleEntries()).kcalCounted)
        // Пришла карточка — позиция полная.
        b.applyFacts("aaa", listOf(p))
        assertEquals("Кефир", b.visibleEntries().single().product?.name)
        assertEquals(1, ProductsMath.totals(b.visibleEntries()).kcalCounted)
    }

    @Test
    fun deletesHideProductAndEntry() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        val meal = a.addMeal()
        val milk = a.saveProduct("Молоко", kcal = 60)
        val bread = a.saveProduct("Хлеб", kcal = 250)
        val e1 = a.addEntry(meal.uid, milk, grams = 200)
        val e2 = a.addEntry(meal.uid, bread, grams = 40)
        ok(exchange(a, b))
        // Удалённый продукт не предлагается, но в старом приёме остаётся подписанным.
        b.deleteProduct(milk.productUid)
        // Удаление позиции на втором телефоне.
        b.deleteEntry(e2.uid)
        ok(exchange(a, b))
        for (s in listOf(a, b)) {
            assertEquals(listOf("Хлеб"), s.liveProducts().map { it.name })
            assertEquals(listOf(e1.uid), s.visibleEntries().map { it.entry.uid })
            assertEquals("Молоко", s.visibleEntries().single().product?.name)
        }
        // Удаление продукта, пришедшее раньше карточки, тоже прячет его.
        val c = MemorySyncStore("ccc")
        val del = a.deleteProduct(bread.productUid)
        c.applyFacts("aaa", listOf(del))
        c.applyFacts("aaa", listOf(bread))
        assertTrue(c.liveProducts().isEmpty())
        // Удаление позиции раньше самой позиции.
        val d = MemorySyncStore("ddd")
        val delEntry = a.deleteEntry(e1.uid)
        d.applyFacts("aaa", listOf(meal, milk, delEntry))
        d.applyFacts("aaa", listOf(e1))
        assertTrue(d.visibleEntries().isEmpty())
        // Удалённый приём прячет и свои позиции.
        val e = MemorySyncStore("eee")
        e.applyFacts("aaa", listOf(meal, bread, e2, a.deleteMeal(meal.uid)))
        assertTrue(e.visibleEntries().isEmpty())
    }

    @Test
    fun repeatAndBrokenExchangeDoNotDuplicate() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        val meals = List(10) { a.addMeal() }
        val products = List(30) { a.saveProduct("Продукт $it", kcal = it * 10) }
        meals.forEachIndexed { i, m -> a.addEntry(m.uid, products[i], grams = 100 + i) }
        products.take(5).forEach { a.deleteProduct(it.productUid) }
        repeat(15) { b.saveProduct("Б $it") }
        for (cut in listOf(3, 5, 8)) {
            val r = exchange(a, b, breakAfter = cut, pageSize = 7)
            assertTrue(r.any { it != null })
        }
        ok(exchange(a, b, pageSize = 7))
        ok(exchange(a, b))
        assertEquals(a.facts.keys, b.facts.keys)
        assertEquals(25 + 15, b.liveProducts().size)
        assertEquals(a.visibleEntries(), b.visibleEntries())
        val before = b.facts.toMap()
        b.applyFacts("aaa", a.facts.values.toList())
        assertEquals(before, b.facts)
    }

    @Test
    fun restoreKeepsProductsAndEntries() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        val meal = a.addMeal()
        val backup = a.snapshot()
        val p = a.saveProduct("Сыр", kcal = 360, portionG = 30)
        val e = a.addEntry(meal.uid, p)
        val gone = a.saveProduct("Удалён")
        a.deleteProduct(gone.productUid)
        a.restoreFrom(backup)
        // Восстановление переносит продукты, позиции и удаления текущей базы: удалённый не воскресает.
        assertEquals(listOf("Сыр"), a.liveProducts().map { it.name })
        assertEquals(listOf(e.uid), a.visibleEntries().map { it.entry.uid })
        assertEquals(30, a.visibleEntries().single().entry.grams)
        ok(exchange(a, b))
        assertEquals(listOf("Сыр"), b.liveProducts().map { it.name })
        assertEquals(listOf(e.uid), b.visibleEntries().map { it.entry.uid })
    }

    @Test
    fun acceptRules() {
        val me = "aaa"
        val epoch = "f-cur"
        val utc = 1_700_000_000_000L
        val day = 19_676L
        val zone = "Europe/Moscow"
        fun accept(f: FactRecord) = SyncRules.acceptFact(f, me, epoch, "bbb")

        val good = ProductVersionFact(
            "v-1", "bbb", "e", 1, "p-1", utc, day, zone, "Гречка", "4006381333931", 343, 126, 33, 620, 80,
        )
        assertTrue(accept(good))
        assertTrue(accept(good.copy(barcode = null, kcal = null, proteinDg = null, fatDg = null, carbsDg = null, portionG = null)))
        assertTrue(accept(good.copy(author = me, epoch = "old")))
        // Автор, поколение, uid.
        assertFalse(accept(good.copy(author = "ccc")))
        assertFalse(accept(good.copy(author = me, epoch = epoch)))
        for (bad in listOf("../x", "a/b", "a.b", "", "-a", "x".repeat(65))) {
            assertFalse(bad, accept(good.copy(uid = bad)))
            assertFalse(bad, accept(good.copy(productUid = bad)))
        }
        // Название: 1–60 символов, нормализованное.
        assertTrue(accept(good.copy(name = "x".repeat(60))))
        for (bad in listOf("", " ", "x".repeat(61), " Гречка", "Гре  чка", "Гре\nчка", "Гре\u0000чка")) {
            assertFalse(bad, accept(good.copy(name = bad)))
        }
        // Штрихкод: цифры, длина 8/12/13/14, контрольная цифра.
        for (ok in listOf("96385074", "036000291452", "10012345678902")) assertTrue(ok, accept(good.copy(barcode = ok)))
        for (bad in listOf("", "4006381333932", "1234567890", "40063813339a1", "٤٠٠٦٣٨١٣٣٣٩٣١", "4006381333931 ")) {
            assertFalse(bad, accept(good.copy(barcode = bad)))
        }
        // Границы чисел.
        assertTrue(accept(good.copy(kcal = 0, proteinDg = 0, fatDg = 0, carbsDg = 0, portionG = 1)))
        assertTrue(accept(good.copy(kcal = 900, proteinDg = 1000, fatDg = 1000, carbsDg = 1000, portionG = 2000)))
        for (bad in listOf(
            good.copy(kcal = -1), good.copy(kcal = 901), good.copy(proteinDg = -1), good.copy(proteinDg = 1001),
            good.copy(fatDg = -1), good.copy(fatDg = 1001), good.copy(carbsDg = Int.MIN_VALUE), good.copy(carbsDg = 1001),
            good.copy(portionG = 0), good.copy(portionG = 2001), good.copy(portionG = Int.MAX_VALUE),
        )) assertFalse(bad.toString(), accept(bad))
        // Момент и сутки.
        assertFalse(accept(good.copy(utcMs = 0)))
        assertFalse(accept(good.copy(zoneId = "Нет/Такого")))
        assertTrue(accept(good.copy(day = day + 1)))
        for (bad in listOf(day + 2, day - 2, 0L, Long.MIN_VALUE, Long.MAX_VALUE)) assertFalse(accept(good.copy(day = bad)))

        val del = ProductDeleteFact("d-1", "bbb", "e", 2, "p-1", utc, day, zone)
        assertTrue(accept(del))
        assertFalse(accept(del.copy(author = "ccc")))
        assertFalse(accept(del.copy(author = me, epoch = epoch)))
        assertFalse(accept(del.copy(uid = "../d")))
        assertFalse(accept(del.copy(productUid = "../p")))
        assertFalse(accept(del.copy(utcMs = -1)))
        assertFalse(accept(del.copy(zoneId = "")))
        assertFalse(accept(del.copy(day = day + 5)))

        val entry = MealEntryFact("e-1", "bbb", "e", 3, "m-1", "v-1", 150, utc, day, zone)
        assertTrue(accept(entry))
        assertTrue(accept(entry.copy(grams = null)))
        assertTrue(accept(entry.copy(grams = 1)))
        assertTrue(accept(entry.copy(grams = 2000)))
        assertFalse(accept(entry.copy(grams = 0)))
        assertFalse(accept(entry.copy(grams = 2001)))
        assertFalse(accept(entry.copy(grams = -150)))
        assertFalse(accept(entry.copy(author = "ccc")))
        assertFalse(accept(entry.copy(author = me, epoch = epoch)))
        assertFalse(accept(entry.copy(uid = "e/1")))
        assertFalse(accept(entry.copy(mealUid = "../m")))
        assertFalse(accept(entry.copy(versionUid = "v.1")))
        assertFalse(accept(entry.copy(utcMs = 0)))
        assertFalse(accept(entry.copy(zoneId = "Марс/Олимп")))
        assertFalse(accept(entry.copy(day = Long.MIN_VALUE)))

        val entryDel = MealEntryDeleteFact("x-1", "bbb", "e", 4, "e-1", utc, day, zone)
        assertTrue(accept(entryDel))
        assertFalse(accept(entryDel.copy(author = "ccc")))
        assertFalse(accept(entryDel.copy(author = me, epoch = epoch)))
        assertFalse(accept(entryDel.copy(uid = "")))
        assertFalse(accept(entryDel.copy(entryUid = "../e")))
        assertFalse(accept(entryDel.copy(utcMs = 0)))
        assertFalse(accept(entryDel.copy(day = day - 3)))
    }

    @Test
    fun nonPositiveAseqRejectedForAllKinds() {
        val me = "aaa"
        val peer = "bbb"
        val utc = 1_700_000_000_000L
        val day = 19_676L
        val zone = "Europe/Moscow"
        val good: List<FactRecord> = listOf(
            SetFact("s1", peer, "e", 1, utc, day, "pushups", "Отжимания", null, 10, 0, 0, 0, null, "REPS", 0),
            RunFact("r1", peer, "e", 1, utc, day, "c1", null, 0, null, null, 10, 0, 0, true),
            LimitMoveFact("l1", peer, "e", 1, me, day, 60_000, utc),
            MealFact("m1", peer, "e", 1, utc, day, zone, false, null),
            MealDeleteFact("md1", peer, "e", 1, "m1", day, utc),
            CardAttemptFact("c1", peer, "e", 1, "mul:7x8", "mul", utc, day, zone, true, 10_000),
            ProductVersionFact("v1", peer, "e", 1, "p1", utc, day, zone, "Рис", null, null, null, null, null, null),
            ProductDeleteFact("pd1", peer, "e", 1, "p1", utc, day, zone),
            MealEntryFact("n1", peer, "e", 1, "m1", "v1", 100, utc, day, zone),
            MealEntryDeleteFact("x1", peer, "e", 1, "n1", utc, day, zone),
        )
        for (f in good) {
            assertTrue(f.toString(), SyncRules.acceptFact(f, me, "cur", peer))
            for (bad in listOf(0L, -1L, Long.MIN_VALUE)) {
                val copy = when (f) {
                    is SetFact -> f.copy(aseq = bad)
                    is RunFact -> f.copy(aseq = bad)
                    is LimitMoveFact -> f.copy(aseq = bad)
                    is MealFact -> f.copy(aseq = bad)
                    is MealDeleteFact -> f.copy(aseq = bad)
                    is CardAttemptFact -> f.copy(aseq = bad)
                    is ProductVersionFact -> f.copy(aseq = bad)
                    is ProductDeleteFact -> f.copy(aseq = bad)
                    is MealEntryFact -> f.copy(aseq = bad)
                    is MealEntryDeleteFact -> f.copy(aseq = bad)
                }
                assertFalse("$bad $f", SyncRules.acceptFact(copy, me, "cur", peer))
            }
        }
        // Свои факты нумеруются с 1: первый факт нового хранилища и после восстановления копии.
        val a = MemorySyncStore("aaa")
        assertEquals(1L, a.addMeal().aseq)
        a.restoreFrom(a.snapshot())
        assertEquals(1L, a.saveProduct("Х").aseq)
    }

    @Test
    fun repeatedWriteWithSameUidIsIdempotent() {
        // Как запись после поворота экрана: uid задан заранее, повтор не создаёт второй факт и не тратит номер.
        val a = MemorySyncStore("aaa")
        val meal = a.addMeal()
        val v1 = a.saveProduct("Рис", kcal = 130, uid = "ver-1")
        val again = a.saveProduct("Рис", kcal = 130, uid = "ver-1")
        assertEquals(v1, again)
        val e1 = a.addEntry(meal.uid, v1, grams = 100, uid = "entry-1")
        assertEquals(e1, a.addEntry(meal.uid, v1, grams = 100, uid = "entry-1"))
        assertEquals(3, a.facts.size)
        assertEquals(listOf(1L, 2L, 3L), a.facts.values.map { it.aseq })
        // Следующая запись — следующий номер, без пропуска.
        assertEquals(4L, a.addEntry(meal.uid, v1, uid = "entry-2").aseq)
        val b = MemorySyncStore("bbb")
        ok(exchange(a, b))
        assertEquals(2, b.visibleEntries().size)
    }

    @Test
    fun rejectedFactDoesNotBlockLaterOnesInGroup() {
        val a = MemorySyncStore("aaa")
        val meal = a.addMeal()
        val p = a.saveProduct("Рис", kcal = 130)
        val e = a.addEntry(meal.uid, p, grams = 100)
        // Посередине пакета — испорченная версия карточки (недопустимое название): отклоняется, остальные принимаются.
        val broken = p.copy(uid = "broken", aseq = 2, name = " ")
        val b = MemorySyncStore("bbb")
        b.applyFacts("aaa", listOf(meal, broken, p.copy(aseq = 3), e.copy(aseq = 4)))
        assertTrue("broken" !in b.facts)
        assertEquals(listOf("Рис"), b.liveProducts().map { it.name })
        assertEquals(listOf(e.uid), b.visibleEntries().map { it.entry.uid })
    }

    @Test
    fun codecRoundTripAndVersion() {
        assertTrue(SyncCodec.PROTOCOL_VERSION >= 4)
        val m = SyncMessage.Facts(
            listOf(
                ProductVersionFact("v1", "a", "e", 1, "p1", 1_700_000_000_000, 19_676, "Europe/Moscow", "Творог 5 %", "96385074", 121, 160, 50, 30, 180),
                ProductVersionFact("v2", "a", "e", 2, "p1", 1_700_000_100_000, 19_676, "UTC", "Т", null, null, null, null, null, null),
                ProductDeleteFact("d1", "b", "e2", 3, "p1", 1_700_000_200_000, 19_676, "UTC"),
                MealEntryFact("n1", "a", "e", 4, "m1", "v1", 150, 1_700_000_300_000, 19_676, "Europe/Moscow"),
                MealEntryFact("n2", "a", "e", 5, "m1", "v2", null, 1_700_000_300_000, 19_676, "UTC"),
                MealEntryDeleteFact("x1", "b", "e2", 6, "n1", 1_700_000_400_000, 19_676, "UTC"),
                MealFact("m1", "a", "e", 7, 1_700_000_000_000, 19_676, "Europe/Moscow", true, "обед"),
            ),
        )
        assertEquals(m, SyncCodec.decode(SyncCodec.encode(m)))
    }
}
