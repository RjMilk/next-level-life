package com.nextlevellife.app.sync

import com.nextlevellife.app.sync.transport.ChannelSlot
import com.nextlevellife.app.sync.transport.PeerKey
import com.nextlevellife.app.sync.transport.SecureChannel
import com.nextlevellife.app.sync.transport.SessionHandshake.Purpose
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Рукопожатие сессии и шифрованные кадры через настоящие сокеты на localhost и потоки в памяти. */
class SyncChannelTest {
    private val phones = mutableListOf<FakePhone>()

    @After
    fun tearDown() = phones.forEach { it.close() }

    private fun pair(readTimeoutMs: Int = 5_000) = FakePhone.pairedPair(readTimeoutMs).also { phones += it.first; phones += it.second }

    /** Обработчик-эхо: на каждое сообщение отвечает тем же с префиксом "re:", пока канал жив. */
    private fun echo(phone: FakePhone, seen: LinkedBlockingQueue<SyncChannel>? = null) {
        phone.handler = { ch ->
            seen?.add(ch)
            ch.use {
                try {
                    while (true) {
                        val m = it.receive()
                        it.send("re:".toByteArray() + m)
                    }
                } catch (e: IOException) {
                    // Клиент закрыл канал.
                }
            }
        }
    }

    @Test
    fun messagesFlowBothWays() {
        val (a, b) = pair()
        val seen = LinkedBlockingQueue<SyncChannel>()
        echo(a, seen)
        val ch = b.core.connect(a.address, Purpose.SYNC)
        assertNotNull(ch)
        ch!!.use {
            assertTrue(it.initiator)
            assertEquals(a.id, it.peerDeviceId)
            for (i in 0 until 20) {
                it.send("привет $i".toByteArray())
                assertEquals("re:привет $i", String(it.receive()))
            }
            it.send(ByteArray(0))
            assertEquals("re:", String(it.receive()))
        }
        val incoming = seen.poll(2, TimeUnit.SECONDS)!!
        assertFalse(incoming.initiator)
        assertEquals(b.id, incoming.peerDeviceId)
    }

    @Test
    fun largeMessageAtLimit() {
        val (a, b) = pair()
        a.handler = { ch ->
            ch.use {
                val m = it.receive()
                it.send(m.reversedArray())
            }
        }
        val big = ByteArray(SyncChannel.MAX_MESSAGE_BYTES).also { SecureRandom().nextBytes(it) }
        b.core.connect(a.address, Purpose.SYNC)!!.use {
            it.send(big)
            assertArrayEquals(big.reversedArray(), it.receive())
            try {
                it.send(ByteArray(SyncChannel.MAX_MESSAGE_BYTES + 1))
                fail("сообщение больше предела должно отклоняться")
            } catch (e: IOException) {
                // ожидаемо
            }
        }
    }

    @Test
    fun wrongPairKey_isRejected() {
        val (a, b) = pair()
        val called = AtomicBoolean(false)
        a.handler = { called.set(true); it.close() }
        b.peer = PeerKey(a.id, a.name, ByteArray(32) { 1 })
        try {
            b.core.connect(a.address, Purpose.SYNC)
            fail("неверный ключ пары должен обрывать соединение")
        } catch (e: IOException) {
            // ожидаемо
        }
        Thread.sleep(100)
        assertFalse(called.get())
        assertFalse(b.core.slot.isBusy())
        assertFalse(a.core.slot.isBusy())
    }

    @Test
    fun foreignDeviceId_isRejected() {
        val (a, b) = pair()
        a.handler = { fail("чужой телефон не должен дойти до обработчика") }
        val stranger = FakePhone("cccccccc-0000-0000-0000-000000000003", "Чужой").also { phones += it }
        stranger.peer = PeerKey(a.id, a.name, a.peer!!.key.copyOf())
        try {
            stranger.core.connect(a.address, Purpose.SYNC)
            fail("непарный device_id должен обрывать соединение")
        } catch (e: IOException) {
            // ожидаемо
        }
        // Сервер не тот, к кому шли: клиент сам рвёт соединение.
        b.peer = PeerKey("dddddddd-0000-0000-0000-000000000004", "Другой", b.peer!!.key)
        try {
            b.core.connect(a.address, Purpose.SYNC)
            fail("ответ чужого сервера должен обрывать соединение")
        } catch (e: IOException) {
            // ожидаемо
        }
    }

