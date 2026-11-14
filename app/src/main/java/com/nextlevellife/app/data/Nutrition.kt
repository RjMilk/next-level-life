package com.nextlevellife.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Приём пищи в дневнике питания. [photoName] — имя файла в filesDir/meals (null — снимка не было);
 * у приёма со второго телефона файла здесь нет. [author] — device_id телефона, где сделан снимок.
 */
data class MealItem(
    val uid: String,
    val author: String,
    val capturedUtcMs: Long,
    val day: Long,
    val zoneId: String,
    val photoName: String?,
    val note: String?,
)

/** Окно питания за учётные сутки: первый и последний зафиксированный приём. */
data class MealWindow(val first: MealItem, val last: MealItem, val count: Int) {
    val durationMs: Long get() = last.capturedUtcMs - first.capturedUtcMs
}

/**
 * Дневник питания на дневнике (diary.db, схема 9). Приём и его удаление — факты (author, epoch, aseq, uid), как подходы:
 * пишутся одной транзакцией с номером из общего счётчика фактов и уходят на второй телефон обменом.
 * Минут и очков характеристик питание не даёт. Заметка задаётся только при создании приёма.
 */
object Nutrition {
    /**
     * Записывает свой приём [uid], снятый в момент [time] (доверенное время и учётный пояс). [hasPhoto] — файл
     * [NutritionMath.photoNameFor] уже лежит в filesDir/meals. Повтор с тем же uid ничего не меняет.
     */
    fun addMeal(uid: String, time: TimeSnapshot, hasPhoto: Boolean, note: String?): Unit = synchronized(Diary) {
        require(NutritionMath.isSafeUid(uid)) { "Недопустимый uid приёма" }
        Diary.write { db ->
            if (exists(db, "meals", uid)) return@write
            db.insertOrThrow("meals", null, ContentValues().apply {
                put("uid", uid)
                put("author", Ledger.deviceId())
                put("epoch", Diary.factsEpoch())
                put("aseq", Diary.nextFactSeq())
                put("captured_utc_ms", time.utcMs)
                put("accounting_day", time.day)
                put("zone_id", time.zone.id)
                if (hasPhoto) put("photo_name", NutritionMath.photoNameFor(uid)) else putNull("photo_name")
                val n = NutritionMath.normalizeNote(note)
                if (n != null) put("note", n) else putNull("note")
                put("deleted", if (isDeleted(db, uid)) 1 else 0)
            })
        }
        Store.notifyChanged()
    }

    /** Удаляет приём [mealUid]: отдельный факт удаления (уходит на второй телефон), приём помечается удалённым. */
    fun deleteMeal(mealUid: String, time: TimeSnapshot = TrustedClock.snapshot()): Unit = synchronized(Diary) {
        Diary.write { db ->
            if (isDeleted(db, mealUid)) return@write
            db.insertOrThrow("meal_deletes", null, ContentValues().apply {
                put("uid", java.util.UUID.randomUUID().toString())
                put("meal_uid", mealUid)
                put("author", Ledger.deviceId())
                put("epoch", Diary.factsEpoch())
                put("aseq", Diary.nextFactSeq())
                put("utc_ms", time.utcMs)
                put("day", time.day)
            })
            db.execSQL("UPDATE meals SET deleted = 1 WHERE uid = ?", arrayOf<Any>(mealUid))
        }
        Store.notifyChanged()
    }

    /** Неудалённые приёмы учётных суток [day] по времени снимка. */
    fun mealsForDay(day: Long): List<MealItem> = mealsInRange(day, day)

    /** Неудалённые приёмы учётных суток [fromDay]..[toDay] по времени снимка. */
    fun mealsInRange(fromDay: Long, toDay: Long): List<MealItem> = synchronized(Diary) {
        Diary.query(
            "SELECT uid, author, captured_utc_ms, accounting_day, zone_id, photo_name, note FROM meals " +
                "WHERE accounting_day BETWEEN ? AND ? AND deleted = 0 ORDER BY captured_utc_ms, uid",
            fromDay.toString(), toDay.toString(),
        ) {
            MealItem(
                uid = it.getString(0), author = it.getString(1), capturedUtcMs = it.getLong(2), day = it.getLong(3),
                zoneId = it.getString(4), photoName = if (it.isNull(5)) null else it.getString(5),
                note = if (it.isNull(6)) null else it.getString(6),
            )
        }
    }

