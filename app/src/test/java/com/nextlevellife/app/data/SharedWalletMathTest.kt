package com.nextlevellife.app.data

import com.nextlevellife.app.data.SharedWalletMath.Give
import com.nextlevellife.app.data.SharedWalletMath.Setting
import com.nextlevellife.app.data.SharedWalletMath.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SharedWalletMathTest {
    private val min = 60_000L
    private val a = "aaa"
    private val b = "bbb"

    // ---------- Доля лимита ----------

    @Test
    fun evenLimitSplitsInHalf() {
        assertEquals(60 * min, SharedWalletMath.shareLeftMs(120 * min, a, b, 0, 0, 0))
        assertEquals(60 * min, SharedWalletMath.shareLeftMs(120 * min, b, a, 0, 0, 0))
        // Расход, полученное и отданное.
        assertEquals(40 * min, SharedWalletMath.shareLeftMs(120 * min, a, b, 0, 0, 20 * min))
        assertEquals(50 * min, SharedWalletMath.shareLeftMs(120 * min, a, b, 10 * min, 0, 20 * min))
        assertEquals(30 * min, SharedWalletMath.shareLeftMs(120 * min, a, b, 0, 10 * min, 20 * min))
    }

    @Test
    fun oddLimitRemainderGoesToSmallerDeviceId() {
        val odd = 7_200_001L
        val forA = SharedWalletMath.shareLeftMs(odd, a, b, 0, 0, 0)!!
        val forB = SharedWalletMath.shareLeftMs(odd, b, a, 0, 0, 0)!!
        assertEquals(odd, forA + forB)
        assertEquals(forB + 1, forA)
        assertEquals(3_600_000L, SharedWalletMath.halfLimitMs(odd, b, a))
    }

    @Test
    fun unpairedPhoneHasWholeLimitAndZeroLimitMeansNoLimit() {
        assertEquals(100 * min, SharedWalletMath.shareLeftMs(120 * min, a, null, 0, 0, 20 * min))
        assertNull(SharedWalletMath.shareLeftMs(0, a, b, 0, 0, 20 * min))
        assertNull(SharedWalletMath.shareLeftMs(0, a, null, 0, 0, 0))
    }

    @Test
    fun overspentShareIsNegativeAndBlocksViaEffectiveSpent() {
        val share = SharedWalletMath.shareLeftMs(120 * min, a, b, 0, 0, 70 * min)!!
        assertEquals(-10 * min, share)
        // Правила блокировки видят «потрачено» больше лимита: остаток лимита 0.
        val spent = SharedWalletMath.effectiveSpentMs(120 * min, share)
        assertEquals(130 * min, spent)
        assertEquals(0L, com.nextlevellife.app.block.BlockRules.limitLeftMs(
            spent, com.nextlevellife.app.block.BlockSettings(120, false, 0, 0),
        ))
        assertEquals(0L, SharedWalletMath.effectiveSpentMs(0, null))
        assertEquals(80 * min, SharedWalletMath.effectiveSpentMs(120 * min, 40 * min))
    }

    @Test
    fun newDayGivesNewHalf() {
        // Сутки 10: всё потрачено и отдано; сутки 11 — переносы и расход прошлых суток не считаются.
        val day10 = SharedWalletMath.shareLeftMs(120 * min, a, b, 0, 20 * min, 40 * min)!!
        assertEquals(0L, day10)
        val day11 = SharedWalletMath.shareLeftMs(120 * min, a, b, 0, 0, 0)!!
        assertEquals(60 * min, day11)
    }

    // ---------- Правило переноса ----------

    private fun side(spending: Boolean, wallet: Long, share: Long? = null, day: Long = 1) = Side(spending, wallet, share, day)

    @Test
    fun idleGivesEverythingToSpendingPeer() {
        val g = SharedWalletMath.plan(side(false, 30 * min, 40 * min), side(true, 0, 5 * min))
        assertEquals(Give(30 * min, 40 * min, 1), g)
        // Отрицательный кошелёк ничего не отдаёт.
        assertEquals(0L, SharedWalletMath.plan(side(false, -5 * min, 0), side(true, 0, 0)).walletMs)
        // Другие учётные сутки — доля не переносится, минуты — да.
        val other = SharedWalletMath.plan(side(false, 30 * min, 40 * min, day = 1), side(true, 0, 5 * min, day = 2))
        assertEquals(30 * min, other.walletMs)
        assertEquals(0L, other.limitMs)
    }

    @Test
    fun spendingSideGivesNothing() {
        assertTrue(SharedWalletMath.plan(side(true, 30 * min, 40 * min), side(false, 0, 0)).isEmpty)
        assertTrue(SharedWalletMath.plan(side(true, 30 * min, 40 * min), side(true, 0, 0)).isEmpty)
    }

    @Test
    fun bothIdleEqualizeAboveThreshold() {
        val g = SharedWalletMath.plan(side(false, 10 * min, 50 * min), side(false, 4 * min, 10 * min))
        assertEquals(3 * min, g.walletMs)
        assertEquals(20 * min, g.limitMs)
        // Поровну после переноса — вторая сторона ничего не отдаёт.
        assertTrue(SharedWalletMath.plan(side(false, 4 * min, 10 * min), side(false, 10 * min, 50 * min)).isEmpty)
        // Разница меньше 2 минут — ничего.
        assertTrue(SharedWalletMath.plan(side(false, 10 * min, 30 * min), side(false, 8 * min + 1, 28 * min + 1)).isEmpty)
        // Ровно 2 минуты — отдаётся минута.
        assertEquals(min, SharedWalletMath.plan(side(false, 10 * min), side(false, 8 * min)).walletMs)
        // Без лимита доля не переносится.
        assertEquals(0L, SharedWalletMath.plan(side(false, 10 * min, null), side(false, 0, null)).limitMs)
    }

    /**
     * Свойство: при любой последовательности обменов, обрывов (перенос записан у отправителя, но не дошёл)
     * и расхода сумма кошельков сохраняется (с учётом переносов в пути), никто не уходит в минус по кошельку,
     * и суммарный расход за сутки не больше лимита.
     */
    @Test
    fun randomExchangesNeverOverspend() {
        val rnd = Random(42)
        repeat(300) {
            val limit = (rnd.nextInt(0, 241) * min)
            val wallet = longArrayOf(rnd.nextLong(0, 200 * min), rnd.nextLong(0, 200 * min))
            val total = wallet.sum()
            val spent = longArrayOf(0, 0)
            val incoming = longArrayOf(0, 0)
            val outgoing = longArrayOf(0, 0)
            val ids = arrayOf(a, b)
            // Переносы в пути: (кому, минуты, доля).
            val inFlight = ArrayList<Triple<Int, Long, Long>>()
            fun share(i: Int) = SharedWalletMath.shareLeftMs(limit, ids[i], ids[1 - i], incoming[i], outgoing[i], spent[i])
            repeat(60) {
                val i = rnd.nextInt(2)
                when (rnd.nextInt(4)) {
                    0 -> { // расход: не больше кошелька и доли
                        val want = rnd.nextLong(0, 30 * min)
                        val can = minOf(want, wallet[i], share(i) ?: Long.MAX_VALUE).coerceAtLeast(0L)
                        wallet[i] -= can
                        spent[i] += can
                    }
                    1, 2 -> { // обмен: каждая сторона считает «от себя»
                        val spendingFlags = booleanArrayOf(rnd.nextBoolean(), rnd.nextBoolean())
                        for (me in 0..1) {
                            val peer = 1 - me
                            val g = SharedWalletMath.plan(
                                Side(spendingFlags[me], wallet[me], share(me), 1),
                                Side(spendingFlags[peer], wallet[peer], share(peer), 1),
                            )
                            val w = minOf(g.walletMs, wallet[me]).coerceAtLeast(0L)
                            val l = minOf(g.limitMs, share(me) ?: 0L).coerceAtLeast(0L)
                            wallet[me] -= w
                            outgoing[me] += l
                            inFlight += Triple(peer, w, l)
                        }
                        // Часть переносов доходит сразу, часть — позже (обрыв).
                        val (arrived, still) = inFlight.partition { rnd.nextBoolean() }
                        inFlight.clear()
                        inFlight.addAll(still)
                        for ((to, w, l) in arrived) {
                            wallet[to] += w
                            incoming[to] += l
                        }
                    }
                    else -> { // догоняющий обмен: всё в пути доходит
                        for ((to, w, l) in inFlight) {
                            wallet[to] += w
                            incoming[to] += l
                        }
                        inFlight.clear()
                    }
                }
                val inFlightWallet = inFlight.sumOf { it.second }
                assertEquals(total, wallet.sum() + spent.sum() + inFlightWallet)
                assertTrue(wallet.all { it >= 0L })
                if (limit > 0) assertTrue("расход ${spent.sum()} > лимит $limit", spent.sum() <= limit)
            }
        }
    }

    // ---------- Общие настройки ----------

    @Test
    fun mergeTakesHigherVersionThenBiggerAuthor() {
        val x = Setting("daily_limit", "90", 3, a)
        val y = Setting("daily_limit", "60", 2, b)
        assertEquals(x, SharedWalletMath.merge(x, y))
        assertEquals(x, SharedWalletMath.merge(y, x))
        val z = Setting("daily_limit", "60", 3, b)
        assertEquals(z, SharedWalletMath.merge(x, z))
        assertEquals(z, SharedWalletMath.merge(z, x))
        assertEquals(x, SharedWalletMath.merge(x, null))
    }

    @Test
    fun mergeMinKeysTakeSmallestDay() {
        val x = Setting("saitama_since", "20100", 9, b)
        val y = Setting("saitama_since", "20050", 1, a)
        assertEquals(y, SharedWalletMath.merge(x, y))
        assertEquals(y, SharedWalletMath.merge(y, x))
        val bad = Setting("stats_penalty_since", "x", 5, b)
        val good = Setting("stats_penalty_since", "20000", 1, a)
        assertEquals(good, SharedWalletMath.merge(bad, good))
    }

    @Test
    fun mergeAllAndNextVersion() {
        val local = listOf(Setting("night", "1,1380,420", 1, a), Setting("daily_limit", "120", 0, a))
        val remote = listOf(Setting("night", "0,1380,420", 2, b), Setting("activity_source", b, 1, a))
        val m = SharedWalletMath.mergeAll(local, remote)
        assertEquals("0,1380,420", m.getValue("night").value)
        assertEquals("120", m.getValue("daily_limit").value)
        assertEquals(b, m.getValue("activity_source").value)
        assertEquals(3L, SharedWalletMath.nextVersion(m.values, "night"))
        assertEquals(1L, SharedWalletMath.nextVersion(m.values, "unknown"))
        // Слияние на обоих телефонах даёт одно и то же.
        assertEquals(m, SharedWalletMath.mergeAll(remote, local))
    }

    @Test
    fun defaultActivitySource() {
        assertEquals(b, SharedWalletMath.defaultActivitySource(a, false, b, true))
        assertEquals(a, SharedWalletMath.defaultActivitySource(a, true, b, false))
        assertEquals(a, SharedWalletMath.defaultActivitySource(b, true, a, true))
        assertEquals(a, SharedWalletMath.defaultActivitySource(b, false, a, false))
        assertFalse(SharedWalletMath.defaultActivitySource(a, false, b, true) == a)
    }

    // ---------- Долги ----------

    @Test
    fun leftoverDebtsKeepUnchargedStart() {
        val d1 = UsageDebt(0, 10_000, "p", "s1")
        val d2 = UsageDebt(20_000, 30_000, "p", "s2")
        // Хватило на первый долг целиком и 4 с второго: от второго остаются первые 6 с.
        val left = LedgerMath.leftoverDebts(listOf(d2, d1), "q", "cur", 50_000, 5_000, 14_000)
        assertEquals(listOf(UsageDebt(20_000, 26_000, "p", "s2"), UsageDebt(45_000, 50_000, "q", "cur")), left)
        // Списано всё — ничего не осталось.
        assertTrue(LedgerMath.leftoverDebts(listOf(d1), "q", "cur", 50_000, 5_000, 15_000).isEmpty())
        // Ничего не списано — остаются все долги и текущий просмотр.
        assertEquals(3, LedgerMath.leftoverDebts(listOf(d1, d2), "q", "cur", 50_000, 5_000, 0).size)
        // Сумма списанного и оставшегося — всё, что было.
        val all = listOf(d1, d2)
        val charged = 12_345L
        val rest = LedgerMath.leftoverDebts(all, "q", "cur", 50_000, 5_000, charged)
        assertEquals(25_000L, charged + rest.sumOf { it.durationMs })
    }

    @Test
    fun transferKindsAreNeitherRewardNorSpending() {
        for (k in OpKind.TRANSFERS) {
            assertFalse(k in OpKind.REWARDS)
            assertFalse(k in OpKind.SPENDING)
        }
        assertEquals("transfer_in:abc", LedgerMath.transferInKey("abc"))
        assertEquals(OpKind.TRANSFER_OUT, OpKind.parse("TRANSFER_OUT"))
        assertNull(OpKind.parse("FUTURE_KIND"))
        assertEquals("Получено со второго телефона", OpKind.TRANSFER_IN.label())
    }
}
