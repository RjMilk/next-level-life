package com.nextlevellife.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.block.BlockReason
import com.nextlevellife.app.block.BlockRules
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.health.ActivitySync

/** Показывается поверх заблокированного приложения: баланс на нуле, дневной лимит исчерпан или ночной режим. */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        // Подтянуть шаги и пробежки: при включённом начислении баланс может пополниться (не чаще раза в 5 минут).
        ActivitySync.request(this)

        // «Назад» не возвращает в заблокированное приложение, а уводит на рабочий стол.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("Это приложение")
        val reason = intent.getStringExtra(EXTRA_REASON)
            ?.let { runCatching { BlockReason.valueOf(it) }.getOrNull() }
            ?: BlockReason.BALANCE
        val title = BlockText.title(reason)
        val message = BlockText.message(
            reason = reason,
            balanceMs = Store.balanceMs(),
            dailyLimitMin = Settings.dailyLimitMin,
            nightEndMin = Settings.nightEndMin,
            pushupsRewardMs = Exercises.rewardMs(Exercises.pushups, 10, Weights.DEFAULT),
        )
        // При лимите и ночью заработанные минуты всё равно не откроют приложение: главное — уйти на рабочий стол.
        val homeFirst = reason != BlockReason.BALANCE

        setContent {
            NextLevelTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // По центру экрана; на низком окне (альбомная ориентация) — прокрутка.
                    BoxWithConstraints(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                    ) {
                        val minHeight = maxHeight
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .heightIn(min = minHeight)
                                // На развёрнутом Fold колонка не шире 480 dp (+ отступы) по центру.
                                .wrapContentWidth(Alignment.CenterHorizontally)
                                .widthIn(max = 528.dp)
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(BlockText.emoji(reason), style = MaterialTheme.typography.displayLarge)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "$label закрыт",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(32.dp))
                            val primaryText = if (homeFirst) HOME_BUTTON else EARN_BUTTON
                            val secondaryText = if (homeFirst) EARN_BUTTON else HOME_BUTTON
                            val primary = if (homeFirst) ::goHome else ::openApp
                            val secondary = if (homeFirst) ::openApp else ::goHome
                            Button(
                                onClick = primary,
                                shape = ButtonShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            ) { Text(primaryText, style = MaterialTheme.typography.titleSmall) }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = secondary,
                                shape = ButtonShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) { Text(secondaryText) }
                        }
                    }
                }
            }
        }
    }

    /** Открыть Next Level Life, чтобы заработать минуты. */
    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    /** Домашний экран телефона. */
    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        /** Имя [BlockReason]; нет или не распознано — считается BALANCE. */
        const val EXTRA_REASON = "reason"

        private const val HOME_BUTTON = "На главный экран"
        private const val EARN_BUTTON = "Заработать минуты"
    }
}

/** Тексты экрана блокировки без Android: их проверяют юнит-тесты. */
internal object BlockText {

    fun title(reason: BlockReason): String = when (reason) {
        BlockReason.BALANCE -> "Баланс закончился"
        BlockReason.LIMIT -> "Дневной лимит исчерпан"
        BlockReason.NIGHT -> "Ночной режим"
    }

    fun emoji(reason: BlockReason): String = when (reason) {
        BlockReason.BALANCE -> "🏋️"
        BlockReason.LIMIT -> "⏳"
        BlockReason.NIGHT -> "🌙"
    }

    /** «минуты» после «из 1», «из 21»; иначе «минут»: «из 120 минут», «из 90 минут». */
    fun minutesGenitive(n: Int): String = pluralRu(n, "минуты", "минут", "минут")

    /** «Баланс 2 ч 15 мин сохранён.»; пустой баланс не упоминается. */
    fun balanceKept(balanceMs: Long): String =
        if (balanceMs > 0) " Баланс ${formatShort(balanceMs)} сохранён." else ""

    fun message(
        reason: BlockReason,
        balanceMs: Long,
        dailyLimitMin: Int,
        nightEndMin: Int,
        pushupsRewardMs: Long,
    ): String = when (reason) {
        BlockReason.BALANCE ->
            "Экранное время закончилось. Сделай упражнения, чтобы заработать минуты: " +
                "10 отжиманий = ${formatShort(pushupsRewardMs)}."
        // Лимит исчерпан — значит, потрачено ровно столько, сколько разрешено.
        BlockReason.LIMIT ->
            "Использовано $dailyLimitMin из $dailyLimitMin ${minutesGenitive(dailyLimitMin)}. " +
                "Доступ возобновится в 00:00." + balanceKept(balanceMs)
        BlockReason.NIGHT ->
            "Приложение доступно с ${BlockRules.formatClock(nightEndMin)}." + balanceKept(balanceMs)
    }
}
