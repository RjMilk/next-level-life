package com.nextlevellife.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Вес подхода зала в обмене (протокол 5): кодек, правило приёма и обмен в памяти. */
class GymSyncTest {
    private fun exchange(a: MemorySyncStore, b: MemorySyncStore): List<Throwable?> {
        val pair = MemoryChannelPair(a.deviceId, b.deviceId, Int.MAX_VALUE)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val fa = pool.submit(Callable { runCatching { SyncSession(a, pair.forA).run() } })
            val fb = pool.submit(Callable { runCatching { SyncSession(b, pair.forB).run() } })
            return listOf(fa.get(30, TimeUnit.SECONDS).exceptionOrNull(), fb.get(30, TimeUnit.SECONDS).exceptionOrNull())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun ok(errors: List<Throwable?>) {
        errors.forEachIndexed { i, e -> if (e != null) throw AssertionError("${"AB"[i]}: $e", e) }
    }

    private val me = "aaa"
    private val peer = "bbb"

    private fun set(exerciseId: String, loadG: Int?) =
        SetFact("s1", peer, "e", 1, 1_700_000_000_000, 19_676, exerciseId, "Х", null, 5, 60_000, 150_000, 0, null, "REPS", 0, loadG)

    private fun accept(f: FactRecord) = SyncRules.acceptFact(f, me, "cur", peer)

    @Test
    fun protocolVersionIsFive() {
        assertEquals(5, SyncCodec.PROTOCOL_VERSION)
    }

    @Test
    fun codecRoundTripWithLoad() {
        val m = SyncMessage.Facts(
            listOf(
                set("bb_squat", 102_500),
                set("bb_press", null).copy(uid = "s2", aseq = 2),
                set("bb_row", 0).copy(uid = "s3", aseq = 3),
                set("bb_deadlift", 400_000).copy(uid = "s4", aseq = 4),
                SetFact("s5", "a", "e", 5, 1, 2, "pushups", "Отжимания", null, 10, 0, 300_000, 0, "r1", "REPS", 0),
            ),
        )
        val back = SyncCodec.decode(SyncCodec.encode(m))
        assertEquals(m, back)
        assertEquals(102_500, ((back as SyncMessage.Facts).rows[0] as SetFact).loadG)
        assertNull((back.rows[1] as SetFact).loadG)
        assertNull((back.rows[4] as SetFact).loadG)
    }

    @Test
    fun loadAcceptRule() {
        // Без веса — всегда, у любого упражнения.
        assertTrue(accept(set("pushups", null)))
        assertTrue(accept(set("bb_squat", null)))
        // Общие пределы обмена 0..400 кг, кратно 0,5 кг.
        // У известного упражнения зала — пределы его способа учёта (штанга от 20 кг).
        assertFalse(accept(set("bb_squat", 0)))
        assertFalse(accept(set("bb_squat", 500)))
        assertFalse(accept(set("bb_squat", 19_500)))
        assertTrue(accept(set("bb_squat", 20_000)))
        assertTrue(accept(set("bb_squat", 62_500)))
        assertTrue(accept(set("bb_deadlift", 400_000)))
        assertFalse(accept(set("bb_deadlift", 400_500)))
        assertFalse(accept(set("bb_squat", -500)))
        assertFalse(accept(set("bb_squat", 62_250)))
        assertFalse(accept(set("bb_squat", 1)))
        assertFalse(accept(set("bb_squat", Int.MAX_VALUE)))
        assertFalse(accept(set("bb_squat", Int.MIN_VALUE)))
        // Вес у домашних упражнений и программ ЛФК — нет.
        assertFalse(accept(set("pushups", 20_000)))
        assertFalse(accept(set("press", 16_000)))
        assertFalse(accept(set("plank", 0)))
        assertFalse(accept(set("lfk_neck_tilt", 20_000)))
        // Неизвестный этой версии id (упражнение зала из более новой версии) — принимается.
        assertTrue(accept(set("db_bench", 30_000)))
        // Прочие правила подхода по-прежнему действуют.
        assertFalse(accept(set("bb_squat", 60_000).copy(reps = -1)))
        assertFalse(accept(set("bb_squat", 60_000).copy(aseq = 0)))
    }

    @Test
    fun setWithLoadTravelsBothWays() {
        val a = MemorySyncStore(me)
        val b = MemorySyncStore(peer)
        val s1 = a.addSet(5, "bb_squat", "Присед со штангой", loadG = 100_000)
        val s2 = b.addSet(8, "bb_press", "Жим штанги стоя", loadG = 42_500)
        val s3 = a.addSet(10)
        ok(exchange(a, b))
        assertEquals(100_000, (b.facts[s1.uid] as SetFact).loadG)
        assertEquals(42_500, (a.facts[s2.uid] as SetFact).loadG)
        assertNull((b.facts[s3.uid] as SetFact).loadG)
        assertEquals(s1, b.facts[s1.uid])
        // Повтор обмена ничего не дублирует.
        ok(exchange(a, b))
        assertEquals(3, a.facts.size)
        assertEquals(3, b.facts.size)
    }

    @Test
    fun rejectedLoadDoesNotBlockLaterSets() {
        val a = MemorySyncStore(me)
        val good1 = a.addSet(5, "bb_row", "Тяга штанги в наклоне", loadG = 60_000)
        val bad = a.addSet(10, loadG = 20_000) // отжимания с весом — не принимаются
        val good2 = a.addSet(5, "bb_row", "Тяга штанги в наклоне", loadG = 62_500)
        val b = MemorySyncStore(peer)
        b.applyFacts(me, listOf(good1, bad, good2))
        assertTrue(good1.uid in b.facts)
        assertFalse(bad.uid in b.facts)
        assertTrue(good2.uid in b.facts)
    }
}