    /** Имена файлов фото неудалённых приёмов (для резервной копии). */
    fun livePhotoNames(): Set<String> = synchronized(Diary) {
        Diary.query("SELECT photo_name FROM meals WHERE deleted = 0 AND photo_name IS NOT NULL") { it.getString(0) }
            .filter(NutritionMath::isSafePhotoName).toSet()
    }

    /** Имена файлов фото удалённых приёмов: файлы можно стереть. */
    fun deletedPhotoNames(): Set<String> = synchronized(Diary) {
        Diary.query("SELECT photo_name FROM meals WHERE deleted = 1 AND photo_name IS NOT NULL") { it.getString(0) }
            .filter(NutritionMath::isSafePhotoName).toSet()
    }

    /** Строка для карточки дня в «Прогрессе»; null — приёмов не было. */
    fun dayLine(day: Long): String? = NutritionMath.dayLine(mealsForDay(day))

    // ---------- Для обмена (DiarySyncStore) ----------

    /** Вставляет копию приёма со второго телефона; дубль по uid пропускается. true — вставлен. */
    internal fun insertMealCopy(db: SQLiteDatabase, uid: String, author: String, epoch: String, aseq: Long,
                                capturedUtcMs: Long, day: Long, zoneId: String, hasPhoto: Boolean, note: String?): Boolean {
        if (exists(db, "meals", uid)) return false
        db.insertOrThrow("meals", null, ContentValues().apply {
            put("uid", uid); put("author", author); put("epoch", epoch); put("aseq", aseq)
            put("captured_utc_ms", capturedUtcMs); put("accounting_day", day); put("zone_id", zoneId)
            if (hasPhoto) put("photo_name", NutritionMath.photoNameFor(uid)) else putNull("photo_name")
            val n = NutritionMath.normalizeNote(note)
            if (n != null) put("note", n) else putNull("note")
            put("deleted", if (isDeleted(db, uid)) 1 else 0)
        })
        return true
    }

    /** Вставляет копию удаления; дубль по uid пропускается. Приём (если уже есть) помечается удалённым. */
    internal fun insertDeleteCopy(db: SQLiteDatabase, uid: String, mealUid: String, author: String, epoch: String,
                                  aseq: Long, day: Long, utcMs: Long): Boolean {
        if (exists(db, "meal_deletes", uid)) return false
        db.insertOrThrow("meal_deletes", null, ContentValues().apply {
            put("uid", uid); put("meal_uid", mealUid); put("author", author); put("epoch", epoch); put("aseq", aseq)
            put("utc_ms", utcMs); put("day", day)
        })
        db.execSQL("UPDATE meals SET deleted = 1 WHERE uid = ?", arrayOf<Any>(mealUid))
        return true
    }

    private fun exists(db: SQLiteDatabase, table: String, uid: String): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE uid = ? LIMIT 1", arrayOf(uid)).use { it.moveToFirst() }

    private fun isDeleted(db: SQLiteDatabase, mealUid: String): Boolean =
        db.rawQuery("SELECT 1 FROM meal_deletes WHERE meal_uid = ? LIMIT 1", arrayOf(mealUid)).use { it.moveToFirst() }
}

/** Чистая логика дневника питания, без Android: её проверяют юнит-тесты. */
object NutritionMath {
    /** Предел заметки (символов). */
    const val MAX_NOTE_CHARS = 140

    /** Длинная сторона сохранённого фото, пикселей. */
    const val PHOTO_MAX_SIDE = 1600

    /** Качество JPEG сохранённого фото. */
    const val PHOTO_QUALITY = 85

    /** Каталог фото в filesDir и в архиве резервной копии. */
    const val PHOTO_DIR = "meals"

