package com.nextlevellife.app.sync

import com.nextlevellife.app.data.ActivityDay
import com.nextlevellife.app.data.ActivityRun
import com.nextlevellife.app.data.OpKind
import com.nextlevellife.app.data.SharedWalletMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncSessionTest {
    private val min = 60_000L

    /** Результат обмена двух хранилищ: итоги сторон или исключения. */
    private class Result(val a: Result1, val b: Result1, val messages: Int)
    private class Result1(val outcome: SyncOutcome?, val error: Throwable?)

    private fun exchange(
        a: MemorySyncStore, b: MemorySyncStore, breakAfter: Int = Int.MAX_VALUE,
        maxBytes: Int = SyncChannel.MAX_MESSAGE_BYTES, pageSize: Int = 400,
    ): Result {
        val pair = MemoryChannelPair(a.deviceId, b.deviceId, breakAfter)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val fa = pool.submit(Callable { runCatching { SyncSession(a, pair.forA, maxBytes, pageSize).run() } })
            val fb = pool.submit(Callable { runCatching { SyncSession(b, pair.forB, maxBytes, pageSize).run() } })
            val ra = fa.get(30, TimeUnit.SECONDS)
            val rb = fb.get(30, TimeUnit.SECONDS)
            return Result(
                Result1(ra.getOrNull(), ra.exceptionOrNull()),
                Result1(rb.getOrNull(), rb.exceptionOrNull()),
                pair.sent.get(),
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun ok(r: Result): Result {
        r.a.error?.let { throw AssertionError("A: $it", it) }
        r.b.error?.let { throw AssertionError("B: $it", it) }
        return r
    }

    private fun pair(limit: Long = 0L): Pair<MemorySyncStore, MemorySyncStore> =
        MemorySyncStore("aaa", limit) to MemorySyncStore("bbb", limit)

    private fun countKind(s: MemorySyncStore, k: OpKind) = s.ops.values.count { it.kind == k.name }

    @Test
    fun normalExchangeCopiesJournalsAndFacts() {
        val (a, b) = pair()
        a.earn(30 * min)
        a.spend(5 * min, null)
        a.addSet(20)
        b.earn(10 * min)
        b.addSet(15)
        b.addSet(12)
        ok(exchange(a, b))
        assertEquals(a.balance(), b.peerBalance("aaa"))
        assertEquals(b.balance(), a.peerBalance("bbb"))
        assertEquals(3, a.facts.size)
        assertEquals(3, b.facts.size)
        assertEquals(a.ops.size.toLong(), b.opsCursor("aaa")!!.pos)
        assertEquals(1, a.importedCalls)
        // Обмен ничего не начисляет: кошельки прежние (оба простаивают, разница 25−10 ≥ 2 мин → выравнивание).
        assertEquals(35 * min, a.balance() + b.balance())
    }

    @Test
    fun repeatSendsNothingNew() {
        val (a, b) = pair()
        a.earn(10 * min); a.addSet(10)
        b.earn(10 * min); b.addSet(10)
        ok(exchange(a, b))
        val factsA = a.facts.size
        val opsCopy = b.peerOps["aaa"]!!.size
        val r = ok(exchange(a, b))
        assertEquals(factsA, a.facts.size)
        assertEquals(opsCopy, b.peerOps["aaa"]!!.size)
        // Только служебные сообщения: приветствия, настройки, концы потоков (журналы, переносы, досылка), завершение.
        assertEquals(2 + 2 + 5 + 2, r.messages)
    }

    @Test
    fun openUsageRowUpdateReplacesCopy() {
        val (a, b) = pair()
        a.earn(30 * min)
        a.spend(1 * min, null)
        ok(exchange(a, b))
        a.extendLastUsage(40_000)
        ok(exchange(a, b))
        // В копии одна строка расхода со свежим значением, а не две.
        val usage = b.peerOps["aaa"]!!.values.filter { it.kind == OpKind.USAGE.name }
        assertEquals(1, usage.size)
        assertEquals(-(min + 40_000), usage.single().deltaMs)
        assertEquals(a.balance(), b.peerBalance("aaa"))
    }

    @Test
    fun brokenExchangeResumesWithoutDuplicates() {
        val (a, b) = pair()
        repeat(300) { a.earn(1_000) }
        repeat(120) { a.addSet(it + 1) }
        repeat(80) { b.addSet(it + 1) }
        for (cut in listOf(3, 5, 8, 11, 17)) {
            val r = exchange(a, b, breakAfter = cut, pageSize = 25)
            assertTrue(r.a.error != null || r.b.error != null)
            // Что применено — применено целиком: копия журнала — префикс по rev, курсор — её максимум.
            val copy = b.peerOps["aaa"].orEmpty()
            val cursor = b.opsCursor("aaa")
            if (cursor != null) assertEquals(copy.values.maxOfOrNull { it.rev } ?: 0L, cursor.pos)
            assertEquals((1..copy.size.toLong()).toList(), copy.values.map { it.rev }.sorted())
        }
        ok(exchange(a, b, pageSize = 25))
        assertEquals(a.ops.size, b.peerOps["aaa"]!!.size)
        assertEquals(a.balance(), b.peerBalance("aaa"))
        assertEquals(200, a.facts.size)
        assertEquals(200, b.facts.size)
    }

    @Test
    fun smallMessagesAreSplit() {
        val (a, b) = pair()
        repeat(50) { a.earn(1_000) }
        repeat(30) { a.addSet(it + 1) }
        ok(exchange(a, b, maxBytes = 2_000))
        assertEquals(50, b.peerOps["aaa"]!!.size)
        assertEquals(30, b.facts.size)
    }

    @Test
    fun opsEpochChangeResetsCopyAndLostSetsComeBack() {
        val (a, b) = pair()
        a.earn(20 * min)
        a.addSet(10)
        val backup = a.snapshot()
        a.earn(5 * min)
        val lost = a.addSet(30)
        ok(exchange(a, b))
        assertTrue(lost.uid in b.facts)
        // Восстановление копии: журнал и факты откатились, поколения новые.
        a.restoreFrom(backup)
        assertFalse(lost.uid in a.facts)
        ok(exchange(a, b))
        // Копия журнала у B заменена журналом A нового поколения.
        assertEquals(a.balance(), b.peerBalance("aaa"))
        assertEquals(a.opsEpoch, b.opsCursor("aaa")!!.epoch)
        // Потерянный подход вернулся с B — со своим автором и прежним поколением.
        val back = a.facts[lost.uid]
        assertNotNull(back)
        assertEquals("aaa", back!!.author)
        assertEquals(lost.epoch, back.epoch)
        // Новые подходы нового поколения тоже доходят.
        val fresh = a.addSet(5)
        ok(exchange(a, b))
        assertTrue(fresh.uid in b.facts)
    }

    @Test
    fun forgedOwnFactOfCurrentEpochIsRejected() {
        val (a, _) = pair()
        val forged = SetFact(
            "x", "aaa", a.factsEpoch, 999, 0, 100, "pushups", "Отжимания", null, 50, 0, 0, 0, null, "REPS", 0,
        )
        assertTrue(a.applyFacts("bbb", listOf(forged)).isEmpty())
        assertNull(a.facts["x"])
        // Тот же автор, но старое поколение (потерянное при восстановлении) — принимается.
        val old = forged.copy(uid = "y", epoch = "old-epoch")
        assertEquals(setOf(100L), a.applyFacts("bbb", listOf(old)))
    }

    @Test
    fun idlePhoneGivesEverythingToSpendingPhone() {
        val (a, b) = pair()
        b.earn(30 * min)
        a.spending = true
        val r = ok(exchange(a, b))
        assertEquals(30 * min, r.b.outcome!!.givenWalletMs)
        assertEquals(30 * min, r.a.outcome!!.receivedMs)
        assertEquals(30 * min, a.balance())
        assertEquals(0L, b.balance())
        assertEquals(1, countKind(a, OpKind.TRANSFER_IN))
        assertEquals(1, countKind(b, OpKind.TRANSFER_OUT))
        // Копии сходятся с журналами.
        assertEquals(a.balance(), b.peerBalance("aaa"))
        assertEquals(b.balance(), a.peerBalance("bbb"))
    }

    @Test
    fun debtsReduceWhatCanBeGiven() {
        val (a, b) = pair()
        b.earn(30 * min)
        b.debtMs = 10 * min
        a.spending = true
        val r = ok(exchange(a, b))
        assertEquals(20 * min, r.b.outcome!!.givenWalletMs)
        assertEquals(10 * min, b.balance())
    }

    @Test
    fun idlePhonesEqualize() {
        val (a, b) = pair()
        a.earn(40 * min)
        b.earn(10 * min)
        ok(exchange(a, b))
        assertEquals(25 * min, a.balance())
        assertEquals(25 * min, b.balance())
        // Повтор ничего не переносит.
        ok(exchange(a, b))
        assertEquals(1, countKind(a, OpKind.TRANSFER_OUT))
        assertEquals(0, countKind(b, OpKind.TRANSFER_OUT))
    }

    @Test
    fun bothSpendingNothingMoves() {
        val (a, b) = pair()
        a.earn(40 * min)
        b.earn(10 * min)
        a.spending = true
        b.spending = true
        ok(exchange(a, b))
        assertEquals(40 * min, a.balance())
        assertEquals(10 * min, b.balance())
    }

    @Test
    fun limitShareMovesToSpendingPhone() {
        val (a, b) = pair(limit = 120 * min)
        a.earn(200 * min)
        b.earn(10 * min)
        b.spend(10 * min, "aaa")
        b.spending = true
        a.spending = false
        a.spend(20 * min, "bbb")
        val r = ok(exchange(a, b))
        // A простаивает: отдаёт кошелёк и оставшуюся долю (60 − 20 = 40 мин).
        assertEquals(40 * min, r.a.outcome!!.givenLimitMs)
        assertEquals(0L, a.shareLeft("bbb"))
        assertEquals(50 * min + 40 * min, b.shareLeft("aaa"))
        // Сумма долей и расхода — ровно лимит.
        assertEquals(120 * min, a.shareLeft("bbb")!! + b.shareLeft("aaa")!! + a.ownSpent() + b.ownSpent())
    }

    @Test
    fun brokenTransferArrivesOnceLater() {
        val (a, b) = pair()
        b.earn(30 * min)
        a.spending = true
        // Ищем обрыв, после которого B уже записал перенос, а A его ещё не получил.
        var found = false
        for (cut in 1..14) {
            val (x, y) = pair()
            y.earn(30 * min)
            x.spending = true
            exchange(x, y, breakAfter = cut)
            val out = countKind(y, OpKind.TRANSFER_OUT)
            val inn = countKind(x, OpKind.TRANSFER_IN)
            assertTrue(inn <= out)
            // Сумма кошельков не растёт: в пути минуты есть только у отправителя минусом.
            assertTrue(x.balance() + y.balance() <= 30 * min)
            if (out == 1 && inn == 0) {
                found = true
                x.spending = false
                ok(exchange(x, y))
                ok(exchange(x, y))
                assertEquals(1, countKind(x, OpKind.TRANSFER_IN))
                assertEquals(1, countKind(y, OpKind.TRANSFER_OUT))
                assertEquals(30 * min, x.balance() + y.balance())
            }
        }
        assertTrue("не нашли обрыв между записью и доставкой переноса", found)
        ok(exchange(a, b))
        assertEquals(1, countKind(a, OpKind.TRANSFER_IN))
    }

    @Test
    fun restoreAfterTransferDoesNotReturnGivenMinutes() {
        val (a, b) = pair()
        b.earn(30 * min)
        val backup = b.snapshot()
        a.spending = true
        ok(exchange(a, b))
        assertEquals(30 * min, a.balance())
        // B восстанавливает копию до переноса: перенос возвращается в журнал той же операцией.
        b.restoreFrom(backup)
        assertEquals(0L, b.balance())
        a.spending = false
        ok(exchange(a, b))
        ok(exchange(a, b))
        assertEquals(1, countKind(a, OpKind.TRANSFER_IN))
        assertEquals(30 * min, a.balance() + b.balance())
    }

    @Test
    fun receiverRestoreGetsTransferInAgain() {
        val (a, b) = pair()
        b.earn(30 * min)
        val backupA = a.snapshot()
        a.spending = true
        ok(exchange(a, b))
        // A восстанавливает копию до получения: входящий перенос пропал, но исходящий B ещё в журнале.
        a.restoreFrom(backupA)
        assertEquals(0L, a.balance())
        ok(exchange(a, b))
        assertEquals(30 * min, a.balance())
        assertEquals(1, countKind(a, OpKind.TRANSFER_IN))
    }

    @Test
    fun failedApplyRollsBackAndRetries() {
        val (a, b) = pair()
        repeat(10) { a.earn(1_000) }
        b.failNextApply = true
        val r = exchange(a, b)
        assertNotNull(r.b.error)
        assertNull(b.opsCursor("aaa"))
        ok(exchange(a, b))
        assertEquals(10, b.peerOps["aaa"]!!.size)
    }

    @Test
    fun settingsMergeAndActivityFromSource() {
        val (a, b) = pair()
        a.healthConnected = true
        a.setSetting("daily_limit", "90")
        b.setSetting("daily_limit", "60")
        b.setSetting("daily_limit", "45")
        a.activity = ActivityExport(
            90, 100,
            listOf(ActivityDay(100, 5_000, 0, 0, 0, 0, listOf("garmin"), 0, 1, runsRead = true)),
            listOf(ActivityRun("r1", 0, 1, 100, 1_000, 900, "garmin", 56)),
        )
        b.activity = ActivityExport(90, 100, listOf(ActivityDay(100, 1, 0, 0, 0, 0, emptyList(), 0, 1)), emptyList())
        ok(exchange(a, b))
        // Версия 2 у B побеждает версию 1 у A.
        assertEquals("45", a.settingRows["daily_limit"]!!.value)
        assertEquals("45", b.settingRows["daily_limit"]!!.value)
        // Источник активности — телефон с Health Connect; его дни приняты, дни B — нет.
        assertEquals("aaa", a.settingRows["activity_source"]!!.value)
        assertEquals(5_000L, b.activityDays[100]!!.steps)
        assertTrue(a.activityDays.isEmpty())
    }

    @Test
    fun wrongProtocolVersionFails() {
        val (a, _) = pair()
        val pair = MemoryChannelPair("aaa", "bbb")
        val pool = Executors.newSingleThreadExecutor()
        try {
            val f = pool.submit(Callable { runCatching { SyncSession(a, pair.forA).run() } })
            val hello = SyncCodec.decode(pair.forB.receive()) as SyncMessage.Hello
            pair.forB.send(SyncCodec.encode(hello.copy(protocol = 99, deviceId = "bbb", cursors = emptyList())))
            val r = f.get(10, TimeUnit.SECONDS)
            assertTrue(r.exceptionOrNull() is SyncProtocolException)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------- Кодирование ----------

    @Test
    fun codecRoundTrip() {
        val messages = listOf(
            SyncMessage.Hello(
                1, "aaa", "e1", "f1", 20_000, "Europe/Moscow", true, 123, null, false,
                listOf(LogCursor(LogCursor.OPS, "bbb", "e2", 5), LogCursor(LogCursor.FACTS, "aaa", "f0", 9)),
            ),
            SyncMessage.Hello(1, "aaa", "e1", "f1", 20_000, "UTC", false, -5, 777, true, emptyList()),
            SyncMessage.Settings(listOf(SharedWalletMath.Setting("night", "1,1380,420", 4, "bbb"))),
            SyncMessage.Ops(
                "e1", true,
                listOf(OpRecord("id", "key", "USAGE", -1, 0, 1, 2, 3, "UTC", "app", "pkg", 1, null, "{\"а\":\"б\"}", 7)),
            ),
            SyncMessage.Facts(
                listOf(
                    SetFact("u1", "a", "e", 1, 2, 3, "plank", "Планка", null, 0, 60_000, 10, 0, "r1", "HOLD", 60_000),
                    RunFact("r1", "a", "e", 0, 2, 3, "minotaur", 16, 900_000, null, 3, 275, 100, 0, true),
                    LimitMoveFact("m1", "a", "e", 4, "b", 3, 60_000, 99),
                ),
            ),
            SyncMessage.Activity(
                ActivityExport(
                    1, 2,
                    listOf(ActivityDay(1, null, 10, 20, 30, 1, listOf("a", "b"), 40, 50, true)),
                    listOf(ActivityRun("x", 1, 2, 1, 3, 4, "s", 56)),
                ),
            ),
            SyncMessage.EndLogs,
            SyncMessage.Done,
        )
        for (m in messages) assertEquals(m, SyncCodec.decode(SyncCodec.encode(m)))
    }

    @Test
    fun codecRejectsGarbage() {
        val good = SyncCodec.encode(SyncMessage.Settings(listOf(SharedWalletMath.Setting("k", "v", 1, "a"))))
        for (bad in listOf(ByteArray(0), byteArrayOf(1, 2, 3), good.copyOf(good.size - 2), good + byteArrayOf(0), byteArrayOf(0x4E, 99))) {
            try {
                SyncCodec.decode(bad)
                fail("принято: ${bad.toList()}")
            } catch (e: SyncProtocolException) {
                // ожидаемо
            }
        }
    }

    @Test
    fun cursorsMergeAndReset() {
        val m = SyncRules.mergeCursors(
            listOf(LogCursor("facts", "a", "e", 3), LogCursor("facts", "b", "e", 1)),
            listOf(LogCursor("facts", "a", "e", 7), LogCursor("ops", "a", "e", 2)),
        )
        assertEquals(3, m.size)
        assertEquals(7L, m.first { it.log == "facts" && it.author == "a" }.pos)
        assertTrue(SyncRules.needsReset(null, "e"))
        assertTrue(SyncRules.needsReset(LogCursor("ops", "a", "old", 5), "e"))
        assertFalse(SyncRules.needsReset(LogCursor("ops", "a", "e", 5), "e"))
    }

    @Test
    fun missingTransferInsOnlyForMeAndOnce() {
        val out = OpRecord("o1", "transfer_out:1", "TRANSFER_OUT", -60_000, 0, 0, 0, 0, "UTC", "transfer", "aaa", 1, null, "{}", 1)
        val other = out.copy(id = "o2", sourceId = "ccc")
        assertEquals(listOf(out), SyncRules.missingTransferIns(listOf(out, other), "aaa", emptySet()))
        assertTrue(SyncRules.missingTransferIns(listOf(out), "aaa", setOf("transfer_in:o1")).isEmpty())
    }
}
