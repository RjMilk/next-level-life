package com.nextlevellife.app.sync.transport

import java.io.Closeable
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Сторож дедлайнов: SO_TIMEOUT ограничивает одно чтение, а не операцию целиком, и не ограничивает запись.
 * Поэтому на рукопожатие и на каждую операцию канала ставится общий срок; по его истечении сокет
 * закрывается принудительно, и зависшее чтение или запись сразу получают исключение.
 */
internal object Watchdog {
    private val timer = ScheduledThreadPoolExecutor(1, ThreadFactory { r ->
        Thread(r, "nll-sync-watchdog").apply { isDaemon = true }
    }).apply { removeOnCancelPolicy = true }

    /** Взведённый сторож. [fired] — срок истёк и [target] закрыт. */
    class Guard internal constructor() {
        @Volatile var fired = false
            internal set
        internal var future: ScheduledFuture<*>? = null

        /** Операция уложилась: снять сторожа. */
        fun disarm() {
            future?.cancel(false)
        }
    }

    /** Закрыть [target] через [timeoutMs], если сторожа не снимут раньше. */
    fun arm(timeoutMs: Long, target: Closeable): Guard {
        val guard = Guard()
        guard.future = timer.schedule({
            guard.fired = true
            runCatching { target.close() }
        }, timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        return guard
    }
}
