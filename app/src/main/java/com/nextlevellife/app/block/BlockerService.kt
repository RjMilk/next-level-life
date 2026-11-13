package com.nextlevellife.app.block

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TimeSnapshot
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.sleep.PhoneEventType
import com.nextlevellife.app.sleep.PhoneEvents
import com.nextlevellife.app.ui.BlockActivity
import java.util.UUID

/**
 * Следит за тем, какое приложение на переднем плане.
 * Заблокированное приложение открывается, только пока это разрешают правила ([BlockRules]):
 * не ночь, дневной лимит не исчерпан и есть баланс. Правила проверяются при открытии и на каждом тике,
 * поэтому ночь наступает и лимит кончается и внутри уже открытого приложения.
 *
 * Длительность списывается по монотонным часам (SystemClock), а «какие сейчас сутки» и «ночь ли сейчас» —
 * по доверенному времени и учётному поясу ([TrustedClock]): перевод системных часов и смена пояса телефона
 * ни лимит, ни ночь не снимают. Пока экран выключен, время не списывается. Каждый тик пишется в журнал
 * ([Ledger.spend]); переход к блокировке — в block_events ([Diary.addBlockEvent]).
 */
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentPkg: String? = null
    private var spending = false
    private var lastTickAt = 0L
    /** Сессия списания: одна на непрерывный просмотр одного заблокированного приложения. */
    private var sessionId: String? = null
    private var sessionPkg: String? = null
    private val ignored = mutableSetOf<String>()
    /**
     * Просмотренное время, которое журнал не принял (ошибка базы). Списывается вместе со следующим
     * тиком текущей сессии: строки расхода ведутся по одной сессии, старую возобновлять нельзя.
     */
    private var unpaidMs = 0L
    /** Сколько попыток списания подряд закончились ошибкой. */
    private var failedCharges = 0

    /** Перевод системных часов и смена пояса — только для лога: доверенное время от них не зависит. */
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_CHANGED -> TrustedClock.logSkew("перевод системных часов")
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    Log.i(TAG, "Пояс телефона сменился; учётный пояс прежний: ${TrustedClock.zone().id}")
                    // Экран настроек покажет кнопку «Сменить на текущий».
                    Store.notifyChanged()
                }
            }
        }
    }
    private var timeReceiverRegistered = false

    /**
     * Включение, выключение и разблокировка экрана — для ночного перерыва ([PhoneEvents]).
     * Использование — экран включён и разблокирован; экран, включённый уведомлением поверх экрана
     * блокировки, использованием не считается — поэтому при включении записывается, показан ли экран блокировки.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON ->
                    PhoneEvents.record(PhoneEventType.SCREEN_ON, PhoneEvents.SOURCE_RECEIVER, interactive = true, locked = keyguardLocked())
                Intent.ACTION_SCREEN_OFF ->
                    PhoneEvents.record(PhoneEventType.SCREEN_OFF, PhoneEvents.SOURCE_RECEIVER, interactive = false, locked = null)
                Intent.ACTION_USER_PRESENT ->
                    PhoneEvents.record(PhoneEventType.USER_PRESENT, PhoneEvents.SOURCE_RECEIVER, interactive = true, locked = false)
                // Выключение телефона — конец использования: после перезагрузки это не пропуск наблюдения.
                Intent.ACTION_SHUTDOWN ->
                    PhoneEvents.record(PhoneEventType.SCREEN_OFF, PhoneEvents.SOURCE_SHUTDOWN, interactive = false, locked = null)
            }
        }
    }
    private var screenReceiverRegistered = false

    private val tick = object : Runnable {
        override fun run() {
            settle()
            val pkg = currentPkg
            if (!spending || pkg == null) return
            // Журнал не принимает списания несколько тиков подряд — смотреть бесплатно нельзя.
            if (failedCharges >= MAX_FAILED_CHARGES) {
                block(pkg, BlockReason.BALANCE, TRIGGER_CHARGE_FAILED)
                return
            }
            val decision = decide()
            if (decision.reason != null) block(pkg, decision.reason, TRIGGER_TICK) else handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.init(this)
        refreshIgnored()
        registerTimeReceiver()
        registerScreenReceiver()
        // Состояние экрана на момент подключения — без выдумывания прошлого; пропуск, если служба не работала.
        PhoneEvents.onServiceConnected(this, interactive(), keyguardLocked())
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        try {
            // Только системные сообщения: чужим приложениям приёмник не открыт.
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось подписаться на события экрана", e)
        }
    }

    private fun interactive(): Boolean? = runCatching { getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull()

    private fun keyguardLocked(): Boolean? = runCatching { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked }.getOrNull()

    private fun registerTimeReceiver() {
        if (timeReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            // Системные широковещательные сообщения: отправить их чужое приложение не может.
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(timeReceiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(timeReceiver, filter)
            timeReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось подписаться на смену времени", e)
        }
    }

    private fun refreshIgnored() {
        ignored.clear()
        // Шторка уведомлений и клавиатуры не считаются сменой приложения.
        ignored += "com.android.systemui"
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.enabledInputMethodList?.forEach { ignored += it.packageName }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignored || pkg == currentPkg) return
        onForeground(pkg)
    }

    private fun onForeground(pkg: String) {
        settle()
        val wasBlocked = currentPkg?.let { Store.isBlocked(it) } == true
        currentPkg = pkg

        if (!Store.isBlocked(pkg)) {
            stopSpending()
            return
        }
        val decision = decide()
        if (decision.reason != null) {
            block(pkg, decision.reason, TRIGGER_OPEN)
            return
        }
        if (!wasBlocked) {
            Toast.makeText(this, "Осталось ${formatMinutes(decision.availableMs)}", Toast.LENGTH_SHORT).show()
        }
        startSpending(pkg)
        if (failedCharges >= MAX_FAILED_CHARGES) {
            // Журнал недавно не принимал списания: сразу пробуем списать долг в новой сессии; не вышло — блок.
            settle()
            if (failedCharges >= MAX_FAILED_CHARGES) block(pkg, BlockReason.BALANCE, TRIGGER_CHARGE_FAILED)
        }
    }

    /**
     * Правила на момент [time] (доверенное время и учётный пояс): ночь, баланс, расход за учётные сутки, настройки.
     */
    private fun decide(time: TimeSnapshot = TrustedClock.snapshot()): BlockDecision {
        val spentToday = try {
            Ledger.spentMs(time.day)
        } catch (e: Exception) {
            // Безопасная сторона: расход неизвестен — считаем дневной лимит исчерпанным (если он задан).
            Log.e(TAG, "Не удалось прочитать расход за сегодня", e)
            Long.MAX_VALUE
        }
        return BlockRules.evaluate(time, Store.balanceMs(), spentToday, Settings.blockSettings())
    }

    private fun startSpending(pkg: String) {
        // Переход из одного заблокированного приложения в другое — новая сессия: строки расхода не смешиваются.
        if (spending && sessionPkg == pkg) return
        handler.removeCallbacks(tick)
        spending = true
        sessionId = UUID.randomUUID().toString()
        sessionPkg = pkg
        lastTickAt = SystemClock.elapsedRealtime()
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stopSpending() {
        spending = false
        sessionId = null
        sessionPkg = null
        handler.removeCallbacks(tick)
    }

    /**
     * Списывает время, прошедшее с прошлой проверки, но не больше, чем сейчас разрешают правила,
     * плюс долг [unpaidMs] от прошлых неудачных списаний. При ошибке журнала время не теряется,
     * а копится в долг; счётчик [failedCharges] решает, когда блокировать.
     * Правила и журнал получают один и тот же снимок доверенного времени.
     */
    private fun settle() {
        if (!spending) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTickAt
        lastTickAt = now
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        val sid = sessionId ?: return
        val pkg = sessionPkg ?: return
        val time = TrustedClock.snapshot()
        val viewed = if (screenOn && elapsed > 0) minOf(elapsed, decide(time).availableMs).coerceAtLeast(0L) else 0L
        val charge = unpaidMs + viewed
        if (charge <= 0L) return
        try {
            Ledger.spend(sid, pkg, charge, time)
            unpaidMs = 0L
            failedCharges = 0
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось списать время (долг ${charge} мс, ошибок подряд ${failedCharges + 1})", e)
            unpaidMs = charge
            failedCharges++
        }
    }

    private fun block(pkg: String, reason: BlockReason, trigger: String) {
        stopSpending()
        recordBlockEvent(pkg, reason, trigger)
        // Экран блокировки открывается прямо поверх приложения. Нажимать «Домой» нельзя:
        // рабочий стол появляется после него и перекрывает экран блокировки.
        startActivity(
            Intent(this, BlockActivity::class.java)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                .putExtra(BlockActivity.EXTRA_REASON, reason.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    /** Одна строка на переход к блокировке (не на тик). Ошибка базы блокировку не отменяет. */
    private fun recordBlockEvent(pkg: String, reason: BlockReason, trigger: String) {
        try {
            val time = TrustedClock.snapshot()
            Diary.addBlockEvent(time.utcMs, time.day, pkg, reason.name, trigger)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось записать событие блокировки", e)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        settle()
        stopSpending()
        if (timeReceiverRegistered) {
            runCatching { unregisterReceiver(timeReceiver) }
            timeReceiverRegistered = false
        }
        PhoneEvents.onServiceDisconnected()
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlockerService"
        private const val TICK_MS = 2_000L
        /** После стольких неудачных списаний подряд заблокированное приложение закрывается. */
        private const val MAX_FAILED_CHARGES = 2

        /** Значения block_events.trigger. */
        const val TRIGGER_OPEN = "open"
        const val TRIGGER_TICK = "tick"
        const val TRIGGER_CHARGE_FAILED = "charge_failed"

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, BlockerService::class.java).flattenToString()
            val enabled = SystemSettings.Secure.getString(
                context.contentResolver,
                SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun formatMinutes(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> "$h ч $m мин"
                m > 0 -> "$m мин"
                else -> "$s сек"
            }
        }
    }
}
