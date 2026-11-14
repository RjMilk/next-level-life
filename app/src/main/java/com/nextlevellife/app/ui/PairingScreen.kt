package com.nextlevellife.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nextlevellife.app.pose.CameraPermissionGate
import com.nextlevellife.app.sync.transport.PairOutcome
import com.nextlevellife.app.sync.transport.PairingInvite
import com.nextlevellife.app.sync.transport.QrAnalyzer
import com.nextlevellife.app.sync.transport.QrCodes
import com.nextlevellife.app.sync.transport.SyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "PairingScreen"

/** Куда идём после проверки разрешения и сети. */
private enum class PairTarget { SHOW, SCAN, CHECK }

/** Понятные причины неудачи. */
private enum class PairError { NO_WIFI, NO_PERMISSION, NOT_FOUND, EXPIRED_SCANNED, EXPIRED_SHOWN, BUSY, SERVER, FAILED }

private sealed interface PairMode {
    data object Home : PairMode
    data object ShowQr : PairMode
    data object Scan : PairMode
    data object Connecting : PairMode
    data class Success(val name: String) : PairMode
    data class Failed(val error: PairError, val retry: PairTarget?) : PairMode
}

private enum class CheckState { IDLE, CHECKING, ONLINE, OFFLINE }

/**
 * «Связь телефонов»: сопряжение двух своих телефонов по QR-коду и состояние связи.
 * Нет пары — объяснение и «Показать QR» / «Сканировать QR»; есть пара — имя второго телефона,
 * «Проверить связь» и «Разорвать связь». Перед показом QR и сканированием запрашивается разрешение
 * локальной сети (Android 17) и проверяется Wi-Fi.
 */
