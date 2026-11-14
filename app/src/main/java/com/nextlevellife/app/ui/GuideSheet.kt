package com.nextlevellife.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.guide.ExerciseGuide
import com.nextlevellife.app.guide.GuidePrefs
import com.nextlevellife.app.guide.Guides

/** Наибольшая ширина показа на развёрнутом экране (анимация слева, текст справа). */
private val GuideWideMaxWidth = 960.dp

/**
 * Показ техники упражнения: название, анимация (сразу играет по кругу, нажатие — пауза), строка
 * «Камера: 2–3 м · сбоку», как поставить телефон, подсказки, за что повтор не засчитается,
 * галочка «Больше не показывать для этого упражнения» и кнопки «Назад» ([onClose]) / [startLabel] ([onStart]).
 * На узком и среднем экране — одна колонка не шире [ContentMaxWidth]; на развёрнутом — анимация
 * слева, текст справа. Если гида у упражнения нет, сразу вызывается [onStart].
 */
@Composable
fun GuideSheet(
    exerciseId: String,
    onStart: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    startLabel: String = "Начать",
) {
    val guide = remember(exerciseId) { Guides.of(exerciseId) }
    if (guide == null) {
        LaunchedEffect(exerciseId) { onStart() }
        return
    }
    val name = remember(exerciseId) {
        Exercises.all.firstOrNull { it.id == exerciseId }?.name ?: guide.title ?: ""
    }
    val widthClass = currentWidthClass()
    val wide = widthClass == WidthClass.EXPANDED
    var playing by rememberSaveable(exerciseId) { mutableStateOf(true) }
    var skip by remember(exerciseId) { mutableStateOf(GuidePrefs.isSkipped(exerciseId)) }

    Box(modifier.fillMaxSize().padding(widthClass.outerPadding), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = if (wide) GuideWideMaxWidth else ContentMaxWidth)
                .fillMaxSize(),
        ) {
            Text(
                "Как выполнять",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
            Spacer(Modifier.height(12.dp))

            if (wide) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(widthClass.gutter),
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        GuideStage(guide, playing, { playing = !playing }, Modifier.aspectRatio(1f))
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) { GuideTexts(guide) }
                }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    GuideStage(
                        guide, playing, { playing = !playing },
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10f),
                    )
                    Spacer(Modifier.height(16.dp))
                    GuideTexts(guide)
                }
            }

            Spacer(Modifier.height(8.dp))
            SkipRow(skip) {
                skip = it
                GuidePrefs.setSkipped(exerciseId, it)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onClose,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MainButtonHeight),
                ) { Text("Назад", style = MaterialTheme.typography.titleMedium, maxLines = 1) }
                Button(
                    onClick = onStart,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1.6f)
                        .heightIn(min = MainButtonHeight),
                ) { Text(startLabel, style = MaterialTheme.typography.titleLarge, maxLines = 1) }
            }
        }
    }
}

/** Гид поверх экрана упражнения (кнопка ▶). Пока он открыт, родитель держит счёт на паузе. */
@Composable
fun GuideDialog(exerciseId: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            GuideSheet(exerciseId, onStart = onClose, onClose = onClose, startLabel = "Продолжить")
        }
    }
}

/** Анимация в карточке: главный акцент сверху, значок паузы/продолжения снизу; нажатие — пауза. */
@Composable
private fun GuideStage(guide: ExerciseGuide, playing: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                role = Role.Button,
                onClickLabel = if (playing) "Пауза" else "Продолжить показ",
                onClick = onToggle,
            ),
    ) {
        GuideFigure(guide, Modifier.fillMaxSize().padding(8.dp), playing)
        Text(
            guide.focus,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .background(CameraScrim, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(CameraScrim),
            contentAlignment = Alignment.Center,
        ) {
            if (playing) PauseIcon(Color.White) else PlayIcon(Color.White, Modifier.size(14.dp))
        }
    }
}

@Composable
private fun GuideTexts(guide: ExerciseGuide) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(guide.cameraLine, style = MaterialTheme.typography.titleMedium, color = Accent)
        Text(guide.camera, style = MaterialTheme.typography.bodyMedium, color = muted)
        Spacer(Modifier.height(12.dp))
        Text("Важно", style = MaterialTheme.typography.titleSmall)
        guide.tips.forEach { Bullet(it, MaterialTheme.colorScheme.onSurface, Good) }
        Spacer(Modifier.height(12.dp))
        Text("Не засчитается", style = MaterialTheme.typography.titleSmall)
        guide.mistakes.forEach { Bullet(it, MaterialTheme.colorScheme.onSurface, Bad) }
    }
}

@Composable
private fun Bullet(text: String, color: Color, dot: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 9.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun SkipRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text("Больше не показывать для этого упражнения", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Треугольник ▶, нарисованный сам (не символ шрифта: ровно по центру при любом шрифте). */
@Composable
internal fun PlayIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.12f, 0f)
            lineTo(w, h / 2f)
            lineTo(w * 0.12f, h)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PauseIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val bar = size.width * 0.32f
        drawRect(color, Offset(size.width * 0.08f, 0f), Size(bar, size.height))
        drawRect(color, Offset(size.width * 0.6f, 0f), Size(bar, size.height))
    }
}

/** Кнопка ▶ «Как выполнять» в заголовке экрана упражнения. */
@Composable
internal fun GuideButton(onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Как выполнять", onClick = onClick)
            .semantics { contentDescription = "Как выполнять упражнение" },
        contentAlignment = Alignment.Center,
    ) {
        PlayIcon(if (enabled) Accent else Accent.copy(alpha = 0.38f), Modifier.size(16.dp).padding(start = 2.dp))
    }
}
