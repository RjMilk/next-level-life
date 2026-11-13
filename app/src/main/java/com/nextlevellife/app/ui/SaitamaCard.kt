package com.nextlevellife.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.SaitamaMath
import com.nextlevellife.app.data.SaitamaRepository
import com.nextlevellife.app.data.SaitamaState
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.health.ActivitySync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Последнее состояние карточки: при возврате на главную видно сразу. */
@Volatile private var cachedCard: SaitamaState? = null

/**
 * Карточка «Сайтамы» для главной — в стиле карточек комплексов (тёмно-янтарный фон, заголовок акцентом):
 * что делать каждый день, прогресс сегодня («Сегодня 63 · 0 · 100 · 4,2 км») и серия.
 * Данные читает сама в фоне и перечитывает при изменении данных ([Store.version]) и смене учётных суток.
 * Бонус не доплачивает — это делает [SaitamaRepository.ensureBonuses] (корень [MainActivity] и экран «Сайтамы»).
 */
@Composable
fun SaitamaCard(onClick: () -> Unit) {
    val version by Store.version.collectAsState()
    val health by ActivitySync.state.collectAsState()
    val today = TrustedClock.today()
    val access = health.access
    val state by produceState(cachedCard?.takeIf { it.today == today }, version, today, access) {
        value = withContext(Dispatchers.IO) { runCatching { SaitamaRepository.load(access) }.getOrNull() }
            ?.also { cachedCard = it } ?: value
    }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SaitamaCardColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Сайтама", style = MaterialTheme.typography.titleLarge, color = Accent)
            Text("Каждый день 100 · 100 · 100 + 10 км", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            val s = state
            if (s != null) {
                Text(
                    SaitamaMath.cardLine(s.day) + if (s.day.full) " ✓" else "",
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = if (s.day.full) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    SaitamaMath.streakText(s.streak) + " · рекорд ${s.record}",
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