@Composable
fun PairingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peer by SyncTransport.peerFlow.collectAsState()
    var mode by remember { mutableStateOf<PairMode>(PairMode.Home) }
    var pending by remember { mutableStateOf<PairTarget?>(null) }
    var check by remember { mutableStateOf(CheckState.IDLE) }
    var unpairing by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }

    // Сервер нужен и для показа QR: служба могла ещё не запустить транспорт.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { SyncTransport.start(context) } } }

    // Связались, пока на экране QR или идёт подключение, — показать успех; разорвали — на главную.
    LaunchedEffect(peer) {
        val p = peer
        if (p != null && (mode == PairMode.ShowQr || mode == PairMode.Connecting)) mode = PairMode.Success(p.name)
        if (p == null && mode is PairMode.Success) mode = PairMode.Home
    }

    fun runCheck() {
        check = CheckState.CHECKING
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                SyncTransport.start(context)
                SyncTransport.ping()
            }
            check = if (ok) CheckState.ONLINE else CheckState.OFFLINE
        }
    }

    fun proceed(target: PairTarget) {
        when (target) {
            PairTarget.SHOW -> mode = PairMode.ShowQr
            PairTarget.SCAN -> mode = PairMode.Scan
            PairTarget.CHECK -> runCheck()
        }
    }

    lateinit var request: (PairTarget, Boolean) -> Unit
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pending
        pending = null
        if (target == null) return@rememberLauncherForActivityResult
        if (granted) request(target, false) else mode = PairMode.Failed(PairError.NO_PERMISSION, target)
    }
    request = { target, skipWifi ->
        val permission = SyncTransport.networkPermissionToRequest()
        when {
            permission != null && !SyncTransport.hasNetworkPermission(context) -> {
                pending = target
                permissionLauncher.launch(permission)
            }
            !skipWifi && target != PairTarget.CHECK && !SyncTransport.isOnLocalNetwork(context) ->
                mode = PairMode.Failed(PairError.NO_WIFI, target)
            else -> proceed(target)
        }
    }

    val back: () -> Unit = {
        val inFlow = mode == PairMode.ShowQr || mode == PairMode.Scan || mode is PairMode.Failed
        if (inFlow) mode = PairMode.Home else onBack()
    }
    BackHandler(onBack = back)

    val shown: PairMode = when {
        mode is PairMode.Success -> mode
        peer != null && (mode == PairMode.ShowQr || mode == PairMode.Connecting) -> PairMode.Success(peer!!.name)
        else -> mode
    }
    val paired = peer != null && shown !is PairMode.Success

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Связь телефонов", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = back) {
                Text(if (shown == PairMode.ShowQr || shown == PairMode.Scan || shown is PairMode.Failed) "Назад" else "Готово")
            }
        }

        if (paired) {
            PairedContent(
                name = peer!!.name,
                check = check,
                unpairing = unpairing,
                onCheck = { request(PairTarget.CHECK, false) },
                onUnpair = { confirmUnpair = true },
            )
        } else {
            when (val m = shown) {
                PairMode.Home -> HomeContent(
                    onShow = { request(PairTarget.SHOW, false) },
                    onScan = { request(PairTarget.SCAN, false) },
                )
                PairMode.ShowQr -> ShowQrContent(
                    onExpired = { mode = PairMode.Failed(PairError.EXPIRED_SHOWN, PairTarget.SHOW) },
                    onServerFailed = { mode = PairMode.Failed(PairError.SERVER, PairTarget.SHOW) },
                    onCancel = { mode = PairMode.Home },
                )
                PairMode.Scan -> ScanContent(
                    onInvite = { invite ->
                        mode = PairMode.Connecting
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                SyncTransport.start(context)
                                SyncTransport.pairWith(invite)
                            }
                            mode = when (outcome) {
                                PairOutcome.OK -> PairMode.Success(SyncTransport.peer()?.name ?: invite.name)
                                PairOutcome.NOT_FOUND -> PairMode.Failed(PairError.NOT_FOUND, PairTarget.SCAN)
                                PairOutcome.EXPIRED -> PairMode.Failed(PairError.EXPIRED_SCANNED, PairTarget.SCAN)
                                PairOutcome.BUSY -> PairMode.Failed(PairError.BUSY, null)
                                PairOutcome.FAILED -> PairMode.Failed(PairError.FAILED, PairTarget.SCAN)
                            }
                        }
                    },
                    onCancel = { mode = PairMode.Home },
                )
                PairMode.Connecting -> ConnectingContent()
                is PairMode.Success -> SuccessContent(m.name, onDone = onBack)
                is PairMode.Failed -> FailedContent(
                    error = m.error,
                    onRetry = m.retry?.let { target -> { request(target, false) } },
                    onAnyway = if (m.error == PairError.NO_WIFI && m.retry != null) {
                        { request(m.retry, true) }
                    } else {
                        null
                    },
                    onOpenSettings = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                            .onFailure { Log.w(TAG, "Не удалось открыть настройки приложения", it) }
                    },
                    onHome = { mode = PairMode.Home },
                )
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    unpairing = true
                    scope.launch {
                        withContext(Dispatchers.IO) { SyncTransport.unpair() }
                        unpairing = false
                        check = CheckState.IDLE
                        mode = PairMode.Home
                    }
                }) { Text("Разорвать", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Отмена") } },
            title = { Text("Разорвать связь?") },
            text = {
                Text(
                    "Телефоны перестанут обмениваться данными. Если второй телефон сейчас в сети, он тоже забудет связь. " +
                        "Чтобы связать снова, понадобится новый QR-код.",
                )
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Состояния экрана
// ---------------------------------------------------------------------------------------------

@Composable
private fun Explanation(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WideButton(text: String, onClick: () -> Unit, outlined: Boolean = false, enabled: Boolean = true) {
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
    if (outlined) {
        OutlinedButton(onClick = onClick, shape = ButtonShape, modifier = modifier, enabled = enabled) { Text(text) }
    } else {
        Button(onClick = onClick, shape = ButtonShape, modifier = modifier, enabled = enabled) { Text(text) }
    }
}

@Composable
private fun HomeContent(onShow: () -> Unit, onScan: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Два телефона — один баланс", style = MaterialTheme.typography.titleMedium)
            Explanation("Оба телефона должны быть в одной Wi-Fi сети. На одном покажи QR, на другом отсканируй.")
            Text(
                "Связь работает только внутри сети: без интернета, серверов и аккаунтов. Данные шифруются.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    WideButton("Показать QR", onShow)
    WideButton("Сканировать QR", onScan, outlined = true)
}

@Composable
private fun ShowQrContent(onExpired: () -> Unit, onServerFailed: () -> Unit, onCancel: () -> Unit) {
    var invite by remember { mutableStateOf<PairingInvite?>(null) }
    var remainingMs by remember { mutableLongStateOf(0L) }
    val expired by rememberUpdatedState(onExpired)
    val serverFailed by rememberUpdatedState(onServerFailed)

    LaunchedEffect(Unit) {
        val created = withContext(Dispatchers.IO) { SyncTransport.beginPairing() }
        if (created == null) {
            serverFailed()
            return@LaunchedEffect
        }
        invite = created
        while (true) {
            remainingMs = SyncTransport.pairingRemainingMs()
            if (remainingMs <= 0L) {
                // Токен истёк (или израсходован — тогда экран уже сменился на «Связано»).
                if (SyncTransport.peer() == null) expired()
                break
            }
            delay(250)
        }
    }
    // Ушли с экрана QR — токен больше не принимается.
    DisposableEffect(Unit) { onDispose { SyncTransport.cancelPairing() } }

    val bitmap = remember(invite) { invite?.let { QrCodes.encode(it.format()).asImageBitmap() } }

    Explanation("На втором телефоне открой «Связь телефонов» → «Сканировать QR» и наведи камеру на этот код.")
    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White, CardShape)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "QR-код для сопряжения",
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = OnAccent)
        }
    }
    if (invite != null) {
        val totalSec = (remainingMs + 999) / 1000
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text("Ждём второй телефон…", style = MaterialTheme.typography.titleSmall)
                Text(
                    "QR действует ещё %d:%02d".format(totalSec / 60, totalSec % 60),
                    style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    WideButton("Отмена", onCancel, outlined = true)
}

@Composable
private fun ScanContent(onInvite: (PairingInvite) -> Unit, onCancel: () -> Unit) {
    var foreign by remember { mutableStateOf(false) }
    Explanation("Наведи камеру на QR-код на экране второго телефона.")
    CameraPermissionGate(
        Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        QrScannerView(
            onInvite = onInvite,
            onForeignCode = { foreign = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (foreign) {
        Text(
            "Это не QR-код сопряжения. Нужен код с экрана «Связь телефонов» на втором телефоне.",
            style = MaterialTheme.typography.bodyMedium,
            color = Bad,
        )
    }
    WideButton("Отмена", onCancel, outlined = true)
}

@Composable
private fun ConnectingContent() {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text("Ищем второй телефон в сети…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Это займёт до 15 секунд.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SuccessContent(name: String, onDone: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Связано с $name", style = MaterialTheme.typography.titleLarge, color = Good)
            Explanation("Телефоны будут обмениваться данными сами, когда оба в одной Wi-Fi сети.")
        }
    }
    WideButton("Готово", onDone)
}

@Composable
private fun FailedContent(
    error: PairError,
    onRetry: (() -> Unit)?,
    onAnyway: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onHome: () -> Unit,
) {
    val (title, text) = when (error) {
        PairError.NO_WIFI -> "Нет Wi-Fi" to
            "Подключи оба телефона к одной Wi-Fi сети. Если второй телефон раздаёт точку доступа, можно попробовать и так."
        PairError.NO_PERMISSION -> "Нет разрешения" to
            "Без доступа к устройствам в локальной сети телефоны друг друга не увидят. " +
            "Разреши его в настройках: «Разрешения» → «Устройства поблизости» или «Локальная сеть»."
        PairError.NOT_FOUND -> "Второй телефон не найден" to
            "Проверь, что оба телефона в одной Wi-Fi сети, а на втором открыт QR-код. " +
            "Гостевые сети и сети с изоляцией устройств телефоны друг другу не показывают."
        PairError.EXPIRED_SCANNED -> "QR устарел" to
            "Этот код больше не действует. Покажи новый QR на втором телефоне и отсканируй его снова."
        PairError.EXPIRED_SHOWN -> "QR устарел" to
            "Код действует 5 минут. Покажи новый и отсканируй его вторым телефоном."
        PairError.BUSY -> "Второй телефон уже связан" to
            "Сначала разорви связь на втором телефоне, потом свяжи заново."
        PairError.SERVER -> "Не удалось начать" to
            "Телефону не удалось открыть соединение в сети. Проверь Wi-Fi и разрешение локальной сети и попробуй снова."
        PairError.FAILED -> "Не получилось" to "Что-то пошло не так. Попробуй ещё раз."
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Bad)
            Explanation(text)
        }
    }
    if (error == PairError.NO_PERMISSION) WideButton("Открыть настройки", onOpenSettings)
    if (onRetry != null) {
        WideButton(
            when (error) {
                PairError.EXPIRED_SHOWN -> "Показать новый QR"
                PairError.EXPIRED_SCANNED, PairError.NOT_FOUND -> "Сканировать снова"
                else -> "Попробовать снова"
            },
            onRetry,
            outlined = error == PairError.NO_PERMISSION,
        )
    }
    if (onAnyway != null) WideButton("Попробовать всё равно", onAnyway, outlined = true)
    WideButton("Назад", onHome, outlined = true)
}

@Composable
private fun PairedContent(
    name: String,
    check: CheckState,
    unpairing: Boolean,
    onCheck: () -> Unit,
    onUnpair: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Связан с",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(name, style = MaterialTheme.typography.titleLarge, color = Accent)
            Text(
                "Обмен идёт сам, когда оба телефона в одной Wi-Fi сети.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (check) {
                CheckState.IDLE -> Unit
                CheckState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Проверяем…", style = MaterialTheme.typography.bodyMedium)
                }
                CheckState.ONLINE -> Text("На связи", style = MaterialTheme.typography.titleSmall, color = Good)
                CheckState.OFFLINE -> {
                    Text("Не найден", style = MaterialTheme.typography.titleSmall, color = Bad)
                    Text(
                        "Второй телефон не ответил: он не в этой сети, выключен экран и система усыпила приложение, " +
                            "или нет разрешения локальной сети.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    WideButton("Проверить связь", onCheck, enabled = check != CheckState.CHECKING && !unpairing)
    WideButton(if (unpairing) "Разрываем…" else "Разорвать связь", onUnpair, outlined = true, enabled = !unpairing)
}

// ---------------------------------------------------------------------------------------------
// Сканер QR
// ---------------------------------------------------------------------------------------------

/** Превью задней камеры с рамкой; первый найденный QR сопряжения уходит в [onInvite] на главном потоке. */
@Composable
private fun QrScannerView(onInvite: (PairingInvite) -> Unit, onForeignCode: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnInvite by rememberUpdatedState(onInvite)
    val currentOnForeign by rememberUpdatedState(onForeignCode)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var bound: Array<UseCase> = emptyArray()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            val cameraProvider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Камера недоступна", e)
                return@addListener
            }
            provider = cameraProvider
            val selector = when {
                runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false) ->
                    CameraSelector.DEFAULT_BACK_CAMERA
                runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false) ->
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else -> {
                    Log.e(TAG, "На устройстве нет камеры")
                    return@addListener
                }
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(
                analysisExecutor,
                QrAnalyzer(
                    onInvite = { invite -> mainExecutor.execute { if (!disposed) currentOnInvite(invite) } },
                    onForeignCode = { mainExecutor.execute { if (!disposed) currentOnForeign() } },
                ),
            )
            try {
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                analysis = imageAnalysis
                bound = arrayOf(preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось привязать камеру", e)
                imageAnalysis.clearAnalyzer()
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            if (bound.isNotEmpty()) provider?.unbind(*bound)
        }
    }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Рамка-прицел: квадрат 70 % стороны по центру.
        Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height) * 0.7f
            val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
            drawRoundRect(
                color = Accent,
                topLeft = topLeft,
                size = androidx.compose.ui.geometry.Size(side, side),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}
