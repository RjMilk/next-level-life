package com.nextlevellife.app.ui

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

private const val TAG = "MealCamera"

/**
 * Съёмка еды задней камерой внутри приложения (CameraX: превью + ImageCapture), без выбора из галереи.
 * [rawFile] — куда CameraX пишет снимок (JPEG с EXIF-поворотом); по готовности — [onCaptured] (главный поток),
 * при сбое — [onError] с понятной причиной. Время снимка экран берёт сам в момент нажатия.
 */
@Composable
internal fun MealCameraView(
    modifier: Modifier,
    rawFile: () -> File,
    onShutter: () -> Unit,
    onCaptured: (File) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val captured by rememberUpdatedState(onCaptured)
    val failed by rememberUpdatedState(onError)
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
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
            // Снимок около 2 Мп: после уменьшения до 1600 px по длинной стороне больше не нужно.
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(2048, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .build()
            previewView.display?.rotation?.let { imageCapture.targetRotation = it }
            try {
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                bound = arrayOf(preview, imageCapture)
                capture = imageCapture
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось привязать камеру", e)
                failed("Не удалось включить камеру")
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            capture = null
            if (bound.isNotEmpty()) provider?.unbind(*bound)
        }
    }

    Box(modifier.clip(MaterialTheme.shapes.large).background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        val ready = capture != null && !busy
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
                .size(76.dp)
                .clip(CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(if (ready) Color.White else Color.White.copy(alpha = 0.4f))
                .clickable(enabled = ready, role = Role.Button, onClickLabel = "Сделать снимок") {
                    val ic = capture ?: return@clickable
                    busy = true
                    onShutter()
                    previewView.display?.rotation?.let { ic.targetRotation = it }
                    val out = rawFile()
                    ic.takePicture(
                        ImageCapture.OutputFileOptions.Builder(out).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                busy = false
                                captured(out)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "Снимок не удался", exception)
                                busy = false
                                out.delete()
                                failed("Снимок не удался, попробуй ещё раз")
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
        }
        if (capture == null) {
            Text(
                "Камера включается…",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
