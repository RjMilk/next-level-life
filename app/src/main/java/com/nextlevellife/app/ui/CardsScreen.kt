package com.nextlevellife.app.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextlevellife.app.data.Card
import com.nextlevellife.app.data.CardDeck
import com.nextlevellife.app.data.CardDecks
import com.nextlevellife.app.data.CardReward
import com.nextlevellife.app.data.CardRewardReason
import com.nextlevellife.app.data.CardSession
import com.nextlevellife.app.data.CardState
import com.nextlevellife.app.data.Cards
import com.nextlevellife.app.data.CardsMath
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TrustedClock
import com.nextlevellife.app.stats.StatsMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "CardsScreen"

private const val PHASE_QUESTION = "question"
private const val PHASE_RESULT = "result"

/** Сводка для выбора колоды и карточки на главной: расписание, к повторению и новые по колодам, заработок дня. */
private data class CardsOverview(
    val states: Map<String, CardState>,
    val counts: Map<CardDeck, Pair<Int, Int>>,
    val earnedMs: Long,
    val capMs: Long,
) {
    val dueTotal: Int get() = counts.values.sumOf { it.first }
}

private fun loadOverview(today: Long): CardsOverview {
    val states = CardsMath.schedule(Cards.allAttempts())
    val counts = CardDeck.entries.associateWith { deck ->
        val cards = CardDecks.cards(deck)
        CardsMath.due(cards, states, today).size to CardsMath.fresh(cards, states).size
    }
    val (earned, cap) = Cards.earnedToday(today)
    return CardsOverview(states, counts, earned, cap)
}

/**
 * «Карточки»: выбор колоды (к повторению сегодня и новые), сессия до 20 карточек — сначала к повторению, затем новые.
 * Ответ вводится с клавиатуры одной отправкой; после ошибки — правильный ответ и повтор карточки позже в этой же
 * сессии (без минут). 10 с за верный первый ответ на карточку, ещё не оплаченную сегодня, до потолка дня.
 * Состояние сессии переживает поворот и раскрытие Fold; запись ответа идемпотентна по uid попытки.
 */
