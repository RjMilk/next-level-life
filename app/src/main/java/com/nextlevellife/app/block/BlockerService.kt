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
import com.nextlevellife.app.data.LedgerMath
import com.nextlevellife.app.data.SharedWalletMath
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TimeSnapshot
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.data.UsageDebt
import com.nextlevellife.app.sleep.PhoneEventType
import com.nextlevellife.app.sleep.PhoneEvents
import com.nextlevellife.app.sync.SyncEngine
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
    /**
     * Отсчёт просмотра и состояние экрана. Состояние хранится здесь и меняется приёмником экрана
     * (интервал до смены закрывается по прежнему состоянию), а не читается на тике.
     */
    private val meter = SpendMeter()
    /** Сессия списания: одна на непрерывный просмотр одного заблокированного приложения. */
    private var sessionId: String? = null
    private var sessionPkg: String? = null
    private val ignored = mutableSetOf<String>()
    /**
     * Просмотры, которые журнал не принял (ошибка базы): интервалы доверенного времени с приложением
     * и сессией. Списываются со следующим тиком строками текущей сессии, но каждый — по своим суткам
     * ([Ledger.spend] с долгами). Каждый новый долг сразу сохраняется в meta ([KEY_USAGE_DEBTS]), чтобы
     * не пропасть с процессом; при подключении службы сохранённые долги читаются и гасятся первыми.
     */
    private val unpaid = ArrayList<UsageDebt>()
    /** Сколько попыток списания подряд закончились ошибкой. */
    private var failedCharges = 0
    /** Долги прошлого запуска прочитаны из meta (до этого meta перезаписывать нельзя — они бы пропали). */
    private var debtsLoaded = false
    /** В meta, возможно, лежат долги: при погашении ключ удаляется той же транзакцией, что и списание. */
    private var debtsInMeta = false
    /** В [unpaid] есть долги, ещё не сохранённые в meta. */
    private var debtsDirty = false

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
     * Включение, выключение и разблокировка экрана — для ночного перерыва ([PhoneEvents]) и списания.
     * Использование — экран включён и разблокирован; экран, включённый уведомлением поверх экрана
     * блокировки, использованием не считается — поэтому при включении записывается, показан ли экран блокировки.
     * Для списания каждая смена сначала закрывает текущий интервал по прежнему состоянию экрана ([onScreen]).
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    onScreen(true)
                    // Экран включили — второй телефон, возможно, рядом: обмен (не чаще, чем разрешает SyncEngine).
                    SyncEngine.requestSync("экран")
                    PhoneEvents.record(PhoneEventType.SCREEN_ON, PhoneEvents.SOURCE_RECEIVER, interactive = true, locked = keyguardLocked())
                }
                Intent.ACTION_SCREEN_OFF -> {
                    onScreen(false)
                    PhoneEvents.record(PhoneEventType.SCREEN_OFF, PhoneEvents.SOURCE_RECEIVER, interactive = false, locked = null)
                }
                Intent.ACTION_USER_PRESENT -> {
                    onScreen(true)
                    PhoneEvents.record(PhoneEventType.USER_PRESENT, PhoneEvents.SOURCE_RECEIVER, interactive = true, locked = false)
                }
                // Выключение телефона — конец использования: после перезагрузки это не пропуск наблюдения.
                Intent.ACTION_SHUTDOWN -> {
                    onScreen(false)
                    PhoneEvents.record(PhoneEventType.SCREEN_OFF, PhoneEvents.SOURCE_SHUTDOWN, interactive = false, locked = null)
                }
            }
        }
    }

    /** Экран сменил состояние: интервал до этого момента списывается по прежнему состоянию, потом — новое. */
    private fun onScreen(on: Boolean) {
        settle()
        meter.setScreen(on)
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
            maybeSyncWhileSpending(decision)
            if (decision.reason != null) block(pkg, decision.reason, TRIGGER_TICK) else handler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Тик наблюдения для ночного перерыва: раз в 5 минут — пульс (служба жива, наблюдение подтверждено),
     * раз в час — финализация ночей и после неё чистка сырых данных (на потоке записи [PhoneEvents]).
     * Тик идёт по Handler: в глубоком сне телефона он реже, пульс тогда просто записывается позже.
     */
    private var lastMaintenanceAt = 0L
    private val observeTick = object : Runnable {
        override fun run() {
            PhoneEvents.heartbeat(this@BlockerService, interactive(), keyguardLocked())
            val now = SystemClock.elapsedRealtime()
            if (lastMaintenanceAt == 0L || now - lastMaintenanceAt >= MAINTENANCE_MS) {
                lastMaintenanceAt = now
                PhoneEvents.maintain()
            }
            handler.postDelayed(this, PhoneEvents.HEARTBEAT_MS)
        }
    }

    /** Для обмена: погасить текущий просмотр и сообщить неоплаченное (главный поток). */
    private val bridge = object : SpendBridge.Source {
        override fun settleNow(): Long {
            settle()
            return unpaid.sumOf { it.durationMs }
        }

        override fun spendingNow(): Boolean = spending
    }

    /** Когда последний раз просили обмен во время расхода (elapsedRealtime). */
    private var lastSpendSyncAt = 0L

    /**
     * Пока идёт расход — обмен раз в минуту; сразу, когда свой кошелёк или своя доля лимита почти кончились,
     * а у второго телефона по копии есть ресурс.
     */
    private fun maybeSyncWhileSpending(decision: BlockDecision) {
        val now = SystemClock.elapsedRealtime()
        val low = decision.reason != null || decision.availableMs <= LOW_MS
        if (low && peerHasResources(decision.reason ?: BlockReason.BALANCE)) {
            SyncEngine.requestSync("ресурс кончается")
            lastSpendSyncAt = now
        } else if (now - lastSpendSyncAt >= SPEND_SYNC_MS) {
            SyncEngine.requestSync("расход")
            lastSpendSyncAt = now
        }
    }

    /** У второго телефона по копии есть минуты (BALANCE) или доля лимита (LIMIT). */
    private fun peerHasResources(reason: BlockReason): Boolean = try {
        when (reason) {
            BlockReason.BALANCE -> Ledger.peerId() != null && Ledger.peerBalanceMs() > 0L
            BlockReason.LIMIT -> (Ledger.peerShareLeftMs(TrustedClock.today()) ?: 0L) > 0L
            BlockReason.NIGHT -> false
        }
    } catch (e: Exception) {
        false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.init(this)
        // Синхронизация со вторым телефоном: служба живёт постоянно — сервер должен отвечать и при закрытом приложении.
        com.nextlevellife.app.sync.SyncBoot.ensure(this)
        SpendBridge.register(bridge)
        refreshIgnored()
        registerTimeReceiver()
        meter.setScreen(interactive() != false)
        registerScreenReceiver()
        // Долги прошлого запуска — первым делом: не погашены — заблокированные приложения не открываются.
        loadStoredDebts()
        // Состояние экрана на момент подключения — без выдумывания прошлого; пропуск, если служба не работала.
        PhoneEvents.onServiceConnected(this, interactive(), keyguardLocked())
        handler.removeCallbacks(observeTick)
        handler.postDelayed(observeTick, PhoneEvents.HEARTBEAT_MS)
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
        // Долги прошлого запуска не прочитались при подключении — пробуем снова (до этого failedCharges держит блок).
        if (!debtsLoaded) loadStoredDebts()
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
        val settings = Settings.blockSettings()
        val spentToday = try {
            // Связанный телефон тратит свою долю общего лимита: правилам показывается «лимит − своя доля».
            if (Ledger.peerId() == null) Ledger.ownSpentMs(time.day)
            else SharedWalletMath.effectiveSpentMs(
                settings.dailyLimitMin * 60_000L, Ledger.shareLeftMs(time.day, settings.dailyLimitMin),
            )
        } catch (e: Exception) {
            // Безопасная сторона: расход неизвестен — считаем дневной лимит исчерпанным (если он задан).
            Log.e(TAG, "Не удалось прочитать расход за сегодня", e)
            Long.MAX_VALUE
        }
        return BlockRules.evaluate(time, Store.balanceMs(), spentToday, settings)
    }

    private fun startSpending(pkg: String) {
        // Переход из одного заблокированного приложения в другое — новая сессия: строки расхода не смешиваются.
        if (spending && sessionPkg == pkg) return
        handler.removeCallbacks(tick)
        spending = true
        sessionId = UUID.randomUUID().toString()
        sessionPkg = pkg
        meter.restart(SystemClock.elapsedRealtime())
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
     * плюс долги [unpaid] от прошлых неудачных списаний. Интервал считается по состоянию экрана,
     * хранящемуся в [meter] (его меняет приёмник экрана, закрыв интервал по прежнему состоянию).
     * При ошибке журнала время не теряется, а копится в долг (интервалом) и сразу сохраняется в meta;
     * счётчик [failedCharges] решает, когда блокировать. Сохранить долг тоже не вышло — блок сразу.
     * Правила и журнал получают один и тот же снимок доверенного времени.
     */
    private fun settle() {
        if (!spending) return
        // Без приёмника экрана состояние узнать не от кого — тогда, как раньше, на момент проверки.
        if (!screenReceiverRegistered) meter.setScreen(interactive() != false)
        val elapsed = meter.take(SystemClock.elapsedRealtime())
        val sid = sessionId ?: return
        val pkg = sessionPkg ?: return
        val time = TrustedClock.snapshot()
        val viewed = if (elapsed > 0) minOf(elapsed, decide(time).availableMs).coerceAtLeast(0L) else 0L
        if (viewed <= 0L && unpaid.isEmpty()) return
        if (charge(sid, pkg, viewed, time)) return
        if (viewed > 0L) {
            unpaid += UsageDebt(time.utcMs - viewed, time.utcMs, pkg, sid)
            debtsDirty = true
            if (!persistDebts()) {
                // Долг не сохранить даже в meta: смотреть дальше нельзя — следующая проверка блокирует.
                Log.e(TAG, "Долг ${unpaid.sumOf { it.durationMs }} мс не сохранён: блок")
                failedCharges = maxOf(failedCharges + 1, MAX_FAILED_CHARGES)
                return
            }
        }
        failedCharges++
        Log.e(TAG, "Не удалось списать время (долг ${unpaid.sumOf { it.durationMs }} мс, ошибок подряд $failedCharges)")
    }

    /**
     * Одно списание: [viewed] текущего просмотра и все долги [unpaid]. Если долги лежат в meta, ключ
     * удаляется той же транзакцией, что и списание: долг не спишется дважды и не пропадёт. true — списано.
     */
    private fun charge(sid: String, pkg: String, viewed: Long, time: TimeSnapshot): Boolean {
        val debts = unpaid.toList()
        return try {
            // Не хватило баланса — остаток долгов и просмотра не пропадает (раньше весь unpaid очищался), а остаётся
            // долгом: в meta той же транзакцией (если долги прошлого запуска уже прочитаны) и в памяти.
            var leftover: List<UsageDebt> = emptyList()
            Diary.write {
                val charged = Ledger.spend(sid, pkg, viewed, time, debts)
                leftover = LedgerMath.leftoverDebts(debts, pkg, sid, time.utcMs, viewed, charged)
                when {
                    leftover.isNotEmpty() && debtsLoaded -> Diary.metaPut(KEY_USAGE_DEBTS, UsageDebtCodec.encode(leftover))
                    leftover.isEmpty() && debtsInMeta -> Diary.metaDelete(KEY_USAGE_DEBTS)
                }
            }
            unpaid.clear()
            unpaid.addAll(leftover)
            debtsInMeta = leftover.isNotEmpty() && debtsLoaded
            debtsDirty = leftover.isNotEmpty() && !debtsLoaded
            if (leftover.isNotEmpty()) Log.w(TAG, "Баланса не хватило: долг ${leftover.sumOf { it.durationMs }} мс остаётся")
            failedCharges = 0
            true
        } catch (e: Exception) {
            Log.e(TAG, "Журнал не принял списание", e)
            false
        }
    }

    /**
     * Сохраняет [unpaid] в meta ([KEY_USAGE_DEBTS]). Пока долги прошлого запуска не прочитаны, писать нельзя
     * (их бы затёрли) — сначала читаются они. false — сохранить не удалось.
     */
    private fun persistDebts(): Boolean {
        if (!debtsLoaded) loadStoredDebts()
        if (!debtsLoaded) return false
        return try {
            if (unpaid.isEmpty()) Diary.metaDelete(KEY_USAGE_DEBTS) else Diary.metaPut(KEY_USAGE_DEBTS, UsageDebtCodec.encode(unpaid))
            debtsInMeta = unpaid.isNotEmpty()
            debtsDirty = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось сохранить долг в meta", e)
            false
        }
    }

    /**
     * Читает долги прошлого запуска из meta и сразу пытается их погасить. Не погашены (или не прочитаны) —
     * [failedCharges] не меньше [MAX_FAILED_CHARGES]: заблокированное приложение откроется, только если
     * при открытии списание пройдёт ([onForeground]).
     */
    private fun loadStoredDebts() {
        if (debtsLoaded) return
        val raw = try {
            Diary.metaGet(KEY_USAGE_DEBTS)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать сохранённые долги: блок до повторного чтения", e)
            failedCharges = maxOf(failedCharges, MAX_FAILED_CHARGES)
            return
        }
        debtsLoaded = true
        val stored = raw?.let { UsageDebtCodec.decode(it) }
        if (raw != null && stored == null) {
            // Неизвестно, сколько и когда смотрели: списать нечего, вечный блок был бы хуже.
            Log.e(TAG, "Сохранённые долги повреждены и отброшены (${raw.length} символов)")
        }
        // Ключ (повреждённый или пустой) удалится вместе со следующим удачным списанием или сохранением.
        if (raw != null) debtsInMeta = true
        if (stored.isNullOrEmpty()) {
            // Долгов прошлого запуска нет: блок, поставленный из-за неудачного чтения, больше не нужен
            // (неудачные списания этого запуска всегда оставляют долг в памяти).
            if (unpaid.isEmpty()) failedCharges = 0
            return
        }
        // Старые долги — первыми; если в памяти уже были новые, их ещё надо сохранить.
        debtsDirty = unpaid.isNotEmpty()
        unpaid.addAll(0, stored)
        debtsInMeta = true
        if (!charge(UUID.randomUUID().toString(), stored.last().packageName, 0L, TrustedClock.snapshot())) {
            Log.e(TAG, "Долг прошлого запуска (${stored.sumOf { it.durationMs }} мс) не погашен: блок")
            failedCharges = maxOf(failedCharges, MAX_FAILED_CHARGES)
            if (debtsDirty) persistDebts()
        } else {
            Log.i(TAG, "Погашен долг прошлого запуска: ${stored.sumOf { it.durationMs }} мс")
        }
    }

    private fun block(pkg: String, reason: BlockReason, trigger: String) {
        stopSpending()
        if (reason != BlockReason.NIGHT) {
            // Упёрлись в свой кошелёк или долю: ближайшие минуты этот телефон «тратит» — второй отдаст ему ресурс.
            SpendBridge.markWantsToSpend()
            if (peerHasResources(reason)) SyncEngine.requestSync("блокировка", force = true)
        }
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

    /** Прерывание отклика: служба продолжает работать, момент только отмечается (SERVICE_STOP, source = interrupt). */
    override fun onInterrupt() {
        if (debtsDirty) persistDebts()
        PhoneEvents.onServiceInterrupted()
    }

    override fun onDestroy() {
        settle()
        // Долг, который не удалось сохранить сразу, — последняя попытка: иначе он пропадёт с процессом.
        if (debtsDirty) persistDebts()
        stopSpending()
        SpendBridge.register(null)
        handler.removeCallbacks(observeTick)
        if (timeReceiverRegistered) {
            runCatching { unregisterReceiver(timeReceiver) }
            timeReceiverRegistered = false
        }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        // После отписки от экрана: момент остановки — последнее подтверждённое наблюдение.
        PhoneEvents.onServiceStopped()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlockerService"
        private const val TICK_MS = 2_000L
        /** После стольких неудачных списаний подряд заблокированное приложение закрывается. */
        private const val MAX_FAILED_CHARGES = 2
        /** Финализация ночей и чистка сырых данных — раз в час. */
        private const val MAINTENANCE_MS = 60 * 60_000L
        /** Обмен со вторым телефоном во время расхода — раз в минуту. */
        private const val SPEND_SYNC_MS = 60_000L
        /** Осталось меньше — «ресурс кончается»: обмен сразу, если у второго телефона есть ресурс. */
        private const val LOW_MS = 60_000L
        /** Ключ meta: неоплаченные просмотры ([UsageDebt]) JSON-массивом ([UsageDebtCodec]). */
        internal const val KEY_USAGE_DEBTS = "usage_debts"

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
