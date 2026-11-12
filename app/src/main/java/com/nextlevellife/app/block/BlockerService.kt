package com.nextlevellife.app.block

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.LedgerMath
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.ui.BlockActivity
import java.time.LocalTime
import java.util.UUID

/**
 * Следит за тем, какое приложение на переднем плане.
 * Заблокированное приложение открывается, только пока это разрешают правила ([BlockRules]):
 * не ночь, дневной лимит не исчерпан и есть баланс. Правила проверяются при открытии и на каждом тике,
 * поэтому ночь наступает и лимит кончается и внутри уже открытого приложения.
 * Время списывается по монотонным часам (SystemClock), поэтому перевод системных часов ничего не даёт;
 * пока экран выключен, время не списывается. Каждый тик пишется в журнал ([Ledger.spend]).
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

    private val tick = object : Runnable {
        override fun run() {
            settle()
            val pkg = currentPkg
            if (!spending || pkg == null) return
            val decision = decide()
            if (decision.reason != null) block(pkg, decision.reason) else handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.init(this)
        refreshIgnored()
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
            block(pkg, decision.reason)
            return
        }
        if (!wasBlocked) {
            Toast.makeText(this, "Осталось ${formatMinutes(decision.availableMs)}", Toast.LENGTH_SHORT).show()
        }
        startSpending(pkg)
    }

    /** Правила на текущий момент: локальное время, баланс, расход за локальные сутки, настройки. */
    private fun decide(): BlockDecision {
        val spentToday = try {
            Ledger.spentMs(LedgerMath.accountingDay(System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать расход за сегодня", e)
            0L
        }
        return BlockRules.evaluate(LocalTime.now(), Store.balanceMs(), spentToday, Settings.blockSettings())
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

    /** Списывает время, прошедшее с прошлой проверки, но не больше, чем сейчас разрешают правила. */
    private fun settle() {
        if (!spending) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTickAt
        lastTickAt = now
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (!screenOn || elapsed <= 0) return
        val sid = sessionId ?: return
        val pkg = sessionPkg ?: return
        try {
            val charge = minOf(elapsed, decide().availableMs)
            if (charge > 0) Ledger.spend(sid, pkg, charge)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось списать время", e)
        }
    }

    private fun block(pkg: String, reason: BlockReason) {
        stopSpending()
        // Экран блокировки открывается прямо поверх приложения. Нажимать «Домой» нельзя:
        // рабочий стол появляется после него и перекрывает экран блокировки.
        startActivity(
            Intent(this, BlockActivity::class.java)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                .putExtra(BlockActivity.EXTRA_REASON, reason.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        settle()
        stopSpending()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlockerService"
        private const val TICK_MS = 2_000L

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
