package com.nextlevellife.app.sync.transport

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.sync.PairedPeer
import com.nextlevellife.app.sync.SyncChannel
import com.nextlevellife.app.sync.SyncLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Транспорт синхронизации уровня процесса: сервер TCP, объявление и поиск в сети (NSD), сопряжение по QR и
 * шифрованные каналы ко второму телефону. Реализует [SyncLink] для обмена данными.
 *
 * Жизненный цикл: [start] идемпотентен (звать из службы блокировки при подключении и с экрана сопряжения),
 * [stop] — при отключении службы. Протокол и криптография — в [TransportCore] (без Android, покрыт тестами).
 *
 * Отладочный хук для двух эмуляторов (только отладочная сборка): если есть файл `files/debug_pair.json`
 * в каталоге приложения, [start] принимает его как сопряжение с фиксированным адресом:
 * ```
 * {
 *   "peerDeviceId": "<device_id второго эмулятора>",   // его видно в logcat: «SyncTransport: свой device_id=…»
 *   "peerName": "Эмулятор B",
 *   "keyBase64": "<32 байта ключа пары, Base64; одинаковый в обоих файлах>",
 *   "host": "10.0.2.2",                                 // адрес второго эмулятора (хост ПК)
 *   "port": 47002,                                      // порт хоста ПК, проброшенный ко второму эмулятору
 *   "listenPort": 47001                                 // свой фиксированный порт сервера внутри эмулятора
 * }
 * ```
 * Пример: на A listenPort=47001, на B listenPort=47002; `adb -s <A> forward tcp:47001 tcp:47001`,
 * `adb -s <B> forward tcp:47002 tcp:47002`; в файле A — port 47002, в файле B — port 47001, host 10.0.2.2.
 * Файл кладётся через `adb shell run-as com.nextlevellife.app` и после чтения не удаляется.
 */
object SyncTransport : SyncLink {
    private const val TAG = "SyncTransport"
    private const val DEBUG_FILE = "debug_pair.json"

    /** Сколько ждать второй телефон при сопряжении (поиск + рукопожатие). */
    const val PAIR_TIMEOUT_MS = 15_000L

