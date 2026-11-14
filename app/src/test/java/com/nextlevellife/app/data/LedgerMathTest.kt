package com.nextlevellife.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class LedgerMathTest {

    private val chunk = LedgerMath.USAGE_CHUNK_MS

    // ---------- Ключи ----------

    @Test
    fun idempotencyKeys() {
        assertEquals("set:abc", LedgerMath.setKey("abc"))
        assertEquals("complex:abc", LedgerMath.complexKey("abc"))
        assertEquals("usage:abc:0", LedgerMath.usageKey("abc", 0))
        assertEquals("usage:abc:12", LedgerMath.usageKey("abc", 12))
        assertEquals("opening", LedgerMath.OPENING_KEY)
        // Подход и комплекс с одним sessionId — разные операции.
        assertNotEquals(LedgerMath.setKey("x"), LedgerMath.complexKey("x"))
    }

    @Test
    fun rewardKindsExcludeOpeningAndUsage() {
        assertTrue(OpKind.OPENING !in OpKind.REWARDS)
        assertTrue(OpKind.USAGE !in OpKind.REWARDS)
        assertTrue(OpKind.SET_REWARD in OpKind.REWARDS && OpKind.STEPS_REWARD in OpKind.REWARDS)
    }

    // ---------- Нарезка расхода ----------

    @Test
    fun firstSpendOpensRowZero() {
        val p = LedgerMath.planUsage(null, "s", "app", 100, 2_000)
        assertEquals(0L, p.addToOpen)
        assertEquals(listOf(2_000L), p.newRows)
        assertEquals(0, p.firstNewIndex)
        assertEquals(UsageCursor("s", "app", 0, 100, 2_000), p.next)
    }

    @Test
    fun ticksAccumulateInOpenRowUpToMinute() {
        var cursor: UsageCursor? = null
        val rowsCreated = mutableListOf<Int>()
        // 30 тиков по 2 с = ровно минута: одна строка.
        repeat(30) {
            val p = LedgerMath.planUsage(cursor, "s", "app", 100, 2_000)
            if (p.newRows.isNotEmpty()) rowsCreated += p.firstNewIndex
            cursor = p.next
        }
        assertEquals(listOf(0), rowsCreated)
        assertEquals(chunk, cursor!!.accumulatedMs)
        // 31-й тик — следующая строка.
        val p = LedgerMath.planUsage(cursor, "s", "app", 100, 2_000)
        assertEquals(0L, p.addToOpen)
        assertEquals(listOf(2_000L), p.newRows)
        assertEquals(1, p.firstNewIndex)
    }

    @Test
    fun tickOverflowsOpenRow() {
        val open = UsageCursor("s", "app", 3, 100, 59_000)
        val p = LedgerMath.planUsage(open, "s", "app", 100, 2_500)
        assertEquals(1_000L, p.addToOpen)
        assertEquals(listOf(1_500L), p.newRows)
        assertEquals(4, p.firstNewIndex)
        assertEquals(UsageCursor("s", "app", 4, 100, 1_500), p.next)
    }

    @Test
    fun bigChargeIsChunked() {
        val p = LedgerMath.planUsage(null, "s", "app", 100, 150_000)
        assertEquals(listOf(60_000L, 60_000L, 30_000L), p.newRows)
        assertEquals(150_000L, p.addToOpen + p.newRows.sum())
        assertEquals(2, p.next.index)
    }

    @Test
    fun everyRowAtMostOneMinuteAndSumPreserved() {
        var cursor: UsageCursor? = null
        val rows = mutableMapOf<Int, Long>()
        val charges = listOf(2_000L, 1_999L, 45_000L, 70_001L, 3L, 2_000L, 120_000L)
        for (c in charges) {
            val p = LedgerMath.planUsage(cursor, "s", "app", 100, c)
            if (p.addToOpen > 0) rows[cursor!!.index] = rows.getValue(cursor!!.index) + p.addToOpen
            p.newRows.forEachIndexed { i, ms -> rows[p.firstNewIndex + i] = ms }
            cursor = p.next
        }
        assertEquals(charges.sum(), rows.values.sum())
        assertTrue(rows.values.all { it in 1..chunk })
        assertEquals((0 until rows.size).toList(), rows.keys.sorted())
    }

    @Test
    fun otherSessionStartsAtZero() {
        val open = UsageCursor("old", "app", 7, 100, 10_000)
        val p = LedgerMath.planUsage(open, "new", "app", 100, 2_000)
        assertEquals(0L, p.addToOpen)
        assertEquals(0, p.firstNewIndex)
    }

    @Test
    fun otherPackageInSameSessionGetsNewRowWithNextIndex() {
        val open = UsageCursor("s", "a", 2, 100, 10_000)
        val p = LedgerMath.planUsage(open, "s", "b", 100, 2_000)
        assertEquals(0L, p.addToOpen)
        assertEquals(3, p.firstNewIndex)
        assertEquals("b", p.next.packageName)
    }

    @Test
    fun dayChangeMidSessionClosesRow() {
        val zone = ZoneId.of("Europe/Moscow")
        val before = LocalDateTime.of(2026, 11, 12, 23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        val after = before + 2_000
        val day1 = LedgerMath.accountingDay(before, zone)
        val day2 = LedgerMath.accountingDay(after, zone)
        assertEquals(LocalDate.of(2026, 11, 12).toEpochDay(), day1)
        assertEquals(LocalDate.of(2026, 11, 13).toEpochDay(), day2)

        val first = LedgerMath.planUsage(null, "s", "app", day1, 2_000)
        // Строка не заполнена, но наступили новые сутки: расход идёт в новую строку нового дня.
        val second = LedgerMath.planUsage(first.next, "s", "app", day2, 2_000)
        assertEquals(0L, second.addToOpen)
        assertEquals(listOf(2_000L), second.newRows)
        assertEquals(1, second.firstNewIndex)
        assertEquals(day2, second.next.day)
    }

    @Test
    fun accountingDayDependsOnZone() {
        val utc = ZoneId.of("UTC")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val t = LocalDateTime.of(2026, 11, 12, 20, 0).atZone(utc).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 12).toEpochDay(), LedgerMath.accountingDay(t, utc))
        assertEquals(LocalDate.of(2026, 11, 13).toEpochDay(), LedgerMath.accountingDay(t, tokyo))
    }

    @Test
    fun ledgerMigrationIsAppendedAndHasAllColumns() {
        // Миграции только дописываются: с 0.4 есть третья (шаги и пробежки).
        assertTrue(Diary.MIGRATIONS.size >= 2)
        // Первая миграция не тронута: дневник по-прежнему создаётся первым шагом.
        assertTrue(Diary.MIGRATIONS[0].first().contains("CREATE TABLE complex_runs"))
        val ops = Diary.MIGRATIONS[1].first { it.contains("CREATE TABLE balance_ops") }
        val columns = listOf(
            "seq INTEGER PRIMARY KEY AUTOINCREMENT", "id TEXT NOT NULL UNIQUE", "device_id TEXT NOT NULL",
            "idempotency_key TEXT NOT NULL UNIQUE", "kind TEXT NOT NULL", "delta_ms INTEGER NOT NULL",
            "delta_xp INTEGER NOT NULL DEFAULT 0", "occurred_at_ms", "recorded_at_ms",
            "accounting_day INTEGER NOT NULL", "zone_id TEXT NOT NULL", "source_type TEXT NOT NULL",
            "source_id TEXT NOT NULL", "rule_version INTEGER NOT NULL", "reverses_id TEXT", "metadata_json TEXT NOT NULL",
        )
        for (c in columns) assertTrue("нет столбца: $c", ops.contains(c))
        assertTrue(Diary.MIGRATIONS[1].any { it.contains("CREATE TABLE account") })
        assertTrue(Diary.MIGRATIONS[1].any { it.contains("INSERT INTO account") })
        assertTrue(Diary.MIGRATIONS[1].any { it.contains("CREATE TABLE meta") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroChargeRejected() {
        LedgerMath.planUsage(null, "s", "app", 100, 0)
    }

    // ---------- Долг списания после сбоя базы ----------

    private val moscow = ZoneId.of("Europe/Moscow")
    private val min = 60_000L
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(moscow).toInstant().toEpochMilli()
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    @Test
    fun debtIsChargedToItsOwnDaysNotToday() {
        // База не принимала списания с 23:50 до 23:58 13 ноября; записалось только в 00:10 14 ноября.
        val debt = UsageDebt(ms(2026, 11, 13, 23, 50), ms(2026, 11, 13, 23, 58), "app.a", "old")
        val now = ms(2026, 11, 14, 0, 10)
        val parts = LedgerMath.planCharges(listOf(debt), "app.b", now, 2 * min, balanceMs = 60 * min, zone = moscow)
        assertEquals(listOf("app.a" to day(2026, 11, 13), "app.b" to day(2026, 11, 14)), parts.map { it.packageName to it.share.day })
        assertEquals(listOf(8 * min, 2 * min), parts.map { it.share.ms })
        assertEquals("old", parts[0].debtSessionId)
        assertEquals(null, parts[1].debtSessionId)
        // Долг через полночь делится по суткам.
        val across = UsageDebt(ms(2026, 11, 13, 23, 55), ms(2026, 11, 14, 0, 5), "app.a", "old")
        val p2 = LedgerMath.planCharges(listOf(across), "app.a", now, 0, 60 * min, moscow)
        assertEquals(listOf(day(2026, 11, 13) to 5 * min, day(2026, 11, 14) to 5 * min), p2.map { it.share.day to it.share.ms })
    }

    @Test
    fun debtsAndCurrentAreCappedByBalanceOldestFirst() {
        val d1 = UsageDebt(ms(2026, 11, 13, 10, 0), ms(2026, 11, 13, 10, 3), "a", "s1")
        val d2 = UsageDebt(ms(2026, 11, 13, 9, 0), ms(2026, 11, 13, 9, 2), "b", "s0")
        val now = ms(2026, 11, 13, 11, 0)
        val parts = LedgerMath.planCharges(listOf(d1, d2), "c", now, 5 * min, balanceMs = 4 * min, zone = moscow)
        // Сначала старый долг (2 мин), затем 2 из 3 минут следующего; на текущий баланса не осталось.
        assertEquals(listOf("b" to 2 * min, "a" to 2 * min), parts.map { it.packageName to it.share.ms })
        assertEquals(4 * min, parts.sumOf { it.share.ms })
        assertTrue(LedgerMath.planCharges(listOf(d1), "c", now, 5 * min, 0L, moscow).isEmpty())
        // Без долгов — как прежде: только текущий просмотр, не больше баланса.
        assertEquals(listOf(3 * min), LedgerMath.planCharges(emptyList(), "c", now, 5 * min, 3 * min, moscow).map { it.share.ms })
    }

    // ---------- Перенос расхода при восстановлении копии ----------

    @Test
    fun restoreCarriesSpendingMadeAfterBackup() {
        val d13 = day(2026, 11, 13)
        val d14 = day(2026, 11, 14)
        // Копия сделана, когда в открытой строке s:0 было 20 с; потом строка доросла до 60 с, и появились новые.
        val backup = mapOf("usage:s:0" to 20_000L, "usage:old:0" to 60_000L)
        val current = listOf(
            SpentRow("usage:old:0", d13, "app.a", 60_000),
            SpentRow("usage:s:0", d13, "app.a", 60_000),
            SpentRow("usage:s:1", d13, "app.a", 30_000),
            SpentRow("usage:t:0", d14, "app.b", 45_000),
            SpentRow("restore_carry:r:$d14:app.b", d14, "app.b", 10_000), // перенос прошлого восстановления тоже переносится
        )
        val carry = LedgerMath.restoreCarry(current, backup)
        assertEquals(listOf(CarryPart(d13, "app.a", 70_000), CarryPart(d14, "app.b", 55_000)), carry)

        // Расход после копии весь в копии (ничего не тратили) — переносить нечего.
        assertTrue(LedgerMath.restoreCarry(current.take(1), backup).isEmpty())
    }

    @Test
    fun restoreCarryIsNotCappedByBalance() {
        val d13 = day(2026, 11, 13)
        val d14 = day(2026, 11, 14)
        val current = listOf(SpentRow("usage:a:0", d13, "x", 60_000), SpentRow("usage:b:0", d14, "y", 40_000))
        // Переносится весь расход после копии: баланс восстановленного журнала может уйти в минус.
        assertEquals(listOf(CarryPart(d13, "x", 60_000), CarryPart(d14, "y", 40_000)),
            LedgerMath.restoreCarry(current, emptyMap()))
    }

    @Test
    fun restoreCarryCountsAsSpending() {
        assertTrue(OpKind.RESTORE_CARRY in OpKind.SPENDING && OpKind.USAGE in OpKind.SPENDING)
        assertTrue(OpKind.RESTORE_CARRY !in OpKind.REWARDS)
        assertEquals("restore_carry:r1:20405:app.a", LedgerMath.restoreCarryKey("r1", 20405, "app.a"))
    }
}
