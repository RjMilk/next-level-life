package com.nextlevellife.app.sync.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Поиск второго телефона через NSD (mDNS/DNS-SD) по типу [SERVICE_TYPE]. Имя сервиса — `NLL-<первые 8
 * символов device_id>`; при конфликте система может дописать « (2)», поэтому сравнивается начало. Имя
 * ничего не доказывает: кто на самом деле ответил, проверяет рукопожатие по ключу пары или токену.
 *
 * Блокирует вызывающий поток; с главного потока не вызывать (колбэки NSD могут прийти туда же).
 */
internal object NsdFinder {
    const val SERVICE_TYPE = "_nextlevellife._tcp"
    private const val TAG = "NsdFinder"

    fun serviceName(deviceId: String): String = "NLL-" + deviceId.take(8)

    /** Адреса (сначала IPv4) второго телефона с [peerDeviceId]; пусто — не найден за [timeoutMs]. */
    fun find(context: Context, peerDeviceId: String, timeoutMs: Long): List<InetSocketAddress> {
        if (timeoutMs <= 0) return emptyList()
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val wanted = serviceName(peerDeviceId)
        val done = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        var result: List<InetSocketAddress> = emptyList()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Не удалось разрешить ${serviceInfo.serviceName}: $errorCode")
                resolving.set(false)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val idAttr = serviceInfo.attributes?.get(ATTR_ID)?.toString(Charsets.UTF_8)
                if (idAttr != null && idAttr != peerDeviceId) {
                    // Совпало начало id, но это другой телефон.
                    resolving.set(false)
                    return
                }
                result = addresses(serviceInfo)
                if (result.isNotEmpty()) done.countDown() else resolving.set(false)
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Поиск не запустился: $errorCode")
                done.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.startsWith(wanted)) return
                // Разрешаем по одному: старые версии NSD не умеют несколько разрешений сразу.
                if (!resolving.compareAndSet(false, true)) return
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(serviceInfo, resolveListener)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "resolveService", e)
                    resolving.set(false)
                }
            }
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "discoverServices", e)
            return emptyList()
        }
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        }
        return result
    }

    private fun addresses(info: NsdServiceInfo): List<InetSocketAddress> {
        val port = info.port
        if (port !in 1..65535) return emptyList()
        val hosts: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }
        return hosts
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .map { InetSocketAddress(it, port) }
    }

    /** TXT-атрибут с полным device_id: отличает телефоны с одинаковым началом id. */
    const val ATTR_ID = "id"
}
