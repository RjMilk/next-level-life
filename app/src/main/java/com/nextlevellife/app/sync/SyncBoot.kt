package com.nextlevellife.app.sync

import android.content.Context
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.sync.transport.SyncTransport

/**
 * Запуск синхронизации в процессе: транспорт (сервер и поиск второго телефона) и обмен данными поверх него.
 * Зовётся из службы блокировки (живёт постоянно) и из главного экрана (служба может быть выключена).
 * Повторный вызов ничего не делает, кроме повторной попытки поднять сервер, если в прошлый раз не вышло.
 */
object SyncBoot {
    @Volatile private var attached = false

    fun ensure(context: Context) {
        val app = context.applicationContext
        Store.init(app)
        SyncTransport.start(app)
        if (!attached) {
            synchronized(this) {
                if (!attached) {
                    SyncEngine.attach(SyncTransport)
                    attached = true
                }
            }
        }
    }
}