    @Test
    fun noHandler_rejectsIncoming() {
        val (a, b) = pair()
        a.handler = null
        try {
            b.core.connect(a.address, Purpose.SYNC)
            fail("без обработчика входящие отклоняются")
        } catch (e: IOException) {
            // ожидаемо
        }
        assertFalse(a.core.slot.isBusy())
    }

    @Test
    fun readTimeout_throwsIOException() {
        val (a, b) = pair(readTimeoutMs = 300)
        val release = CountDownLatch(1)
        a.handler = { ch -> release.await(5, TimeUnit.SECONDS); ch.close() }
        val ch = b.core.connect(a.address, Purpose.SYNC)!!
        val started = System.nanoTime()
        try {
            ch.receive()
            fail("ожидание без данных должно кончаться тайм-аутом")
        } catch (e: SocketTimeoutException) {
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("тайм-аут через $ms мс", ms in 200..3_000)
        } finally {
            release.countDown()
        }
        // После тайм-аута канал закрыт и слот свободен.
        try {
            ch.send(byteArrayOf(1))
            fail("после тайм-аута канал закрыт")
        } catch (e: IOException) {
            // ожидаемо
        }
        assertFalse(b.core.slot.isBusy())
    }

    @Test
    fun onlyOneChannelAtATime() {
        val (a, b) = pair()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        a.handler = { ch -> entered.countDown(); release.await(5, TimeUnit.SECONDS); ch.close() }
        b.handler = { it.close() }
        val first = b.core.connect(a.address, Purpose.SYNC)!!
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        // Наш исходящий открыт — второй open возвращает null.
        assertNull(b.core.connect(a.address, Purpose.SYNC))
        // A обслуживает входящий — его open тоже null.
        assertNull(a.core.connect(b.address, Purpose.SYNC))
        release.countDown()
        first.close()
        Thread.sleep(200)
        assertFalse(a.core.slot.isBusy())
        assertFalse(b.core.slot.isBusy())
        val again = b.core.connect(a.address, Purpose.SYNC)
        assertNotNull(again)
        again!!.close()
    }

    @Test
    fun pingAndUnpair_doNotWakeHandler() {
        val (a, b) = pair()
        val calls = AtomicInteger()
        a.handler = { calls.incrementAndGet(); it.close() }
        assertTrue(b.core.ping(a.address))
        assertEquals(0, calls.get())
        assertTrue(b.core.notifyUnpair(a.address))
        Thread.sleep(100)
        assertEquals(1, a.unpairedByPeer.get())
        assertNull(a.peer)
        // A забыл пару — ping больше не проходит.
        assertFalse(b.core.ping(a.address))
    }

    @Test
    fun slot_simultaneousConnects_smallerIdWins() {
        val small = "aaaaaaaa-0000-0000-0000-000000000001"
        val big = "bbbbbbbb-0000-0000-0000-000000000002"
        // Сторона с большим id: её исходящее в рукопожатии, приходит встречное от меньшего — принять, своё отменить.
        val bigSlot = ChannelSlot { big }
        val cancelled = AtomicBoolean(false)
        val out = bigSlot.beginOutgoing { cancelled.set(true) }!!
        val ticket = bigSlot.acceptIncoming(small)
        assertNotNull(ticket)
        assertTrue(cancelled.get())
        assertFalse(bigSlot.outgoingEstablished(out))
        bigSlot.release(out) // чужой билет слот не освобождает
        assertTrue(bigSlot.isBusy())
        bigSlot.release(ticket!!)
        assertFalse(bigSlot.isBusy())

        // Сторона с меньшим id: встречное от большего отклоняется, своё исходящее живёт.
        val smallSlot = ChannelSlot { small }
        val out2 = smallSlot.beginOutgoing { fail("исходящее меньшего не отменяется") }!!
        assertNull(smallSlot.acceptIncoming(big))
        assertTrue(smallSlot.outgoingEstablished(out2))
        // Уже открытый канал: любое входящее отклоняется, новое исходящее не начинается.
        assertNull(smallSlot.acceptIncoming(big))
        assertNull(smallSlot.beginOutgoing { })
        smallSlot.release(out2)
        assertNotNull(smallSlot.beginOutgoing { })
    }

