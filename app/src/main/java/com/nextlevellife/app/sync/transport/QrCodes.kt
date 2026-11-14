package com.nextlevellife.app.sync.transport

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.atomic.AtomicBoolean

/** QR-код сопряжения: рисование (ZXing) и распознавание кадров камеры. */
internal object QrCodes {

    /**
     * Чёрно-белый QR, один пиксель на модуль, с полем 2 модуля. Масштабировать при показе без сглаживания
     * (FilterQuality.None), чтобы края модулей оставались резкими.
     */
    fun encode(text: String): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Анализатор CameraX: ищет QR в яркостной плоскости (Y) кадра и отдаёт первое приглашение сопряжения
 * в [onInvite] (один раз). Чужие QR игнорируются, о них сообщает [onForeignCode] (не чаще раза в 2 с).
 */
internal class QrAnalyzer(
    private val onInvite: (PairingInvite) -> Unit,
    private val onForeignCode: () -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
    private val delivered = AtomicBoolean(false)
    private var buffer = ByteArray(0)
    private var lastForeignAt = 0L

    override fun analyze(image: ImageProxy) {
        image.use {
            if (delivered.get()) return
            val plane = it.planes.firstOrNull() ?: return
            val width = it.width
            val height = it.height
            val rowStride = plane.rowStride
            val data = plane.buffer
            data.rewind()
            val needed = rowStride * height
            if (buffer.size != needed) buffer = ByteArray(needed)
            // У последней строки может не быть «хвоста» шага — читаем сколько есть.
            data.get(buffer, 0, minOf(data.remaining(), needed))
            val source = PlanarYUVLuminanceSource(buffer, rowStride, height, 0, 0, width, height, false)
            val text = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (e: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (text == null) return
            val invite = PairingInvite.parse(text)
            if (invite == null) {
                val now = System.currentTimeMillis()
                if (now - lastForeignAt > 2_000) {
                    lastForeignAt = now
                    onForeignCode()
                }
                return
            }
            if (delivered.compareAndSet(false, true)) onInvite(invite)
        }
    }
}
