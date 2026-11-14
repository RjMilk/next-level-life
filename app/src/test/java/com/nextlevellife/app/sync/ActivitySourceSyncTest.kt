package com.nextlevellife.app.sync

import com.nextlevellife.app.data.OpKind
import com.nextlevellife.app.data.SharedWalletMath
import com.nextlevellife.app.health.ActivityMath
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ActivitySourceSyncTest {
    private fun exchange(
        a: MemorySyncStore, b: MemorySyncStore, breakAfter: Int = Int.MAX_VALUE,
        onASend: (SyncMessage) -> Unit = {},
    ): Pair<Throwable?, Throwable?> {
        val pair = MemoryChannelPair(a.deviceId, b.deviceId, breakAfter)
        val aChannel = object : SyncChannel by pair.forA {
            override fun send(message: ByteArray) {
                onASend(SyncCodec.decode(message))
                pair.forA.send(message)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        return try {
            val fa = pool.submit(Callable { runCatching { SyncSession(a, aChannel).run() }.exceptionOrNull() })
            val fb = pool.submit(Callable { runCatching { SyncSession(b, pair.forB).run() }.exceptionOrNull() })
            fa.get(15, TimeUnit.SECONDS) to fb.get(15, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun success(result: Pair<Throwable?, Throwable?>) {
        result.first?.let { throw AssertionError("A", it) }
        result.second?.let { throw AssertionError("B", it) }
    }

    @Test
    fun firstExchangePrefersPhoneWithMoreLocalHealthSteps() {
        val a = MemorySyncStore("aaa").apply { healthConnected = true; healthSteps7d = 40L }
        val b = MemorySyncStore("bbb").apply { healthConnected = true; healthSteps7d = 12_000L }
        success(exchange(a, b))
        assertEquals("bbb", a.settingRows.getValue("activity_source").value)
        assertEquals(a.settingRows, b.settingRows)
        assertFalse(a.readsActivity("bbb"))
        assertTrue(b.readsActivity("aaa"))
        assertTrue(a.handoffSupported && b.handoffSupported)
    }

    @Test
    fun everyBrokenHandoffRemainsExclusiveAndCanResume() {
        for (cut in 0..18) {
            val a = MemorySyncStore("aaa")
            val b = MemorySyncStore("bbb")
            success(exchange(a, b))
            assertTrue(a.readsActivity("bbb"))
            b.setSetting("activity_source", "bbb")
            assertFalse(b.readsActivity("aaa"))
            exchange(a, b, breakAfter = cut)
            assertFalse("Обрыв после $cut", a.readsActivity("bbb") && b.readsActivity("aaa"))
            success(exchange(a, b))
            assertFalse(a.readsActivity("bbb"))
            assertTrue(b.readsActivity("aaa"))
        }
    }

    @Test
    fun handoffWorksInBothDirectionsFromEitherPhoneAndChannelRole() {
        for (oldSource in listOf("aaa", "bbb")) {
            for (selectOnOld in listOf(false, true)) {
                for (oldInitiates in listOf(false, true)) {
                    val a = MemorySyncStore("aaa")
                    val b = MemorySyncStore("bbb")
                    success(exchange(a, b))
                    if (oldSource == "bbb") {
                        b.setSetting("activity_source", "bbb")
                        success(exchange(a, b))
                    }
                    val old = if (oldSource == "aaa") a else b
                    val next = if (old === a) b else a
                    (if (selectOnOld) old else next).setSetting("activity_source", next.deviceId)
                    assertFalse(old.readsActivity(next.deviceId) && next.readsActivity(old.deviceId))
                    success(if (oldInitiates) exchange(old, next) else exchange(next, old))
                    assertFalse(old.readsActivity(next.deviceId))
                    assertTrue(next.readsActivity(old.deviceId))
                }
            }
        }
    }

    @Test
    fun concurrentChoicesAtEqualVersionConvergeExclusively() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        success(exchange(a, b))
        a.setSetting("activity_source", "bbb")
        b.setSetting("activity_source", "aaa")
        assertEquals(a.settingRows.getValue("activity_source").version, b.settingRows.getValue("activity_source").version)
        assertFalse(a.readsActivity("bbb") || b.readsActivity("aaa"))
        success(exchange(a, b))
        assertEquals(a.settingRows, b.settingRows)
        assertEquals("aaa", a.settingRows.getValue("activity_source").value)
        assertTrue(a.readsActivity("bbb"))
        assertFalse(b.readsActivity("aaa"))
    }

    @Test
    fun lateLocalChoiceCannotBeConfirmedByEarlierSettingsSnapshot() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        success(exchange(a, b))
        success(exchange(a, b, onASend = { message ->
            // Все Settings и журналы уже отправлены. Этот выбор ещё не видел второй телефон.
            if (message == SyncMessage.Done) a.setSetting("activity_source", "bbb")
        }))
        assertTrue(a.sourcePending)
        assertFalse(a.readsActivity("bbb"))
        assertFalse(b.readsActivity("aaa"))
        success(exchange(a, b))
        assertTrue(b.readsActivity("aaa"))
    }

    @Test
    fun finalOldSourceRewardArrivesBeforeNewSourceCanPay() {
        val a = MemorySyncStore("aaa")
        val b = MemorySyncStore("bbb")
        success(exchange(a, b))
        a.rewardSteps(1_000L)
        b.setSetting("activity_source", "bbb")
        var afterFirstOps = false
        success(exchange(a, b, onASend = { message ->
            if (message == SyncMessage.EndLogs && !afterFirstOps) {
                afterFirstOps = true
                assertTrue(a.readsActivity("bbb"))
                // Оплачено после первой выгрузки ops, пока выбор B ещё не дошёл до A.
                a.rewardSteps(500L)
            }
        }))
        assertTrue(b.readsActivity("aaa"))
        val importedPaid = b.peerOps.getValue("aaa").values
            .filter { it.kind == OpKind.STEPS_REWARD.name && it.sourceId == b.day.toString() }.sumOf { it.deltaMs }
        assertEquals(1_500L, importedPaid)
        val paid = ActivityMath.paidBeforeReward(0L, importedPaid)
        assertEquals(0L, ActivityMath.rewardDelta(1_500L, paid, true, b.day, b.day))
        assertEquals(500L, ActivityMath.rewardDelta(2_000L, paid, true, b.day, b.day))
        // Старая локальная строка нового источника тоже сохраняет уже оплаченный максимум.
        assertEquals(2_000L, ActivityMath.paidBeforeReward(2_000L, importedPaid))
        b.rewardSteps(500L)
        a.setSetting("activity_source", "aaa")
        success(exchange(a, b))
        assertTrue(a.readsActivity("bbb"))
        val ownPaid = a.ops.values.filter { it.kind == OpKind.STEPS_REWARD.name && it.sourceId == a.day.toString() }.sumOf { it.deltaMs }
        val peerPaid = a.peerOps.getValue("bbb").values.filter { it.kind == OpKind.STEPS_REWARD.name && it.sourceId == a.day.toString() }.sumOf { it.deltaMs }
        val afterReturn = ActivityMath.paidBeforeReward(1_500L, ownPaid + peerPaid)
        assertEquals(2_000L, afterReturn)
        assertEquals(0L, ActivityMath.rewardDelta(2_000L, afterReturn, true, a.day, a.day))
        assertEquals(300L, ActivityMath.rewardDelta(2_300L, afterReturn, true, a.day, a.day))
    }

    @Test
    fun exactSnapshotAndDeterministicMergeIncludeVersionAuthorAndValue() {
        val old = SharedWalletMath.Setting("activity_source", "aaa", 1L, "aaa")
        val newer = old.copy(value = "bbb", version = 2L)
        assertFalse(SharedWalletMath.sourceConfirmed(newer, old, old))
        assertFalse(SharedWalletMath.sourceConfirmed(newer, newer, old))
        assertFalse(SharedWalletMath.sourceConfirmed(null, null, null))
        assertTrue(SharedWalletMath.sourceConfirmed(newer, newer, newer))
        assertEquals(newer, SharedWalletMath.merge(old, newer))
        assertEquals(newer, SharedWalletMath.merge(newer, old))
        val conflict = newer.copy(value = "aaa")
        assertEquals(SharedWalletMath.merge(newer, conflict), SharedWalletMath.merge(conflict, newer))
    }

    @Test
    fun metadataIsCodecCompatibleAndValidatesAuthorWindowAndCapability() {
        val state = LocalState("ops", "facts", 42L, "UTC", false, 0L, null, true, 100L)
        val hello = SyncMessage.Hello(SyncCodec.PROTOCOL_VERSION, "aaa", "ops", "facts", 42L,
            "UTC", false, 0L, null, true, ActivityHelloMetadata.cursors("aaa", state))
        assertEquals(hello, SyncCodec.decode(SyncCodec.encode(hello)))
        assertEquals(100L, ActivityHelloMetadata.steps(hello, 42L, "UTC"))
        assertNull(ActivityHelloMetadata.steps(hello, 43L, "UTC"))
        assertNull(ActivityHelloMetadata.steps(hello.copy(deviceId = "bbb"), 42L, "UTC"))
        assertTrue(ActivityHelloMetadata.supportsHandoff(hello))
        assertFalse(ActivityHelloMetadata.supportsHandoff(hello.copy(cursors = emptyList())))
    }
}
