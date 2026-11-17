package com.nextlevellife.app.pose

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * «Отправить записи тренировок»: записи «чёрного ящика» ([PoseLogWriter]) за период — одним zip
 * во внутреннем cache/exports и системное «Поделиться» через FileProvider.
 * В записях только координаты суставов и состояние счёта, видео нет.
 *
 * Разбор файлов и упаковка — чистый Kotlin (проверяются тестами); [dirs] и [share] — Android.
 */
object PoseLogExport {
    /** Подкаталог cache, открытый FileProvider'у (res/xml/file_paths.xml). Больше ничего наружу не отдаётся. */
    const val EXPORT_DIR = "exports"

    /** Периоды на выбор в настройках, дни; [DEFAULT_DAYS] — по умолчанию. */
    val PERIOD_DAYS = listOf(1, 3, 7, 30)
    const val DEFAULT_DAYS = 3

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Итог сборки: архив, число записей в нём и размер архива. */
    class Packed(val zip: File, val files: Int, val bytes: Long)

    /** Каталоги, куда [PoseLogWriter] пишет записи (внешний каталог приложения или запасной внутренний). */
    fun dirs(context: Context): List<File> =
        listOfNotNull(context.getExternalFilesDir(PoseLogWriter.DIR), File(context.filesDir, PoseLogWriter.DIR)).distinct()

    /** Записи (`*.csv`) из [dirs], изменённые не раньше чем за [days] суток до [nowMs]; по имени (дата-время) по порядку. */
    fun select(dirs: List<File>, nowMs: Long, days: Int): List<File> {
        val since = nowMs - days * DAY_MS
        return dirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") && f.lastModified() >= since }?.toList().orEmpty()
        }.sortedBy { it.name }
    }

    /**
     * Упаковать [files] в [zip] (старый файл заменяется). Одинаковые имена из разных каталогов
     * получают суффикс, чтобы ни одна запись не потерялась.
     */
    fun pack(files: List<File>, zip: File): Packed {
        zip.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("нет каталога $it") }
        val used = HashSet<String>()
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for (f in files) {
                var name = f.name
                var n = 2
                while (!used.add(name)) name = "${f.name.removeSuffix(".csv")}-${n++}.csv"
                val entry = ZipEntry(name)
                entry.time = f.lastModified()
                out.putNextEntry(entry)
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return Packed(zip, files.size, zip.length())
    }

    /** Имя архива: poselog-ГГГГММДД-ЧЧММСС.zip. */
    fun zipName(nowMs: Long): String =
        "poselog-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + ".zip"

    /** Размер по-русски: «820 Б», «12 КБ», «1,4 МБ». */
    fun sizeText(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} КБ"
        else -> {
            val tenths = (bytes * 10 + 512 * 1024) / (1024 * 1024)
            "${tenths / 10},${tenths % 10} МБ"
        }
    }

    /** «3 записи», «1 запись», «5 записей». */
    fun countText(n: Int): String {
        val word = when {
            n % 100 in 11..14 -> "записей"
            n % 10 == 1 -> "запись"
            n % 10 in 2..4 -> "записи"
            else -> "записей"
        }
        return "$n $word"
    }

    /** Собрать архив за [days] суток в cache/exports (прежние архивы удаляются). null — записей нет. */
    fun build(context: Context, days: Int, nowMs: Long = System.currentTimeMillis()): Packed? {
        val files = select(dirs(context), nowMs, days)
        if (files.isEmpty()) return null
        val dir = File(context.cacheDir, EXPORT_DIR)
        dir.listFiles { f -> f.isFile && f.name.startsWith("poselog-") && f.name.endsWith(".zip") }?.forEach { it.delete() }
        return pack(files, File(dir, zipName(nowMs)))
    }

    /** Системное «Поделиться» для архива. */
    fun share(context: Context, packed: Packed) {
        val uri = FileProvider.getUriForFile(context, authority(context), packed.zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Записи тренировок Next Level Life")
            clipData = ClipData.newRawUri(packed.zip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Отправить записи тренировок")
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** Совпадает с android:authorities провайдера в манифесте. */
    fun authority(context: Context): String = context.packageName + ".exports"
}
