package com.nextlevellife.app.sync

import com.nextlevellife.app.sync.transport.PairingTokens
import com.nextlevellife.app.sync.transport.PeerKey
import com.nextlevellife.app.sync.transport.TransportCore
import com.nextlevellife.app.sync.transport.TransportHost
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Телефон «в пробирке»: хост транспорта без Android и сервер на localhost. */
internal class FakePhone(
    val id: String,
    val name: String,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    readTimeoutMs: Int = 5_000,
) : TransportHost, AutoCloseable {
    @Volatile var peer: PeerKey? = null
    @Volatile var handler: ((SyncChannel) -> Unit)? = null
    val paired = CopyOnWriteArrayList<Pair<PairedPeer, InetSocketAddress?>>()
    val unpairedByPeer = AtomicInteger()
    val core = TransportCore(this, PairingTokens(SecureRandom(), clock), readTimeoutMs = readTimeoutMs)

    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val address = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)

    init {
        Thread {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                Thread { core.serve(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    override fun myDeviceId() = id
    override fun myName() = name
    override fun pairedPeer() = peer
    override fun onPaired(peer: PairedPeer, key: ByteArray, address: InetSocketAddress?) {
        this.peer = PeerKey(peer.deviceId, peer.name, key)
        paired += peer to address
    }

    override fun onUnpairedByPeer() {
        peer = null
        unpairedByPeer.incrementAndGet()
    }

    override fun incomingHandler() = handler

    override fun close() = server.close()

    companion object {
        /** Два уже связанных телефона с общим ключом. */
        fun pairedPair(readTimeoutMs: Int = 5_000): Pair<FakePhone, FakePhone> {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val a = FakePhone("aaaaaaaa-0000-0000-0000-000000000001", "S25", readTimeoutMs = readTimeoutMs)
            val b = FakePhone("bbbbbbbb-0000-0000-0000-000000000002", "Fold", readTimeoutMs = readTimeoutMs)
            a.peer = PeerKey(b.id, b.name, key.copyOf())
            b.peer = PeerKey(a.id, a.name, key.copyOf())
            return a to b
        }
    }
}
