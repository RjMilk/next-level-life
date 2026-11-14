package com.nextlevellife.app.sync.transport

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.sync.PairedPeer
import java.net.InetSocketAddress

/**
 * Сопряжение на этом телефоне: device_id и имя второго телефона, постоянный ключ пары (32 байта, Base64),
 * последний адрес, где второй телефон отвечал. Лежит в SharedPreferences [Store.prefs] с префиксом `sync_`;
 * резервная копия эти ключи не выгружает и при восстановлении не трогает. [Store.version] не меняется.
 *
 * Перед первым вызовом должен быть выполнен `Store.init` (его делает [SyncTransport.start]).
 */
internal object PairStore {
    private const val KEY_PEER_ID = "sync_peer_id"
    private const val KEY_PEER_NAME = "sync_peer_name"
    private const val KEY_PAIR_KEY = "sync_pair_key"
    private const val KEY_PEER_HOST = "sync_peer_host"
    private const val KEY_PEER_PORT = "sync_peer_port"
    private const val KEY_LISTEN_PORT = "sync_listen_port"

    private val prefs get() = Store.prefs()

    /** Свой device_id — тот же, что у журнала операций. */
    fun myDeviceId(): String = Ledger.deviceId()

    /** Имя этого телефона для второго: «Имя устройства» из настроек, иначе модель. */
    fun myName(context: Context): String {
        val custom = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return Names.cleanName(custom ?: "") ?: Names.cleanName(Build.MODEL ?: "") ?: "Телефон"
    }

    @Synchronized
    fun peer(): PairedPeer? {
        val id = prefs.getString(KEY_PEER_ID, null) ?: return null
        if (prefs.getString(KEY_PAIR_KEY, null) == null) return null
        return PairedPeer(id, prefs.getString(KEY_PEER_NAME, null) ?: "Второй телефон")
    }

    /** Пара вместе с ключом — только для транспорта. */
    @Synchronized
    fun peerKey(): PeerKey? {
        val id = prefs.getString(KEY_PEER_ID, null) ?: return null
        val encoded = prefs.getString(KEY_PAIR_KEY, null) ?: return null
        val key = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
        if (key == null || key.size != 32) return null
        return PeerKey(id, prefs.getString(KEY_PEER_NAME, null) ?: "Второй телефон", key)
    }

    @Synchronized
    fun save(peer: PairedPeer, key: ByteArray, address: InetSocketAddress?) {
        val e = prefs.edit()
            .putString(KEY_PEER_ID, peer.deviceId)
            .putString(KEY_PEER_NAME, peer.name)
            .putString(KEY_PAIR_KEY, Base64.encodeToString(key, Base64.NO_WRAP))
            .remove(KEY_PEER_HOST)
            .remove(KEY_PEER_PORT)
        address?.let { e.putString(KEY_PEER_HOST, it.literal()).putInt(KEY_PEER_PORT, it.port) }
        // commit, а не apply: пара и ключ должны пережить мгновенное убийство процесса.
        e.commit()
    }

    @Synchronized
    fun clear() {
        prefs.edit()
            .remove(KEY_PEER_ID)
            .remove(KEY_PEER_NAME)
            .remove(KEY_PAIR_KEY)
            .remove(KEY_PEER_HOST)
            .remove(KEY_PEER_PORT)
            .commit()
    }

    /** Последний адрес, где второй телефон отвечал; null — неизвестен. */
    @Synchronized
    fun cachedAddress(): InetSocketAddress? {
        val host = prefs.getString(KEY_PEER_HOST, null) ?: return null
        val port = prefs.getInt(KEY_PEER_PORT, 0)
        if (port !in 1..65535) return null
        // Адрес хранится числом (IPv4/IPv6), без DNS-запроса.
        return runCatching { InetSocketAddress(java.net.InetAddress.getByName(host), port) }.getOrNull()
    }

    @Synchronized
    fun setCachedAddress(address: InetSocketAddress) {
        if (prefs.getString(KEY_PEER_HOST, null) == address.literal() && prefs.getInt(KEY_PEER_PORT, 0) == address.port) return
        prefs.edit().putString(KEY_PEER_HOST, address.literal()).putInt(KEY_PEER_PORT, address.port).apply()
    }

    /** Порт, на котором слушали в прошлый раз: пробуем его снова, чтобы кеш адреса у второго телефона не устаревал. */
    fun lastListenPort(): Int = prefs.getInt(KEY_LISTEN_PORT, 0)

    fun setLastListenPort(port: Int) {
        if (lastListenPort() != port) prefs.edit().putInt(KEY_LISTEN_PORT, port).apply()
    }

    /** Адрес числом (без имени хоста), чтобы при чтении не было DNS-запроса. */
    private fun InetSocketAddress.literal(): String = address?.hostAddress ?: hostString
}
