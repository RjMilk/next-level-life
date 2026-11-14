package com.nextlevellife.app.media

import kotlinx.coroutines.launch
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import android.util.LruCache
import com.nextlevellife.app.data.Nutrition
import com.nextlevellife.app.data.NutritionMath
import java.io.File
import java.io.IOException

/**
 * Файлы фото приёмов пищи: постоянные — filesDir/meals/<uid>.jpg (длинная сторона 1600 px, JPEG 85 %, уже повёрнуты),
 * снимок до подтверждения — cacheDir/meal-pending, уменьшенные превью ленты — в памяти. Всё блокирующее — вызывать
 * не из главного потока.
 */
object MealPhotos {
    private const val TAG = "MealPhotos"
    private const val PENDING_DIR = "meal-pending"

    /** Превью ленты: ключ «имя@размер», размер кеша — в КБ. */
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun dir(context: Context): File = File(context.filesDir, NutritionMath.PHOTO_DIR)

    /** Файл фото [photoName] (только безопасное имя); null — имя недопустимо. */
    fun file(context: Context, photoName: String): File? =
        if (NutritionMath.isSafePhotoName(photoName)) File(dir(context), photoName) else null

    /** Есть ли файл фото на этом телефоне. */
    fun exists(context: Context, photoName: String?): Boolean = photoName != null && file(context, photoName)?.isFile == true

    /** Обработанный снимок [uid], ещё не сохранённый приёмом. */
    fun pendingFile(context: Context, uid: String): File {
        require(NutritionMath.isSafeUid(uid))
        return File(File(context.cacheDir, PENDING_DIR).apply { mkdirs() }, NutritionMath.photoNameFor(uid))
    }

    /** Сырой снимок камеры (с EXIF) — во временный файл кеша. */
    fun rawFile(context: Context, uid: String): File = File(File(context.cacheDir, PENDING_DIR).apply { mkdirs() }, "raw-$uid.jpg")

    /**
     * Снимок камеры [src] → [dest]: поворот по EXIF Orientation, длинная сторона не больше
     * [NutritionMath.PHOTO_MAX_SIDE], JPEG [NutritionMath.PHOTO_QUALITY] %. Запись через временный файл.
     */
    fun process(src: File, dest: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Снимок не читается")
        val sample = NutritionMath.sampleSize(bounds.outWidth, bounds.outHeight, NutritionMath.PHOTO_MAX_SIDE)
        val decoded = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Снимок не читается")
        val orientation = runCatching {
            ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val (degrees, flip) = NutritionMath.exifTransform(orientation)
        val (w, h) = NutritionMath.scaledSize(decoded.width, decoded.height)
        val m = Matrix().apply {
            postScale(w.toFloat() / decoded.width * (if (flip) -1f else 1f), h.toFloat() / decoded.height)
            if (degrees != 0) postRotate(degrees.toFloat())
        }
        val out = if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        try {
            val tmp = File(dest.path + ".tmp")
            tmp.outputStream().use { stream ->
                if (!out.compress(Bitmap.CompressFormat.JPEG, NutritionMath.PHOTO_QUALITY, stream)) throw IOException("Не удалось сжать фото")
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IOException("Не удалось сохранить фото")
            }
        } finally {
            if (out !== decoded) out.recycle()
            decoded.recycle()
        }
    }

    /** Переносит обработанный снимок [uid] в постоянные фото. true — файл на месте. */
    fun commit(context: Context, uid: String): Boolean {
        val pending = pendingFile(context, uid)
        val dest = file(context, NutritionMath.photoNameFor(uid)) ?: return false
        dir(context).mkdirs()
        if (pending.renameTo(dest)) return true
        return try {
            pending.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            pending.delete()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Не удалось сохранить фото", e)
            dest.delete()
            false
        }
    }

    /** Удаляет постоянные фото [names] (только безопасные имена) и их превью. */
    fun delete(context: Context, names: Collection<String>) {
        for (name in names) {
            file(context, name)?.delete()
            thumbs.snapshot().keys.filter { it.startsWith("$name@") }.forEach { thumbs.remove(it) }
        }
    }

    /** Уборка: фото удалённых приёмов (в том числе удалённых на втором телефоне) и брошенные снимки. */
    fun prune(context: Context) {
        try {
            delete(context, Nutrition.deletedPhotoNames())
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось убрать фото удалённых приёмов", e)
        }
        val day = 24 * 3_600_000L
        File(context.cacheDir, PENDING_DIR).listFiles()?.forEach {
            if (System.currentTimeMillis() - it.lastModified() > day) it.delete()
        }
    }

    /** Уменьшенное фото для ленты (длинная сторона не меньше [sidePx]); null — файла нет или он не читается. */
    fun thumbnail(context: Context, photoName: String, sidePx: Int): Bitmap? {
        val key = "$photoName@$sidePx"
        thumbs.get(key)?.let { return it }
        val f = file(context, photoName)?.takeIf { it.isFile } ?: return null
        return decode(f, sidePx)?.also { thumbs.put(key, it) }
    }

    /** Фото для просмотра крупно (длинная сторона не меньше [sidePx], если исходник больше). */
    fun full(context: Context, photoName: String, sidePx: Int = NutritionMath.PHOTO_MAX_SIDE): Bitmap? {
        val f = file(context, photoName)?.takeIf { it.isFile } ?: return null
        return decode(f, sidePx)
    }

    /** Обработанный, но ещё не сохранённый снимок. */
    fun pendingBitmap(context: Context, uid: String, sidePx: Int): Bitmap? =
        pendingFile(context, uid).takeIf { it.isFile }?.let { decode(it, sidePx) }

    private fun decode(f: File, sidePx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) {
            null
        } else {
            val sample = NutritionMath.sampleSize(bounds.outWidth, bounds.outHeight, sidePx)
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        Log.w(TAG, "Фото не читается: ${f.name}", e)
        null
    }
}

/**
 * Обработка снимков вне экрана: поворот или раскрытие Fold пересоздают экран и отменяют его корутины, а обработка
 * должна дойти до конца. Экран ждёт итог по uid ([result]) — null, пока идёт.
 */
object MealProcessing {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val results = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Запустить обработку сырого снимка [raw] в ожидающий файл приёма [uid]. */
    fun start(context: Context, uid: String, raw: File) {
        if (!running.add(uid)) return
        results.remove(uid)
        val app = context.applicationContext
        scope.launch {
            val ok = runCatching { MealPhotos.process(raw, MealPhotos.pendingFile(app, uid)) }
                .onFailure { android.util.Log.e("MealProcessing", "Не удалось обработать снимок", it) }
                .isSuccess
            raw.delete()
            results[uid] = ok
            running.remove(uid)
        }
    }

    /** Итог обработки [uid]: true — готово, false — сбой, null — ещё идёт или не запускалась. */
    fun result(uid: String): Boolean? = results[uid]

    fun isRunning(uid: String): Boolean = uid in running

    fun forget(uid: String) { results.remove(uid) }
}