    private class DebugPair(val address: InetSocketAddress, val listenPort: Int)

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var started = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var debugPair: DebugPair? = null
    @Volatile private var myName: String = "Телефон"
    @Volatile private var incomingHandler: ((SyncChannel) -> Unit)? = null
    @Volatile private var pairingListener: ((PairedPeer?) -> Unit)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastLinkKey: String? = null
    private val liveSockets: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    private val workers = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "nll-sync-conn").apply { isDaemon = true }
    }

    private val _peer = MutableStateFlow<PairedPeer?>(null)

    /** Связанный телефон для экранов (обновляется при сопряжении и разрыве). */
    val peerFlow: StateFlow<PairedPeer?> = _peer

    private val host: TransportHost = object : TransportHost {
        override fun myDeviceId(): String = PairStore.myDeviceId()
        override fun myName(): String = this@SyncTransport.myName
        override fun pairedPeer(): PeerKey? = PairStore.peerKey()

        override fun onPaired(peer: PairedPeer, key: ByteArray, address: InetSocketAddress?) {
            PairStore.save(peer, key, address)
            _peer.value = peer
            core.tokens.cancel()
            Log.i(TAG, "Связано с ${peer.name}")
            notifyPairing(peer)
        }

        override fun onUnpairedByPeer() {
            PairStore.clear()
            _peer.value = null
            Log.i(TAG, "Второй телефон разорвал связь")
            notifyPairing(null)
        }

        override fun incomingHandler(): ((SyncChannel) -> Unit)? = incomingHandler

        override fun log(message: String, error: Throwable?) {
            if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message)
        }
    }

    private val core: TransportCore = TransportCore(host)

    // ---------- Жизненный цикл ----------

    /**
     * Поднять сервер и объявить себя в сети. Идемпотентен; можно звать с любого потока.
     * Без разрешения локальной сети (Android 17) сервер может не подняться — тогда повторить после выдачи.
     */
    fun start(context: Context) {
        synchronized(lock) {
            if (started) {
                // Сервер в прошлый раз не поднялся (не было сети или разрешения) — ещё попытка.
                val app = appContext
                if (app != null && serverSocket == null && serverThread?.isAlive != true) launchServer(app)
                return
            }
            val app = context.applicationContext
            appContext = app
            Store.init(app)
            myName = PairStore.myName(app)
            loadDebugPair(app)
            _peer.value = PairStore.peer()
            val t = HandlerThread("nll-sync").apply { start() }
            thread = t
            handler = Handler(t.looper)
            started = true
            launchServer(app)
            registerNetworkCallback(app)
        }
    }

    private var serverThread: Thread? = null

    private fun launchServer(app: Context) {
        serverThread = Thread({ serve(app) }, "nll-sync-server").apply {
            isDaemon = true
            start()
        }
    }

    /** Остановить сервер, снять объявление, закрыть входящие соединения. Исходящие каналы закрывает их владелец. */
    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            core.tokens.cancel()
            runCatching { serverSocket?.close() }
            serverSocket = null
            val app = appContext
            if (app != null) {
                unregisterNsd(app)
                networkCallback?.let { cb -> runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
            }
            networkCallback = null
            lastLinkKey = null
            synchronized(liveSockets) {
                liveSockets.forEach { runCatching { it.close() } }
                liveSockets.clear()
            }
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }

    /** Есть ли разрешение на локальную сеть. До Android 17 отдельного разрешения нет — всегда true. */
    fun hasNetworkPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    /** Разрешение, которое нужно запросить на этом телефоне; null — не нужно. */
    fun networkPermissionToRequest(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) Manifest.permission.ACCESS_LOCAL_NETWORK else null

    /** Телефон в Wi-Fi (или проводной) сети — без неё второй телефон не найти. */
    fun isOnLocalNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ---------- SyncLink ----------

    override fun peer(): PairedPeer? = try {
        PairStore.peer()
    } catch (e: UninitializedPropertyAccessException) {
        null // Store ещё не инициализирован (start не вызывали)
    }

    /** Блокирует до [timeoutMs]; не звать с главного потока. */
    override fun open(timeoutMs: Long): SyncChannel? {
        if (!started || peer() == null || core.slot.isBusy()) return null
        return tryPeerAddresses(timeoutMs) { address, budget ->
            core.connect(address, SessionHandshake.Purpose.SYNC, budget) ?: throw Busy
        }
    }

    override fun setIncomingHandler(handler: ((SyncChannel) -> Unit)?) {
        incomingHandler = handler
    }

    override fun setPairingListener(listener: ((PairedPeer?) -> Unit)?) {
        pairingListener = listener
    }

    // ---------- Для экрана сопряжения ----------

    /** Проверка связи без пробуждения обмена данными. Блокирует до [timeoutMs]. */
    fun ping(timeoutMs: Long = 5_000L): Boolean {
        if (!started || peer() == null) return false
        return tryPeerAddresses(timeoutMs) { address, budget ->
            if (core.ping(address, budget)) true else throw IOException("нет ответа")
        } == true
    }

    /**
     * Показать QR: новый одноразовый токен на [PairingTokens.TTL_MS]. null — сервер не поднят (нет сети или
     * разрешения) или пара уже есть. Может подождать сервер до [waitServerMs] — не звать с главного потока.
     */
    fun beginPairing(waitServerMs: Long = 3_000L): PairingInvite? {
        if (!started || peer() != null) return null
        // Сервер поднимается на своём потоке сразу после start — подождать его немного.
        val until = SystemClock.elapsedRealtime() + waitServerMs
        while (serverSocket == null && started && SystemClock.elapsedRealtime() < until) {
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        if (serverSocket == null) return null
        val token = core.tokens.issue()
        return PairingInvite(PairStore.myDeviceId(), token, myName)
    }

    /** Сколько ещё действует показанный QR, мс. */
    fun pairingRemainingMs(): Long = core.tokens.remainingMs()

    /** Экран QR закрыт: токен больше не принимается. */
    fun cancelPairing() = core.tokens.cancel()

    /** Сторона «Сканировать QR»: найти показавший телефон и связаться. Блокирует до [timeoutMs]. */
    internal fun pairWith(invite: PairingInvite, timeoutMs: Long = PAIR_TIMEOUT_MS): PairOutcome {
        val app = appContext ?: return PairOutcome.FAILED
        if (peer() != null) return PairOutcome.BUSY
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val candidates = debugPair?.let { listOf(it.address) }
            ?: NsdFinder.find(app, invite.deviceId, timeoutMs - 3_000L)
        if (candidates.isEmpty()) return PairOutcome.NOT_FOUND
        for (address in candidates) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            val outcome = core.pair(address, invite, left.toInt().coerceAtMost(TransportCore.CONNECT_TIMEOUT_MS))
            if (outcome != PairOutcome.NOT_FOUND) return outcome
        }
        return PairOutcome.NOT_FOUND
    }

    /**
     * Разорвать связь: сообщить второму телефону, если он доступен (до ~6 с), и забыть пару у себя.
     * Блокирует; не звать с главного потока.
     */
    fun unpair() {
        if (peer() == null) return
        if (started) {
            val told = tryPeerAddresses(6_000L) { address, budget ->
                if (core.notifyUnpair(address, budget)) true else throw IOException("нет ответа")
            } == true
            Log.i(TAG, if (told) "Второй телефон знает о разрыве" else "Второй телефон недоступен, связь разорвана только здесь")
        }
        PairStore.clear()
        _peer.value = null
        notifyPairing(null)
    }

    // ---------- Внутреннее ----------

    /** Слот канала занят: дальше адреса не перебирать. */
    private object Busy : RuntimeException()

    /**
     * Попробовать [attempt] по известным адресам второго телефона: отладочный, кешированный, затем найденные
     * NSD — в пределах [timeoutMs]. [attempt] бросает [IOException] при неудаче адреса и [Busy], чтобы
     * прекратить попытки. Успех по найденному адресу обновляет кеш.
     */
    private fun <T : Any> tryPeerAddresses(timeoutMs: Long, attempt: (InetSocketAddress, Int) -> T): T? {
        val app = appContext ?: return null
        val peerId = peer()?.deviceId ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        fun budget(): Int = (deadline - SystemClock.elapsedRealtime()).coerceIn(0L, TransportCore.CONNECT_TIMEOUT_MS.toLong()).toInt()
        fun tryOne(address: InetSocketAddress): T? {
            val b = budget()
            if (b <= 0) return null
            return try {
                attempt(address, b)
            } catch (e: IOException) {
                Log.i(TAG, "Нет связи по ${address.address?.hostAddress}:${address.port}: ${e.message}")
                null
            }
        }
        try {
            debugPair?.let { return tryOne(it.address) }
            val cached = PairStore.cachedAddress()
            if (cached != null) tryOne(cached)?.let { return it }
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 300) return null
            for (address in NsdFinder.find(app, peerId, left)) {
                if (address == cached) continue
                val result = tryOne(address) ?: continue
                PairStore.setCachedAddress(address)
                return result
            }
        } catch (e: Busy) {
            return null
        }
        return null
    }

    private fun notifyPairing(peer: PairedPeer?) {
        try {
            pairingListener?.invoke(peer)
        } catch (e: Exception) {
            Log.w(TAG, "Слушатель сопряжения упал", e)
        }
    }

    /** Поток сервера: открыть сокет, объявить в NSD, принимать соединения до [stop]. */
    private fun serve(app: Context) {
        val socket = try {
            openServerSocket()
        } catch (e: IOException) {
            Log.w(TAG, "Сервер не поднялся", e)
            return
        }
        synchronized(lock) {
            if (!started) {
                runCatching { socket.close() }
                return
            }
            serverSocket = socket
        }
        PairStore.setLastListenPort(socket.localPort)
        handler?.post { registerNsd(app) }
        Log.i(TAG, "Сервер слушает порт ${socket.localPort}")
        while (!socket.isClosed) {
            val s = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            liveSockets += s
            try {
                workers.execute {
                    try {
                        core.serve(s)
                    } finally {
                        liveSockets -= s
                    }
                }
            } catch (e: RejectedExecutionException) {
                // Больше четырёх одновременных соединений — лишние сразу закрываются.
                liveSockets -= s
                runCatching { s.close() }
            }
        }
        // Сокет закрыт (stop или сбой): следующий start поднимет сервер заново.
        synchronized(lock) {
            if (serverSocket === socket) serverSocket = null
        }
        runCatching { socket.close() }
    }

    private fun openServerSocket(): ServerSocket {
        val fixed = debugPair?.listenPort
        val preferred = fixed ?: PairStore.lastListenPort()
        if (preferred in 1..65535) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(preferred), 8)
                }
            } catch (e: IOException) {
                Log.i(TAG, "Порт $preferred занят, берём случайный")
            }
        }
        return ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0), 8)
        }
    }

    /** На потоке [handler]. */
    private fun registerNsd(app: Context) {
        val port = serverSocket?.localPort ?: return
        if (!started) return
        unregisterNsd(app)
        val nsd = app.getSystemService(NsdManager::class.java) ?: return
        val myId = PairStore.myDeviceId()
        val info = NsdServiceInfo().apply {
            serviceName = NsdFinder.serviceName(myId)
            serviceType = NsdFinder.SERVICE_TYPE
            setPort(port)
            setAttribute(NsdFinder.ATTR_ID, myId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // Имя могло измениться при конфликте — поиск сравнивает начало имени.
                Log.i(TAG, "Объявлен в сети как ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Не удалось объявиться в сети: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerService", e)
        }
    }

    private fun unregisterNsd(app: Context) {
        val old = registration ?: return
        registration = null
        runCatching { app.getSystemService(NsdManager::class.java)?.unregisterService(old) }
    }

    /** Смена сети или IP-адреса — перерегистрация NSD (с задержкой, чтобы не дёргать на каждом событии). */
    private fun registerNetworkCallback(app: Context) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val h = handler ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val key = linkProperties.linkAddresses.map { it.toString() }.sorted().joinToString(",")
                if (key == lastLinkKey) return
                val first = lastLinkKey == null
                lastLinkKey = key
                if (!first) reregisterSoon(app)
            }

            override fun onLost(network: Network) {
                lastLinkKey = ""
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb, h)
            networkCallback = cb
        } catch (e: RuntimeException) {
            Log.w(TAG, "Не удалось следить за сетью", e)
        }
    }

    private fun reregisterSoon(app: Context) {
        val h = handler ?: return
        h.removeCallbacksAndMessages(REREGISTER)
        h.postAtTime({ registerNsd(app) }, REREGISTER, SystemClock.uptimeMillis() + 1_500)
    }

    private val REREGISTER = Any()

    private fun loadDebugPair(app: Context) {
        debugPair = null
        val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        Log.i(TAG, "свой device_id=${PairStore.myDeviceId()}")
        val file = File(app.filesDir, DEBUG_FILE)
        if (!file.isFile) return
        try {
            val o = JSONObject(file.readText())
            val peerId = o.getString("peerDeviceId")
            val name = Names.cleanName(o.optString("peerName", "")) ?: "Эмулятор"
            val key = Base64.decode(o.getString("keyBase64"), Base64.DEFAULT)
            require(Names.isValidDeviceId(peerId) && key.size == 32) { "неверный peerDeviceId или ключ" }
            val address = InetSocketAddress(o.getString("host"), o.getInt("port"))
            debugPair = DebugPair(address, o.optInt("listenPort", 0))
            val current = PairStore.peerKey()
            if (current == null || current.deviceId != peerId || !current.key.contentEquals(key) || current.name != name) {
                PairStore.save(PairedPeer(peerId, name), key, address)
                notifyPairing(PairedPeer(peerId, name))
            }
            Log.i(TAG, "Отладочное сопряжение: $name по ${o.getString("host")}:${o.getInt("port")}")
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать $DEBUG_FILE", e)
        }
    }
}