@Composable
fun CardsScreen(onBack: () -> Unit) {
    val version by Store.version.collectAsState()
    val today = TrustedClock.today()
    var deckId by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var phase by rememberSaveable { mutableStateOf(PHASE_QUESTION) }
    var input by rememberSaveable { mutableStateOf("") }
    var attemptUid by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    // Показанный итог ответа: карточка, верно ли, что ввели, текст награды (null — ответ ещё записывается).
    var shownId by rememberSaveable { mutableStateOf<String?>(null) }
    var shownCorrect by rememberSaveable { mutableStateOf(false) }
    var shownGiven by rememberSaveable { mutableStateOf("") }
    var rewardText by rememberSaveable { mutableStateOf<String?>(null) }
    // Первый ответ, который надо записать (переживает пересоздание экрана: запись повторится с тем же uid).
    var pendingCard by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCorrect by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val overview by produceState<CardsOverview?>(null, version, today) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadOverview(today) }.onFailure { Log.e(TAG, "Не удалось прочитать карточки", it) }.getOrNull()
        }
    }
    val session = CardSession.restore(saved)
    val deck = CardDeck.byId(deckId)

    // Запись первого ответа и начисление: одной транзакцией в Cards, повтор с тем же uid ничего не меняет.
    LaunchedEffect(pendingCard, attemptUid) {
        val id = pendingCard ?: return@LaunchedEffect
        val card = CardDecks.byId(id) ?: run { pendingCard = null; return@LaunchedEffect }
        val uid = attemptUid
        val correct = pendingCorrect
        val r = withContext(Dispatchers.IO) {
            runCatching { Cards.recordAttempt(uid, card, correct) }
                .onFailure { Log.e(TAG, "Не удалось записать ответ", it) }.getOrNull()
        }
        rewardText = r?.let(CardsMath::rewardText) ?: "Не удалось записать ответ"
        if (r != null && r.paidMs > 0L) CardSession.restore(saved)?.let { saved = it.withPaid(r.paidMs).save() }
        pendingCard = null
    }

    fun toDecks() {
        saved = null
        deckId = null
        phase = PHASE_QUESTION
        input = ""
        pendingCard = null
    }

    fun start(d: CardDeck) {
        val o = overview ?: return
        val ids = CardsMath.pickSession(CardDecks.cards(d), o.states, today)
        if (ids.isEmpty()) {
            message = "В колоде «${d.title}» на сегодня всё повторено"
            return
        }
        message = null
        deckId = d.id
        saved = CardSession.start(ids).save()
        phase = PHASE_QUESTION
        input = ""
        attemptUid = UUID.randomUUID().toString()
    }

    fun check() {
        val s = session ?: return
        val id = s.current ?: return
        val card = CardDecks.byId(id) ?: return
        if (input.isBlank() || pendingCard != null) return
        val correct = CardsMath.isCorrect(card, input)
        shownId = id
        shownCorrect = correct
        shownGiven = input.trim()
        if (s.isFirstShow) {
            rewardText = null
            pendingCorrect = correct
            pendingCard = id
        } else {
            rewardText = CardsMath.rewardText(CardReward(0L, CardRewardReason.PRACTICE))
        }
        saved = s.answered(correct).save()
        phase = PHASE_RESULT
    }

    fun next() {
        if (pendingCard != null) return
        phase = PHASE_QUESTION
        input = ""
        rewardText = null
        attemptUid = UUID.randomUUID().toString()
    }

    BackHandler { if (session != null) toDecks() else onBack() }

    val widthClass = currentWidthClass()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
            .padding(widthClass.outerPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val earnedLine = overview?.let { CardsMath.earnedLine(it.earnedMs, it.capMs) } ?: " "
        when {
            session == null || deck == null -> DeckList(overview, earnedLine, message, onBack, ::start)
            session.finished && phase == PHASE_QUESTION -> Summary(
                deck, session, earnedLine,
                canContinue = (overview?.counts?.get(deck)?.let { it.first + it.second } ?: 0) > 0,
                onMore = { start(deck) },
                onDecks = ::toDecks,
            )
            else -> {
                Header(deck.title, "Закончить", ::toDecks)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.progressText,
                        style = MaterialTheme.typography.titleMedium.merge(Tabular),
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(
                        earnedLine,
                        style = MaterialTheme.typography.bodySmall.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                LinearProgressIndicator(
                    progress = { session.done.toFloat() / session.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                val cardId = if (phase == PHASE_RESULT) shownId else session.current
                val card = cardId?.let(CardDecks::byId)
                if (card == null) {
                    LaunchedEffect(Unit) { toDecks() }
                    return@Column
                }
                PromptCard(card, retry = phase == PHASE_QUESTION && !session.isFirstShow)
                if (phase == PHASE_QUESTION) {
                    AnswerInput(card, input, onInput = { input = it }, onSubmit = ::check)
                } else {
                    ResultCard(card, shownCorrect, shownGiven, rewardText)
                    Button(
                        onClick = ::next,
                        enabled = pendingCard == null,
                        shape = ButtonShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(if (session.finished) "Итог сессии" else "Дальше") }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun DeckList(
    overview: CardsOverview?,
    earnedLine: String,
    message: String?,
    onBack: () -> Unit,
    onStart: (CardDeck) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Header("Карточки", "Назад", onBack)
    Text(earnedLine, style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = muted)
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    CardDeck.entries.forEach { d ->
        val counts = overview?.counts?.get(d)
        val empty = counts != null && counts.first + counts.second == 0
        Card(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = overview != null && !empty, onClickLabel = "Начать: ${d.title}") { onStart(d) },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.title, style = MaterialTheme.typography.titleMedium)
                    Text(d.subtitle, style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(
                        when {
                            counts == null -> " "
                            empty -> "На сегодня всё повторено"
                            else -> CardsMath.deckLine(counts.first, counts.second)
                        },
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                        color = if (counts != null && counts.first > 0) Accent else muted,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = muted)
            }
        }
    }
    Text(
        "${CardsMath.REWARD_MS / 1000} с за верный первый ответ на карточку, которую сегодня ещё не оплачивали " +
            "(ни на этом, ни на втором телефоне). До ${CardsMath.durationText(CardsMath.DAY_CAP_MS)} в день на оба " +
            "телефона: в паре каждый оплачивает до ${CardsMath.durationText(CardsMath.phoneCapMs(true))}. " +
            "Сессия — до ${CardsMath.SESSION_SIZE} карточек: сначала к повторению, затем новые. Повторы через " +
            "${CardsMath.INTERVALS.joinToString(", ")} дней, ошибка — снова через день. " +
            "Знание: очко за каждые ${StatsMath.CARDS_PER_POINT} разные карточки с верным первым ответом.",
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
}

@Composable
private fun PromptCard(card: Card, retry: Boolean) {
    Card(Modifier.fillMaxWidth(), shape = CardShape) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (retry) "${card.task} · повтор" else card.task,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                card.prompt,
                style = MaterialTheme.typography.displaySmall.merge(Tabular),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AnswerInput(card: Card, value: String, onInput: (String) -> Unit, onSubmit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(card.id) { runCatching { focus.requestFocus() } }
    // Подсказка клавиатуре, на каком языке ждём ответ: русский для EN→RU, английский для RU→EN.
    val locale = when {
        card.deck.numeric -> null
        card.id.startsWith("ru:") -> LocaleList("en")
        else -> LocaleList("ru")
    }
    OutlinedTextField(
        value = value,
        onValueChange = { onInput(it.take(64)) },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
        label = { Text(if (card.deck.numeric) "Ответ числом" else "Ответ") },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.merge(Tabular),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (card.deck.numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = ImeAction.Done,
            hintLocales = locale,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
    Button(
        onClick = onSubmit,
        enabled = value.isNotBlank(),
        shape = ButtonShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text("Проверить") }
}

@Composable
private fun ResultCard(card: Card, correct: Boolean, given: String, rewardText: String?) {
    Card(Modifier.fillMaxWidth(), shape = CardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (correct) "Верно" else "Неверно",
                style = MaterialTheme.typography.titleLarge,
                color = if (correct) Accent else MaterialTheme.colorScheme.error,
            )
            if (!correct) {
                Text("Твой ответ: $given", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Правильно: ${CardsMath.answerText(card)}",
                    style = MaterialTheme.typography.titleMedium.merge(Tabular),
                )
                Text(
                    "Карточка вернётся позже в этой сессии",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (card.answers.size > 1) {
                Text(
                    "Подходит: ${CardsMath.answerText(card)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                rewardText ?: "Записываю…",
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                color = if (rewardText?.startsWith("+") == true) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Summary(
    deck: CardDeck,
    session: CardSession,
    earnedLine: String,
    canContinue: Boolean,
    onMore: () -> Unit,
    onDecks: () -> Unit,
) {
    Header(deck.title, "К колодам", onDecks)
    Card(Modifier.fillMaxWidth(), shape = CardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Сессия закончена", style = MaterialTheme.typography.titleLarge)
            Text(
                "Верно с первого раза: ${session.correctFirst} из ${session.total}",
                style = MaterialTheme.typography.bodyLarge.merge(Tabular),
            )
            Text(
                "За сессию: ${if (session.paidMs > 0L) "+" else ""}${CardsMath.durationText(session.paidMs)}",
                style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                color = if (session.paidMs > 0L) Accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(earnedLine, style = MaterialTheme.typography.bodySmall.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (canContinue) {
        Button(onClick = onMore, shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Ещё ${CardsMath.SESSION_SIZE} карточек")
        }
    }
    OutlinedButton(onClick = onDecks, shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text("К колодам")
    }
}

/** Карточка «Карточки» на главной (в «Заработать минуты»): к повторению сегодня и заработок дня. */
@Composable
internal fun CardsHomeCard(onOpen: () -> Unit) {
    val version by Store.version.collectAsState()
    val today = TrustedClock.today()
    val overview by produceState<CardsOverview?>(null, version, today) {
        value = withContext(Dispatchers.IO) { runCatching { loadOverview(today) }.getOrNull() }
    }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Открыть карточки", onClick = onOpen),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Карточки", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Английские слова, таблица умножения, устный счёт",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    overview?.let { o ->
                        val due = if (o.dueTotal > 0) "К повторению ${o.dueTotal} · " else ""
                        due + CardsMath.earnedLine(o.earnedMs, o.capMs)
                    } ?: " ",
                    style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                    color = if ((overview?.dueTotal ?: 0) > 0) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
