package com.nextlevellife.app.ui

import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.Camera
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.nextlevellife.app.data.BarcodeKind
import com.nextlevellife.app.data.ProductsMath
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TAG = "BarcodeScanner"

/** Сколько кадров подряд должен прочитаться один и тот же код: случайное ложное чтение не выбирает продукт. */
private const val STABLE_FRAMES = 2

/**
 * Сканер штрихкодов продуктов: задняя камера (CameraX: превью + анализ кадров) и ML Kit Barcode Scanning с моделью
 * внутри APK — всё на устройстве, без сети. Форматы — EAN-8, EAN-13, UPC-A, UPC-E; код проходит
 * [ProductsMath.normalizeScan] (UPC-E разворачивается в UPC-A, неверная контрольная цифра отбрасывается) и должен
 * прочитаться [STABLE_FRAMES] кадра подряд. [onBarcode] вызывается один раз (главный поток), [onError] — с понятной
 * причиной, если камера не включилась. Разрешение камеры проверяет вызывающий ([com.nextlevellife.app.pose.CameraPermissionGate]).
 */
@Composable
internal fun BarcodeScannerView(
    modifier: Modifier,
    onBarcode: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val found by rememberUpdatedState(onBarcode)
    val failed by rememberUpdatedState(onError)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .build(),
        )
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // Объявлен раньше привязки камеры, поэтому освобождается после неё: сначала камера перестаёт слать кадры,
    // потом закрываем распознавание и поток.
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

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
                failed("Камера недоступна")
                return@addListener
            }
            provider = cameraProvider
            fun has(s: CameraSelector) = runCatching { cameraProvider.hasCamera(s) }.getOrDefault(false)
            val selector = when {
                has(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                has(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> {
                    failed("На телефоне нет камеры")
                    return@addListener
                }
            }
            val ratio = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            val preview = Preview.Builder().setResolutionSelector(ratio.build()).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // Около 1280×960: штрихи тонкие, 640×480 мало для кода издалека, больше — медленнее без пользы.
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            previewView.display?.rotation?.let { imageAnalysis.targetRotation = it }
            imageAnalysis.setAnalyzer(
                analysisExecutor,
                BarcodeAnalyzer(scanner, mainExecutor) { code -> if (!disposed) found(code) },
            )
            try {
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                analysis = imageAnalysis
                bound = arrayOf(preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось привязать камеру", e)
                imageAnalysis.clearAnalyzer()
                failed("Не удалось включить камеру")
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            camera = null
            torch = false
            analysis?.clearAnalyzer()
            if (bound.isNotEmpty()) provider?.unbind(*bound)
        }
    }

    Box(modifier.clip(MaterialTheme.shapes.large).background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Рамка-подсказка: штрихкод — поперёк, по центру.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.8f)
                .aspectRatio(2.2f)
                .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp)),
        )
        val cam = camera
        if (cam == null) {
            Text(
                "Камера включается…",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (cam.cameraInfo.hasFlashUnit()) {
            TextButton(
                onClick = {
                    torch = !torch
                    cam.cameraControl.enableTorch(torch)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.5f)),
            ) { Text(if (torch) "Выключить фонарик" else "Фонарик", color = Color.White) }
        }
    }
}

/**
 * Кадр → ML Kit → проверенный код. Следующий кадр CameraX отдаёт только после close(), поэтому распознаётся
 * не больше одного кадра за раз. Код отдаётся один раз, когда прочитался [STABLE_FRAMES] кадра подряд.
 */
private class BarcodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val callbackExecutor: Executor,
    private val deliver: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    // Кадр в работе всегда один (следующий — после close() в конце обработки), но обработчики ML Kit идут на его
    // потоке, а analyze — на потоке анализа: поля видимы обоим.
    @Volatile private var last: String? = null
    @Volatile private var streak = 0
    @Volatile private var delivered = false
    private val direct = Executor { it.run() }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || delivered) {
            imageProxy.close()
            return
        }
        val task = try {
            scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
        } catch (e: Exception) {
            // Распознавание уже закрыто (экран уходит) — кадр просто отпускаем.
            Log.w(TAG, "Кадр не передан в распознавание", e)
            imageProxy.close()
            return
        }
        task
            .addOnSuccessListener(direct) { barcodes -> onCodes(barcodes) }
            .addOnFailureListener(direct) { Log.w(TAG, "Кадр не распознан", it) }
            .addOnCompleteListener(direct) { imageProxy.close() }
    }

    private fun onCodes(barcodes: List<Barcode>) {
        if (delivered) return
        val code = barcodes.firstNotNullOfOrNull { b ->
            val kind = when (b.format) {
                Barcode.FORMAT_EAN_13 -> BarcodeKind.EAN_13
                Barcode.FORMAT_EAN_8 -> BarcodeKind.EAN_8
                Barcode.FORMAT_UPC_A -> BarcodeKind.UPC_A
                Barcode.FORMAT_UPC_E -> BarcodeKind.UPC_E
                else -> null
            }
            val raw = b.rawValue
            if (kind == null || raw == null) null else ProductsMath.normalizeScan(kind, raw)
        }
        if (code == null) {
            streak = 0
            last = null
            return
        }
        streak = if (code == last) streak + 1 else 1
        last = code
        if (streak >= STABLE_FRAMES) {
            delivered = true
            callbackExecutor.execute { deliver(code) }
        }
    }
}
