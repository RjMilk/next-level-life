package com.nextlevellife.app.block

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.ui.BlockActivity

/**
 * Следит за тем, какое приложение на переднем плане.
 * Заблокированное приложение открывается, только пока есть баланс; время списывается
 * по монотонным часам (SystemClock), поэтому перевод системных часов ничего не даёт.
 * Пока экран выключен, время не списывается.
 */
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentPkg: String? = null
    private var spending = false
    private var lastTickAt = 0L
    private val ignored = mutableSetOf<String>()

    private val tick = object : Runnable {
        override fun run() {
            settle()
            val pkg = currentPkg
            if (spending && pkg != null && Store.balanceMs() <= 0L) {
                block(pkg)
            } else if (spending) {
                handler.postDelayed(this, TICK_MS)
            }
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
        val balance = Store.balanceMs()
        if (balance <= 0L) {
            block(pkg)
            return
        }
        if (!wasBlocked) {
            Toast.makeText(this, "Осталось ${formatMinutes(balance)}", Toast.LENGTH_SHORT).show()
        }
        startSpending()
    }

    private fun startSpending() {
        if (spending) return
        spending = true
        lastTickAt = SystemClock.elapsedRealtime()
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stopSpending() {
        spending = false
        handler.removeCallbacks(tick)
    }

    /** Списывает время, прошедшее с прошлой проверки. */
    private fun settle() {
        if (!spending) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTickAt
        lastTickAt = now
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (screenOn && elapsed > 0) Store.spend(elapsed)
    }

    private fun block(pkg: String) {
        stopSpending()
        // Экран блокировки открывается прямо поверх приложения. Нажимать «Домой» нельзя:
        // рабочий стол появляется после него и перекрывает экран блокировки.
        startActivity(
            Intent(this, BlockActivity::class.java)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopSpending()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 2_000L

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, BlockerService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
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