    @Test
    fun slot_establishedOutgoingIsNotCancelled() {
        val slot = ChannelSlot { "bbbbbbbb-0000-0000-0000-000000000002" }
        val out = slot.beginOutgoing { fail("открытый канал не отменяется") }!!
        assertTrue(slot.outgoingEstablished(out))
        assertNull(slot.acceptIncoming("aaaaaaaa-0000-0000-0000-000000000001"))
    }

    // ---------- Кадры в памяти: подмена, повтор, перестановка ----------

    private val k1 = ByteArray(32) { 11 }
    private val k2 = ByteArray(32) { 22 }

    /** Отправитель пишет кадры в память; возвращает их по одному (длина + шифротекст). */
    private fun sealFrames(vararg messages: String): List<ByteArray> {
        val sink = ByteArrayOutputStream()
        val sender = SecureChannel(ByteArrayInputStream(ByteArray(0)), sink, k1.copyOf(), k2.copyOf(), "peer-0001", true) {}
        messages.forEach { sender.send(it.toByteArray()) }
        val all = ByteBuffer.wrap(sink.toByteArray())
        val frames = mutableListOf<ByteArray>()
        while (all.hasRemaining()) {
            val len = all.getInt(all.position())
            val f = ByteArray(4 + len)
            all.get(f)
            frames += f
        }
        return frames
    }

    private fun receiverOf(vararg frames: ByteArray): SecureChannel {
        val wire = ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        return SecureChannel(ByteArrayInputStream(wire), ByteArrayOutputStream(), k2.copyOf(), k1.copyOf(), "peer-0002", false) {}
    }

    private fun assertBroken(ch: SecureChannel) {
        try {
            ch.receive()
            fail("испорченный поток должен рвать канал")
        } catch (e: IOException) {
            assertTrue(ch.isClosed)
        }
    }

    @Test
    fun frames_inOrder_ok() {
        val (f1, f2) = sealFrames("один", "два")
        val r = receiverOf(f1, f2)
        assertEquals("один", String(r.receive()))
        assertEquals("два", String(r.receive()))
    }

    @Test
    fun frames_replay_isRejected() {
        val (f1, _) = sealFrames("один", "два")
        val r = receiverOf(f1, f1)
        assertEquals("один", String(r.receive()))
        assertBroken(r)
    }

    @Test
    fun frames_reorder_isRejected() {
        val (f1, f2) = sealFrames("один", "два")
        assertBroken(receiverOf(f2, f1))
    }

    @Test
    fun frames_tamper_isRejected() {
        val (f1) = sealFrames("один")
        val bad = f1.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        assertBroken(receiverOf(bad))
        // Подмена длины: AAD включает длину, а сам кадр обрежется.
        val shorter = f1.copyOf().also { ByteBuffer.wrap(it).putInt(0, ByteBuffer.wrap(f1).getInt(0) - 1) }
        assertBroken(receiverOf(shorter))
        // Длина больше предела не приводит к выделению памяти — сразу обрыв.
        assertBroken(receiverOf(ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()))
    }

    @Test
    fun frames_wrongDirectionKey_isRejected() {
        val (f1) = sealFrames("один")
        // Кадр, зашифрованный ключом клиент→сервер, не читается как сервер→клиент (своё же эхо).
        val reflected = SecureChannel(ByteArrayInputStream(f1), ByteArrayOutputStream(), k2.copyOf(), k2.copyOf(), "x-000000", false) {}
        assertBroken(reflected)
    }

    @Test
    fun pipedStreams_twoThreads() {
        val aIn = PipedInputStream(1 shl 16)
        val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(1 shl 16)
        val aOut = PipedOutputStream(bIn)
        val a = SecureChannel(aIn, aOut, k1.copyOf(), k2.copyOf(), "b-000000", true) {}
        val b = SecureChannel(bIn, bOut, k2.copyOf(), k1.copyOf(), "a-000000", false) {}
        val t = Thread { repeat(50) { b.send(b.receive() + byteArrayOf(it.toByte())) } }
        t.start()
        repeat(50) {
            a.send(ByteArray(1000) { i -> i.toByte() })
            val r = a.receive()
            assertEquals(1001, r.size)
            assertEquals(it.toByte(), r.last())
        }
        t.join(5_000)
        val closed = AtomicBoolean(false)
        val c = SecureChannel(aIn, aOut, k1.copyOf(), k2.copyOf(), "b-000000", true) { closed.set(true) }
        c.close()
        c.close()
        assertTrue(closed.get())
        assertSame(true, c.isClosed)
    }
}
