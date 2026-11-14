package com.nextlevellife.app.sync

import com.nextlevellife.app.sync.transport.PairOutcome
import com.nextlevellife.app.sync.transport.PairingInvite
import com.nextlevellife.app.sync.transport.PairingTokens
import com.nextlevellife.app.sync.transport.PeerKey
import com.nextlevellife.app.sync.transport.SessionHandshake.Purpose
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/** Сопряжение по одноразовому токену из QR: обе стороны в памяти, связь — сокеты на localhost. */
class PairingTest {
    private val phones = mutableListOf<FakePhone>()
    private val now = AtomicLong(1_000_000L)

    @After
    fun tearDown() = phones.forEach { it.close() }

    private fun phone(id: String, name: String) = FakePhone(id, name, clock = { now.get() }).also { phones += it }

    private val idA = "aaaaaaaa-1111-2222-3333-444444444444"
    private val idB = "bbbbbbbb-1111-2222-3333-444444444444"

    private fun waitFor(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 2_000
        while (!cond() && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test
    fun bothSidesGetSameKey_andCanTalk() {
        val a = phone(idA, "Galaxy S25")
        val b = phone(idB, "Galaxy Z Fold8")
        val invite = PairingInvite(a.id, a.core.tokens.issue(), a.name)
        // QR проходит через текст — как на самом деле.
        val scanned = PairingInvite.parse(invite.format())!!

        assertEquals(PairOutcome.OK, b.core.pair(a.address, scanned))
        waitFor { a.peer != null }

        val pa = a.peer!!
        val pb = b.peer!!
        assertEquals(idB, pa.deviceId)
        assertEquals("Galaxy Z Fold8", pa.name)
        assertEquals(idA, pb.deviceId)
        assertEquals("Galaxy S25", pb.name)
        assertEquals(32, pa.key.size)
        assertArrayEquals(pa.key, pb.key)
        assertFalse("ключ пары не равен токену", pa.key.contentEquals(scanned.token))
        // B знает адрес A, A адрес B не знает (найдёт через поиск).
        assertEquals(a.address, b.paired.single().second)
        assertNull(a.paired.single().second)
        // Токен израсходован.
        assertNull(a.core.tokens.current())

        // Новый ключ сразу годится для канала.
        a.handler = { ch -> ch.use { it.send(it.receive() + byteArrayOf(9)) } }
        b.core.connect(a.address, Purpose.SYNC)!!.use {
            it.send(byteArrayOf(1, 2))
            assertArrayEquals(byteArrayOf(1, 2, 9), it.receive())
        }
    }

    @Test
    fun wrongToken_isRejected_andRevokedAfterThreeTries() {
        val a = phone(idA, "S25")
        val b = phone(idB, "Fold")
        a.core.tokens.issue()
        val forged = PairingInvite(a.id, ByteArray(32) { 5 }, a.name)
        repeat(PairingTokens.MAX_FAILURES) {
            assertNotNull("токен жив до ${PairingTokens.MAX_FAILURES} ошибок", a.core.tokens.current())
            assertEquals(PairOutcome.EXPIRED, b.core.pair(a.address, forged))
        }
        waitFor { a.core.tokens.current() == null }
        assertNull(a.core.tokens.current())
        assertNull(a.peer)
        assertNull(b.peer)
    }

    @Test
    fun expiredToken_isRejected() {
        val a = phone(idA, "S25")
        val b = phone(idB, "Fold")
        val invite = PairingInvite(a.id, a.core.tokens.issue(), a.name)
        now.addAndGet(PairingTokens.TTL_MS + 1)
        assertEquals(0L, a.core.tokens.remainingMs())
        assertEquals(PairOutcome.EXPIRED, b.core.pair(a.address, invite))
        assertNull(a.peer)
        assertNull(b.peer)
    }

    @Test
    fun cancelledToken_isRejected() {
        val a = phone(idA, "S25")
        val b = phone(idB, "Fold")
        val invite = PairingInvite(a.id, a.core.tokens.issue(), a.name)
        a.core.tokens.cancel()
        assertEquals(PairOutcome.EXPIRED, b.core.pair(a.address, invite))
    }

    @Test
    fun tokenIsSingleUse() {
        val tokens = PairingTokens(SecureRandom(), { now.get() })
        val t = tokens.issue()
        assertEquals(PairingTokens.TTL_MS, tokens.remainingMs())
        assertTrue(tokens.consume(t))
        assertFalse(tokens.consume(t))
        assertNull(tokens.current())
        // Новый токен отзывает старый.
        val t1 = tokens.issue()
        val t2 = tokens.issue()
        assertFalse(tokens.consume(t1))
        assertTrue(tokens.consume(t2))
    }

    @Test
    fun qrOfAnotherPhone_isRejectedByScanner() {
        val a = phone(idA, "S25")
        val b = phone(idB, "Fold")
        val token = a.core.tokens.issue()
        // QR обещает другой device_id, а по адресу отвечает A.
        val invite = PairingInvite("cccccccc-1111-2222-3333-444444444444", token, "Чужой")
        assertEquals(PairOutcome.EXPIRED, b.core.pair(a.address, invite))
        assertNull(b.peer)
    }

    @Test
    fun alreadyPaired_isBusy() {
        val a = phone(idA, "S25")
        val b = phone(idB, "Fold")
        a.peer = PeerKey("dddddddd-1111-2222-3333-444444444444", "Старый", ByteArray(32))
        val invite = PairingInvite(a.id, a.core.tokens.issue(), a.name)
        assertEquals(PairOutcome.BUSY, b.core.pair(a.address, invite))
        // У сканирующего уже есть пара — сопряжение даже не начинается.
        val c = phone("cccccccc-1111-2222-3333-444444444444", "Третий")
        c.peer = PeerKey(idA, "S25", ByteArray(32))
        assertEquals(PairOutcome.BUSY, c.core.pair(a.address, invite))
    }

    @Test
    fun nobodyListening_isNotFound() {
        val a = phone(idA, "S25")
        val b = phone(idB, "Fold")
        val invite = PairingInvite(a.id, a.core.tokens.issue(), a.name)
        val address = a.address
        a.close()
        assertEquals(PairOutcome.NOT_FOUND, b.core.pair(address, invite))
    }
}
