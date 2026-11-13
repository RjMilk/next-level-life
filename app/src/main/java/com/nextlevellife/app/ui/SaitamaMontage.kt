package com.nextlevellife.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextlevellife.app.data.BeatKind
import com.nextlevellife.app.data.MontageBeat
import com.nextlevellife.app.pose.RepSound
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.random.Random

/** Фон «нарезки»: почти чёрный, как фон приложения, чтобы надписи и линии читались. */
private val MontageBackground = Color(0xF20F1115)

/** Сколько ждать готовности голоса перед первым кадром: движок TTS просыпается не сразу. */
private const val VOICE_WAIT_MS = 800L

/**
 * «Нарезка» «Сайтамы» поверх экрана: кадры [beats] один за другим ([com.nextlevellife.app.data.SaitamaMath.montage]).
 * Кадр — удар текста (масштаб 1,6 → 1 с перелётом и проявлением), вспышка, диагональные линии скорости,
 * вибрация и свой звук ([RepSound.impact], на финале [RepSound.finale]) с фразой голосом, если она есть
 * (без русского голоса — только сигналы). Тап или «Назад» — пропустить: звук обрывается сразу.
 * Своя графика и свои сигналы, без кадров, звука и образа из аниме.
 */
@Composable
fun SaitamaMontage(beats: List<MontageBeat>, sound: RepSound, onFinished: () -> Unit) {
    if (beats.isEmpty()) return
    val haptics = rememberHaptics()
    val finished by rememberUpdatedState(onFinished)
    var index by remember(beats) { mutableIntStateOf(-1) }
    val skip = {
        sound.stop()
        finished()
    }
    BackHandler(onBack = skip)

    LaunchedEffect(beats) {
        var waited = 0L
        while (sound.voicePending && waited < VOICE_WAIT_MS) {
            delay(50)
            waited += 50
        }
        for ((i, beat) in beats.withIndex()) {
            index = i
            if (beat.kind == BeatKind.FINAL) sound.finale() else sound.impact()
            beat.voice?.let { sound.say(it) }
            haptics.strong()
            if (beat.kind == BeatKind.FINAL) {
                launch {
                    delay(360)
                    haptics.strong()
                }
            }
            delay(beat.durationMs)
        }
        // Естественный конец: финальную фразу не обрываем — звук освободится вместе с экраном.
        finished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MontageBackground)
            .pointerInput(Unit) { detectTapGestures(onTap = { skip() }) },
        contentAlignment = Alignment.Center,
    ) {
        val i = index
        if (i in beats.indices) key(i) { MontageBeatView(beats[i], seed = i) }
        Text(
            "Нажми, чтобы пропустить",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

/** Один кадр: удар текста, вспышка, линии скорости. Анимации стартуют заново для каждого кадра ([key]). */
@Composable
private fun MontageBeatView(beat: MontageBeat, seed: Int) {
    val scale = remember { Animatable(1.6f) }
    val alpha = remember { Animatable(0f) }
    val flash = remember { Animatable(0.75f) }
    val lines = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { alpha.animateTo(1f, tween(90)) }
        // Низкое затухание — текст «влетает» и чуть проскакивает размер, как от удара.
        launch { scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 700f)) }
        launch { flash.animateTo(0f, tween(320)) }
        launch { lines.animateTo(1f, tween(beat.durationMs.toInt().coerceAtLeast(1), easing = LinearEasing)) }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Тёплое пятно в центре: акцент приложения за надписью.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.30f), Color.Transparent))),
        )
        SpeedLines(progress = lines.value, seed = seed)
        // Вспышка: заливка акцентом, гаснет за треть секунды.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = flash.value * 0.55f }
                .background(Accent),
        )
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                    rotationZ = -4f
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Цифры вспыхивают белым и остывают в акцент.
            val numberColor = lerp(Accent, Color.White, flash.value / 0.75f)
            when (beat.kind) {
                BeatKind.GOAL -> {
                    Text(beat.big, style = scoreStyle(128, 136), color = numberColor, maxLines = 1)
                    Text(beat.caption, style = shoutStyle(40), color = Color.White, textAlign = TextAlign.Center, maxLines = 1)
                }
                BeatKind.FINAL -> {
                    Text(beat.big, style = shoutStyle(36), color = Color.White, textAlign = TextAlign.Center, maxLines = 2)
                    Text("·", style = shoutStyle(36), color = Accent)
                    Text(beat.caption, style = scoreStyle(56, 64), color = numberColor, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

/** Крупная надпись «нарезки»: Inter Bold, плотный трекинг. */
private fun shoutStyle(sizeSp: Int) = TextStyle(
    fontFamily = InterFont,
    fontWeight = FontWeight.Bold,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp + 6).sp,
    letterSpacing = (-0.5).sp,
)

/**
 * Диагональные линии скорости: летят снизу-слева вверх-направо, за кадр проходят чуть больше ширины экрана.
 * Положения — из [seed], чтобы у каждого кадра был свой рисунок, а внутри кадра он не дёргался.
 */
@Composable
private fun SpeedLines(progress: Float, seed: Int) {
    val specs = remember(seed) {
        val r = Random(seed * 7919 + 17)
        List(18) {
            LineSpec(
                x = r.nextFloat() * 1.6f - 0.6f,
                y = r.nextFloat() * 1.3f - 0.15f,
                length = 0.25f + r.nextFloat() * 0.45f,
                speed = 0.6f + r.nextFloat() * 0.9f,
                width = 2f + r.nextFloat() * 5f,
                alpha = 0.25f + r.nextFloat() * 0.5f,
                accent = r.nextFloat() < 0.4f,
            )
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Направление вверх-вправо, единичный вектор.
        val dx = 1f
        val dy = -0.55f
        val len = sqrt(dx * dx + dy * dy)
        val ux = dx / len
        val uy = dy / len
        val fade = 1f - progress * 0.6f
        for (s in specs) {
            val travel = progress * s.speed * w * 1.2f
            val sx = s.x * w + ux * travel
            val sy = s.y * h + uy * travel
            val l = s.length * w
            drawLine(
                color = (if (s.accent) Accent else Color.White).copy(alpha = s.alpha * fade),
                start = Offset(sx, sy),
                end = Offset(sx + ux * l, sy + uy * l),
                strokeWidth = s.width.dp.toPx() / 2f,
                cap = StrokeCap.Round,
            )
        }
    }
}

private class LineSpec(
    val x: Float,
    val y: Float,
    val length: Float,
    val speed: Float,
    val width: Float,
    val alpha: Float,
    val accent: Boolean,
)
