package com.nextlevellife.app.pose

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.nextlevellife.app.ui.Accent
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import androidx.compose.ui.geometry.Size as GeoSize

private const val TAG = "PoseCamera"

/** Ниже этой уверенности сустав не рисуем: ML Kit «додумывает» невидимые точки. */
private const val MIN_DRAW_CONFIDENCE = 0.5f

// ---------------------------------------------------------------------------------------------
// Разрешение камеры
// ---------------------------------------------------------------------------------------------

/** Показывает content только при выданном разрешении камеры; иначе — объяснение и кнопку запроса. */
@Composable
fun CameraPermissionGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    // Нужно, чтобы отличить «запрещено навсегда» от закрытия диалога тапом мимо при первом запросе.
    var deniedBefore by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    var requestStartedAt by remember { mutableLongStateOf(0L) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) {
            blocked = false
            return@rememberLauncherForActivityResult
        }
        val activity = context.findActivity()
        val rationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        // Ответ пришёл мгновенно (система не показала диалог) или это повторный отказ без «объяснения» —
        // значит, запрещено навсегда и поможет только экран настроек.
        val instant = SystemClock.elapsedRealtime() - requestStartedAt < 600
        blocked = !rationale && (instant || deniedBefore)
        deniedBefore = true
    }

    // Разрешение могли выдать в настройках, пока экран был скрыт.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasCameraPermission(context)
                if (granted) blocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        Box(modifier) { content() }
    } else {
        CameraPermissionRequest(
            modifier = modifier,
            blocked = blocked,
            onRequest = {
                requestStartedAt = SystemClock.elapsedRealtime()
                launcher.launch(Manifest.permission.CAMERA)
            },
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { Log.w(TAG, "Не удалось открыть настройки приложения", it) }
            },
        )
    }
}

@Composable
private fun CameraPermissionRequest(
    modifier: Modifier,
    blocked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(Modifier.size(72.dp)) { drawCameraGlyph(Accent) }
            Spacer(Modifier.height(20.dp))
            Text(
                "Нужна камера",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Повторения считает камера. Видео обрабатывается на телефоне и никуда не отправляется.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (blocked) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Доступ к камере запрещён. Открой настройки → «Разрешения» → «Камера».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = if (blocked) onOpenSettings else onRequest,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(if (blocked) "Открыть настройки" else "Разрешить камеру")
            }
        }
    }
}

/** Значок камеры рисуем сами: в material3 нет набора иконок, тянуть зависимость ради одного значка незачем. */
private fun DrawScope.drawCameraGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.06f, cap = StrokeCap.Round)
    val bodyTop = h * 0.30f
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.08f, bodyTop),
        size = GeoSize(w * 0.84f, h * 0.56f),
        cornerRadius = CornerRadius(w * 0.10f),
        style = stroke,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.32f, h * 0.17f),
        size = GeoSize(w * 0.36f, h * 0.13f),
        cornerRadius = CornerRadius(w * 0.04f),
        style = stroke,
    )
    drawCircle(color = color, radius = w * 0.16f, center = Offset(w * 0.5f, bodyTop + h * 0.28f), style = stroke)
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ---------------------------------------------------------------------------------------------
// Превью и распознавание позы
// ---------------------------------------------------------------------------------------------

/**
 * Превью камеры со скелетом. onFrame вызывается в главном потоке на каждый обработанный кадр
 * (в том числе с пустыми points, когда человека нет).
 */
