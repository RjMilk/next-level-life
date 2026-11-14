package com.nextlevellife.app.block

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.nextlevellife.app.data.Diary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Связь обмена со службой блокировки (объект процесса): перед расчётом переноса текущий просмотр списывается
 * ([settle]), а неоплаченный расход вычитается из того, что можно отдать. Служба регистрируется при подключении
 * ([register]) и снимается при остановке.
 */
object SpendBridge {
    private const val TAG = "SpendBridge"

    /** Сколько после блокировки по балансу или лимиту телефон считается «тратящим» (ему отдают минуты). */
    private const val WANT_MS = 3 * 60_000L

    /** Ожидание главного потока при погашении. */
    private const val SETTLE_TIMEOUT_MS = 2_000L

    /** Что служба сообщает обмену. Вызывается на главном потоке. */
    interface Source {
        /** Списать просмотр до этого момента; вернуть неоплаченный расход (мс). */
        fun settleNow(): Long

        /** Сейчас открыто заблокированное приложение и идёт расход. */
        fun spendingNow(): Boolean
    }

    /** Итог погашения: тратит ли телефон сейчас и сколько расхода ещё не оплачено. */
    data class Settled(val spending: Boolean, val unpaidMs: Long)

    @Volatile private var source: Source? = null
    @Volatile private var wantUntilElapsed = 0L
    private val main = Handler(Looper.getMainLooper())

    fun register(s: Source?) {
        source = s
    }

    /** Заблокировано по балансу или лимиту: ближайшие минуты телефон «тратит» — второй отдаст ему ресурс. */
    fun markWantsToSpend() {
        wantUntilElapsed = SystemClock.elapsedRealtime() + WANT_MS
    }

    private fun wants(): Boolean = SystemClock.elapsedRealtime() < wantUntilElapsed

    /**
     * Погасить текущий просмотр и узнать неоплаченный расход. С любого потока: работа службы идёт на главном,
     * ожидание не дольше [SETTLE_TIMEOUT_MS]. Не дождались — null (обмен тогда ничего не отдаёт).
     * Службы нет — расхода нет; неоплаченное — сохранённые долги в meta.
     */
    fun settle(): Settled? {
        val s = source ?: return Settled(wants(), storedDebtMs())
        if (Looper.myLooper() == Looper.getMainLooper()) return Settled(s.spendingNow() || wants(), s.settleNow())
        var result: Settled? = null
        val done = CountDownLatch(1)
        main.post {
            try {
                result = Settled(s.spendingNow() || wants(), s.settleNow())
            } catch (e: Exception) {
                Log.e(TAG, "Служба не погасила расход", e)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(SETTLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Служба не ответила: обмен ничего не отдаст")
            return null
        }
        return result
    }

    private fun storedDebtMs(): Long = try {
        Diary.metaGet(BlockerService.KEY_USAGE_DEBTS)?.let { UsageDebtCodec.decode(it) }?.sumOf { it.durationMs } ?: 0L
    } catch (e: Exception) {
        Log.e(TAG, "Не удалось прочитать долги", e)
        Long.MAX_VALUE / 4
    }
}