    private val SAFE_UID = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")
    private val SAFE_PHOTO = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,63}\\.jpg$")
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** uid годится в имя файла: латиница, цифры и дефис, без точек и разделителей пути. */
    fun isSafeUid(uid: String): Boolean = SAFE_UID.matches(uid)

    fun photoNameFor(uid: String): String = "$uid.jpg"

    /** Имя файла фото безопасно: только «uid.jpg» из [isSafeUid] — без каталогов, «..» и обратных косых. */
    fun isSafePhotoName(name: String): Boolean = SAFE_PHOTO.matches(name)

    /**
     * Имя файла фото из записи архива копии: только «meals/<uid>.jpg» ровно на один уровень. Любое другое
     * (абсолютный путь, «..», вложенные каталоги, обратная косая) — null: такая запись на диск не пишется.
     */
    fun backupPhotoName(entryName: String): String? {
        val prefix = "$PHOTO_DIR/"
        if (!entryName.startsWith(prefix)) return null
        val name = entryName.substring(prefix.length)
        return name.takeIf(::isSafePhotoName)
    }

    /** Запись архива копии для фото [photoName]. */
    fun backupEntryFor(photoName: String): String = "$PHOTO_DIR/$photoName"

    /** Заметка: пробелы и переводы строк — одним пробелом, не длиннее предела; пустая — null. */
    fun normalizeNote(raw: String?): String? {
        val s = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return if (s.isEmpty()) null else s.take(MAX_NOTE_CHARS)
    }

    /** Приёмы без удалённых: [deletedMealUids] — uid из журнала удалений. */
    fun visible(meals: List<MealItem>, deletedMealUids: Set<String>): List<MealItem> =
        meals.filter { it.uid !in deletedMealUids }

    /** Окно питания по приёмам одних учётных суток; null — приёмов нет. */
    fun window(meals: List<MealItem>): MealWindow? {
        if (meals.isEmpty()) return null
        val sorted = meals.sortedWith(compareBy({ it.capturedUtcMs }, { it.uid }))
        return MealWindow(sorted.first(), sorted.last(), sorted.size)
    }

    /** «08:40» — время снимка в учётном поясе, где он сделан. */
    fun timeText(utcMs: Long, zoneId: String): String {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC"))
        return HM.format(Instant.ofEpochMilli(utcMs).atZone(zone))
    }

    /** «11 ч 35 мин», «2 ч», «45 мин». */
    fun durationText(ms: Long): String {
        val totalMin = (ms.coerceAtLeast(0L) / 60_000L)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    /** «08:40 – 20:15 · 11 ч 35 мин»; один приём — «Один приём»; нет приёмов — null. */
    fun windowText(meals: List<MealItem>): String? {
        val w = window(meals) ?: return null
        if (w.count == 1) return "Один приём"
        return "${timeText(w.first.capturedUtcMs, w.first.zoneId)} – ${timeText(w.last.capturedUtcMs, w.last.zoneId)} · " +
            durationText(w.durationMs)
    }

    fun mealsWord(n: Int): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> "приёмов"
            m10 == 1 -> "приём"
            m10 in 2..4 -> "приёма"
            else -> "приёмов"
        }
    }

    /** Карточка дня «Прогресса»: «Питание: 3 приёма · окно 08:40 – 20:15 · 11 ч 35 мин», «Питание: 1 приём»; пусто — null. */
    fun dayLine(meals: List<MealItem>): String? {
        val w = window(meals) ?: return null
        val count = "Питание: ${w.count} ${mealsWord(w.count)}"
        if (w.count == 1) return count
        return "$count · окно ${windowText(meals)}"
    }

    /** Карточка на главной: «Сегодня: 3 приёма · 08:40 – 20:15 · 11 ч 35 мин», «Сегодня: 1 приём», «Сегодня приёмов нет». */
    fun homeLine(meals: List<MealItem>): String {
        val w = window(meals) ?: return "Сегодня приёмов нет"
        val count = "Сегодня: ${w.count} ${mealsWord(w.count)}"
        if (w.count == 1) return count
        return "$count · ${windowText(meals)}"
    }

    // ---------- Фото: размер и поворот ----------

    /** Размер после уменьшения длинной стороны до [maxSide] (меньшие не увеличиваются). */
    fun scaledSize(width: Int, height: Int, maxSide: Int = PHOTO_MAX_SIDE): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxSide || long <= 0) return width to height
        val k = maxSide.toDouble() / long
        return maxOf(1, Math.round(width * k).toInt()) to maxOf(1, Math.round(height * k).toInt())
    }

    /** inSampleSize (степень двойки) для декодирования: длинная сторона остаётся не меньше [minSide]. */
    fun sampleSize(width: Int, height: Int, minSide: Int): Int {
        var s = 1
        val long = maxOf(width, height)
        while (long / (s * 2) >= minSide) s *= 2
        return s
    }

    /**
     * Поворот (градусы по часовой) и зеркалирование по горизонтали для значения EXIF Orientation (1–8).
     * Неизвестное или 0 — (0, false). Зеркалирование применяется до поворота.
     */
    fun exifTransform(orientation: Int): Pair<Int, Boolean> = when (orientation) {
        2 -> 0 to true
        3 -> 180 to false
        4 -> 180 to true
        5 -> 270 to true // TRANSPOSE
        6 -> 90 to false
        7 -> 90 to true // TRANSVERSE
        8 -> 270 to false
        else -> 0 to false
    }
}