@Composable
fun PoseCameraView(
    modifier: Modifier = Modifier,
    frontCamera: Boolean = true,
    showSkeleton: Boolean = true,
    onFrame: (PoseFrame) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentShowSkeleton by rememberUpdatedState(showSkeleton)
    // Читается только при рисовании: новый кадр перерисовывает Canvas без перекомпозиции.
    val lastFrame = remember { mutableStateOf<PoseFrame?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            // TextureView, а не SurfaceView: иначе Compose-Canvas поверх может оказаться под превью.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val detector = remember {
        PoseDetection.getClient(
            AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                .build(),
        )
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // Объявлен раньше привязки камеры, поэтому освобождается после неё:
    // сначала камера перестаёт слать кадры, потом закрываем детектор и поток.
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            detector.close()
        }
    }

    DisposableEffect(lifecycleOwner, frontCamera) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var bound: Array<UseCase> = emptyArray()
        lastFrame.value = null

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
            val (selector, isFront) = chooseCamera(cameraProvider, frontCamera) ?: run {
                Log.e(TAG, "На устройстве нет камеры")
                return@addListener
            }

            // Превью и анализ в одном соотношении 4:3: оба видят одно поле зрения,
            // и FILL_CENTER для кадра анализа совпадает с тем, как PreviewView обрезает превью.
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build(),
                )
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            previewView.display?.rotation?.let { imageAnalysis.targetRotation = it }
            imageAnalysis.setAnalyzer(
                analysisExecutor,
                PoseAnalyzer(detector, mirror = isFront, callbackExecutor = mainExecutor) { frame ->
                    if (disposed) return@PoseAnalyzer
                    lastFrame.value = if (currentShowSkeleton) frame else null
                    currentOnFrame(frame)
                },
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
        if (showSkeleton) {
            Canvas(Modifier.fillMaxSize()) {
                lastFrame.value?.let { drawSkeleton(it) }
            }
        }
    }
}

/** Запрошенная камера, а если её нет — другая. Второе значение — фронтальная ли она на самом деле. */
private fun chooseCamera(provider: ProcessCameraProvider, front: Boolean): Pair<CameraSelector, Boolean>? {
    val wanted = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    val other = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
    fun has(selector: CameraSelector) = try {
        provider.hasCamera(selector)
    } catch (e: Exception) {
        false
    }
    return when {
        has(wanted) -> wanted to front
        has(other) -> other to !front
        else -> null
    }
}

private val LANDMARKS: List<Pair<Joint, Int>> = listOf(
    Joint.NOSE to PoseLandmark.NOSE,
    Joint.LEFT_SHOULDER to PoseLandmark.LEFT_SHOULDER,
    Joint.RIGHT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
    Joint.LEFT_ELBOW to PoseLandmark.LEFT_ELBOW,
    Joint.RIGHT_ELBOW to PoseLandmark.RIGHT_ELBOW,
    Joint.LEFT_WRIST to PoseLandmark.LEFT_WRIST,
    Joint.RIGHT_WRIST to PoseLandmark.RIGHT_WRIST,
    Joint.LEFT_HIP to PoseLandmark.LEFT_HIP,
    Joint.RIGHT_HIP to PoseLandmark.RIGHT_HIP,
    Joint.LEFT_KNEE to PoseLandmark.LEFT_KNEE,
    Joint.RIGHT_KNEE to PoseLandmark.RIGHT_KNEE,
    Joint.LEFT_ANKLE to PoseLandmark.LEFT_ANKLE,
    Joint.RIGHT_ANKLE to PoseLandmark.RIGHT_ANKLE,
)

/**
 * Кадр → ML Kit → [PoseFrame]. Следующий кадр CameraX отдаёт только после close(),
 * поэтому распознаётся не больше одного кадра за раз, а лишние отбрасываются.
 */
