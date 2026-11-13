package com.nextlevellife.app.sync

import java.io.Closeable
import java.io.IOException

/**
 * Граница между транспортом (поиск второго телефона в Wi-Fi сети, сопряжение по QR, шифрованный канал) и
 * обменом данными (журналы, перенос минут). Транспорт ничего не знает о дневнике, обмен — о сокетах.
 *
 * Канал уже установлен и проверен: на другом конце — связанный телефон [peerDeviceId], сообщения зашифрованы
 * и приходят целиком и по порядку.
 */
interface SyncChannel : Closeable {
    /** device_id телефона на другом конце (тот, с которым выполнено сопряжение). */
    val peerDeviceId: String

    /** true — это мы открыли соединение; false — входящее. У двух сторон значения противоположны. */
    val initiator: Boolean

    /** Отправить одно сообщение (не больше [MAX_MESSAGE_BYTES]). Обрыв связи — [IOException]. */
    @Throws(IOException::class)
    fun send(message: ByteArray)

    /** Дождаться одного сообщения. Обрыв связи, закрытие или тайм-аут ожидания — [IOException]. */
    @Throws(IOException::class)
    fun receive(): ByteArray

    companion object {
        /** Предел одного сообщения: большие пакеты строк режутся на части. */
        const val MAX_MESSAGE_BYTES = 1 shl 20
    }
}

/** Связанный телефон: его device_id и имя для экрана («Galaxy Z Fold8»). */
data class PairedPeer(val deviceId: String, val name: String)

/**
 * Транспорт глазами обмена данными. Реализация — объект транспорта; обмен получает её через
 * `SyncEngine.attach(link)`. Все методы можно звать с любого потока; [open] блокирует.
 */
interface SyncLink {
    /** Связанный телефон; null — сопряжения нет. */
    fun peer(): PairedPeer?

    /**
     * Открыть канал ко второму телефону (кешированный адрес, затем поиск в сети). null — сопряжения нет
     * или телефон сейчас не найден. Блокирует не дольше [timeoutMs].
     */
    fun open(timeoutMs: Long = 5_000L): SyncChannel?

    /**
     * Обработчик входящих каналов (второй телефон подключился сам). Вызывается на фоновом потоке транспорта;
     * обработчик сам закрывает канал. Один обработчик, null — входящие отклоняются.
     */
    fun setIncomingHandler(handler: ((SyncChannel) -> Unit)?)

    /**
     * Слушатель изменений сопряжения (связали, разорвали): обмен сбрасывает своё состояние.
     * Вызывается на произвольном потоке.
     */
    fun setPairingListener(listener: ((PairedPeer?) -> Unit)?)
}