private class PoseAnalyzer(
    private val detector: PoseDetector,
    private val mirror: Boolean,
    private val callbackExecutor: Executor,
    private val deliver: (PoseFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val timeMs = SystemClock.elapsedRealtime()
        try {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }
            val rotation = imageProxy.imageInfo.rotationDegrees
            // Координаты ML Kit — в уже повёрнутом кадре, поэтому и размеры отдаём повёрнутые.
            val upright = rotation % 180 == 0
            val width = if (upright) mediaImage.width else mediaImage.height
            val height = if (upright) mediaImage.height else mediaImage.width
            val input = InputImage.fromMediaImage(mediaImage, rotation)
            detector.process(input).addOnCompleteListener(callbackExecutor) { task ->
                imageProxy.close()
                if (task.isCanceled) return@addOnCompleteListener
                val pose = if (task.isSuccessful) task.result else null
                if (!task.isSuccessful) Log.w(TAG, "Ошибка распознавания позы", task.exception)
                deliver(PoseFrame(timeMs, width, height, pose.toPoints(width)))
            }
        } catch (e: Exception) {
            // Без close() CameraX больше не пришлёт ни одного кадра.
            Log.w(TAG, "Кадр пропущен", e)
            imageProxy.close()
        }
    }

    private fun Pose?.toPoints(width: Int): Map<Joint, PosePoint> {
        if (this == null || allPoseLandmarks.isEmpty()) return emptyMap()
        val points = HashMap<Joint, PosePoint>(LANDMARKS.size * 2)
        for ((joint, type) in LANDMARKS) {
            val landmark = getPoseLandmark(type) ?: continue
            val p = landmark.position
            // Фронтальное превью зеркальное — отражаем x, чтобы точки легли на тело в превью.
            val x = if (mirror) width - p.x else p.x
            points[joint] = PosePoint(x, p.y, landmark.inFrameLikelihood)
        }
        return points
    }
}

// ---------------------------------------------------------------------------------------------
// Скелет
// ---------------------------------------------------------------------------------------------

private val BONES: List<Pair<Joint, Joint>> = listOf(
    Joint.LEFT_SHOULDER to Joint.RIGHT_SHOULDER,
    Joint.LEFT_HIP to Joint.RIGHT_HIP,
    Joint.LEFT_SHOULDER to Joint.LEFT_ELBOW,
    Joint.LEFT_ELBOW to Joint.LEFT_WRIST,
    Joint.RIGHT_SHOULDER to Joint.RIGHT_ELBOW,
    Joint.RIGHT_ELBOW to Joint.RIGHT_WRIST,
    Joint.LEFT_SHOULDER to Joint.LEFT_HIP,
    Joint.LEFT_HIP to Joint.LEFT_KNEE,
    Joint.LEFT_KNEE to Joint.LEFT_ANKLE,
    Joint.RIGHT_SHOULDER to Joint.RIGHT_HIP,
    Joint.RIGHT_HIP to Joint.RIGHT_KNEE,
    Joint.RIGHT_KNEE to Joint.RIGHT_ANKLE,
)

private val JointShadow = Color(0x99000000)

private fun DrawScope.drawSkeleton(frame: PoseFrame) {
    if (frame.points.isEmpty() || frame.width <= 0 || frame.height <= 0) return
    // То же преобразование, что FILL_CENTER у PreviewView: масштаб по большей стороне, центр, обрезка краёв.
    val scale = max(size.width / frame.width, size.height / frame.height)
    val dx = (size.width - frame.width * scale) / 2f
    val dy = (size.height - frame.height * scale) / 2f

    fun at(joint: Joint): Offset? {
        val p = frame.points[joint] ?: return null
        if (p.confidence < MIN_DRAW_CONFIDENCE) return null
        return Offset(dx + p.x * scale, dy + p.y * scale)
    }

    val line = 4.dp.toPx()
    for ((a, b) in BONES) {
        val pa = at(a) ?: continue
        val pb = at(b) ?: continue
        drawLine(Accent, pa, pb, strokeWidth = line, cap = StrokeCap.Round)
    }
    val radius = 5.dp.toPx()
    val outline = radius + 1.5.dp.toPx()
    for (joint in Joint.entries) {
        val c = at(joint) ?: continue
        // Тёмная обводка, чтобы белая точка была видна на светлой стене.
        drawCircle(JointShadow, radius = outline, center = c)
        drawCircle(Color.White, radius = radius, center = c)
    }
}
