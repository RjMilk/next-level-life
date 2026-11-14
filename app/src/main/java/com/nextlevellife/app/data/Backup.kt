package com.nextlevellife.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Итог восстановления из копии. */
sealed interface RestoreResult {
    /**
     * Данные заменены. [createdAtMs] и [sourceDeviceId] — из манифеста копии. [carriedMs] — сколько расхода,
     * сделанного после копии, перенесено в восстановленный журнал ([OpKind.RESTORE_CARRY]).
     */
    data class Ok(val createdAtMs: Long, val sourceDeviceId: String?, val carriedMs: Long = 0L) : RestoreResult

    /** Ничего не изменено; [reason] — понятная человеку причина. */
    data class Error(val reason: String) : RestoreResult
}

/**
 * Резервная копия: zip из diary.db (согласованный снимок), prefs.json (настройки, блокируемые
 * приложения, серия, рекорды), manifest.json (версия формата, версия приложения, время, device_id) и фото
 * приёмов пищи каталогом meals/ (<uid>.jpg). Записи движений (poselog) в копию не входят. Выбор файла — забота экрана: здесь только потоки.
 */
object Backup {
    private const val TAG = "Backup"
    private const val MAX_DB_BYTES = 512L * 1024 * 1024
    private const val MAX_JSON_BYTES = 4L * 1024 * 1024

    /**
     * Пишет копию в [out] (поток не закрывается — это делает вызывающий).
     * Снимок базы делается под блокировкой [Diary], так что запись в базу во время снимка невозможна.
     * Ошибки (IOException, SQLiteException) пробрасываются.
     */
    fun export(context: Context, out: OutputStream) {
        Store.init(context)
        val tmp = File(context.cacheDir, "backup-${UUID.randomUUID()}.db")
        try {
            snapshotDb(context, tmp)
            val manifest = JSONObject()
                .put(BackupMath.KEY_FORMAT, BackupMath.FORMAT_VERSION)
                .put(BackupMath.KEY_DB_SCHEMA, Diary.SCHEMA_VERSION)
                .put("app_version", appVersionName(context))
                .put("app_version_code", appVersionCode(context))
                .put("created_at_ms", TrustedClock.nowMs()) // доверенное время, не системные часы
                .put("device_id", Ledger.deviceId())
            val prefs = Store.exportPrefs()

            val zip = ZipOutputStream(out)
            zip.putNextEntry(ZipEntry(BackupMath.ENTRY_MANIFEST))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupMath.ENTRY_PREFS))
            zip.write(prefs.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupMath.ENTRY_DB))
            tmp.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            // Фото приёмов пищи — каталогом meals/: только неудалённых приёмов этой базы, файлы которых есть здесь.
            // JPEG уже сжат: без повторного сжатия (STORED требует CRC заранее — проще DEFLATE с уровнем 0).
            // Список фото — из того же снимка базы, что попадает в копию (а не из текущей базы, где приём могли
            // удалить уже после снимка): каждому приёму архива — его фото, если файл ещё есть.
            val photos = runCatching {
                SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT photo_name FROM meals WHERE deleted = 0 AND photo_name IS NOT NULL", null).use { c ->
                        buildSet { while (c.moveToNext()) add(c.getString(0)) }
                    }
                }.filter(NutritionMath::isSafePhotoName).toSet()
            }.getOrElse { runCatching { Nutrition.livePhotoNames() }.getOrDefault(emptySet()) }
            val dir = File(context.filesDir, NutritionMath.PHOTO_DIR)
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            for (name in photos.sorted()) {
                val f = File(dir, name)
                if (!f.isFile) continue
                zip.putNextEntry(ZipEntry(NutritionMath.backupEntryFor(name)))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
        } finally {
            deleteDbFiles(tmp)
        }
    }

    /**
     * Восстанавливает данные из копии. Сначала всё читается и проверяется во временной папке;
     * текущие данные меняются только после этого, а при сбое подмены возвращаются как были.
     * Идентификатор этой установки (device_id) и якорь доверенных часов (номер загрузки, якорь,
     * last_trusted_ms) сохраняются прежними. Учётный часовой пояс остаётся здешним; другой пояс копии
     * ставится обычной отложенной сменой на 24 часа ([TrustedClockMath.restoredMeta]).
     * Расход, сделанный после копии (его нет в журнале копии), переносится в восстановленный журнал
     * ([Ledger.appendRestoreCarry]): «сделал копию → потратил → восстановил» минуты не возвращает.
     */
    fun restore(context: Context, input: InputStream): RestoreResult {
        Store.init(context)
        val work = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            // 1. Распаковка: только известные файлы, с ограничением размера.
            val dbTmp = File(work, BackupMath.ENTRY_DB)
            var manifestText: String? = null
            var prefsText: String? = null
            var hasDb = false
            val photosTmp = File(work, NutritionMath.PHOTO_DIR)
            var photosBytes = 0L
            try {
                ZipInputStream(input).let { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        when (entry.name) {
                            BackupMath.ENTRY_MANIFEST -> {
                                if (manifestText != null) return RestoreResult.Error("В копии два манифеста")
                                manifestText = readLimited(zip, MAX_JSON_BYTES).toString(Charsets.UTF_8)
                            }
                            BackupMath.ENTRY_PREFS -> {
                                if (prefsText != null) return RestoreResult.Error("В копии два файла настроек")
                                prefsText = readLimited(zip, MAX_JSON_BYTES).toString(Charsets.UTF_8)
                            }
                            BackupMath.ENTRY_DB -> {
                                if (hasDb) return RestoreResult.Error("В копии две базы")
                                dbTmp.outputStream().use { copyLimited(zip, it, MAX_DB_BYTES) }
                                hasDb = true
                            }
                            else -> {
                                // Фото приёма: только «meals/<uid>.jpg» ровно на один уровень (без «..» и каталогов),
                                // во временную папку; остальные чужие файлы пропускаем, на диск не пишем.
                                val photo = NutritionMath.backupPhotoName(entry.name)
                                if (photo != null && !entry.isDirectory) {
                                    val dest = File(photosTmp.apply { mkdirs() }, photo)
                                    if (dest.exists()) return RestoreResult.Error("В копии два одинаковых фото")
                                    val n = dest.outputStream().use { copyLimited(zip, it, BackupMath.MAX_PHOTO_BYTES) }
                                    photosBytes += n
                                    if (photosBytes > BackupMath.MAX_PHOTOS_TOTAL_BYTES) return RestoreResult.Error("Фото в копии слишком много")
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Не удалось прочитать копию", e)
                return RestoreResult.Error("Файл не читается как резервная копия: ${e.message ?: "ошибка чтения"}")
            }

            // 2. Манифест.
            val manifestRaw = manifestText ?: return RestoreResult.Error("Это не резервная копия Next Level Life: нет manifest.json")
            val manifest = runCatching { JSONObject(manifestRaw) }.getOrNull()
                ?: return RestoreResult.Error("Манифест копии повреждён")
            val format = if (manifest.has(BackupMath.KEY_FORMAT)) manifest.optInt(BackupMath.KEY_FORMAT, -1) else null
            val schema = if (manifest.has(BackupMath.KEY_DB_SCHEMA)) manifest.optInt(BackupMath.KEY_DB_SCHEMA, -1) else null
            BackupMath.checkManifest(format, schema, Diary.SCHEMA_VERSION)?.let { return RestoreResult.Error(it) }
            if (!hasDb) return RestoreResult.Error("В копии нет базы дневника")
            val prefsRaw = prefsText ?: return RestoreResult.Error("В копии нет настроек")

            // 3. Настройки разбираются заранее: подмена начнётся, только если они в порядке.
            val newPrefsJson = try {
                JSONObject(prefsRaw)
            } catch (e: Exception) {
                Log.e(TAG, "Настройки копии повреждены", e)
                return RestoreResult.Error("Настройки в копии повреждены")
            }
            val newPrefs = try {
                Store.parsePrefs(newPrefsJson)
            } catch (e: Exception) {
                Log.e(TAG, "Настройки копии повреждены", e)
                return RestoreResult.Error("Настройки в копии повреждены")
            }

            // 4. База из копии: целостность, версия схемы, нужные таблицы.
            checkDbFile(dbTmp, schema!!)?.let { return RestoreResult.Error(it) }

            // 5. Подмена.
            val carried = LongArray(1)
            // Очередь записи событий экрана — на паузу до подмены: граница восстановленной истории —
            // последнее событие восстановленной базы, и пропуск от неё пишется первым, до новых событий.
            // Пауза не подтверждена — подмены нет: текущие данные не трогаются.
            val pause = com.nextlevellife.app.sleep.PhoneEvents.pauseForRestore()
                ?: return RestoreResult.Error("Запись событий экрана занята, восстановление не начато. Текущие данные сохранены, попробуйте ещё раз.")
            var replaced = false
            try {
                return swap(context, dbTmp, newPrefs, newPrefsJson) { carried[0] = it }.let { error ->
                    replaced = error == null
                    if (replaced) installPhotos(context, photosTmp)
                    if (error != null) RestoreResult.Error(error)
                    else RestoreResult.Ok(manifest.optLong("created_at_ms", 0L), manifest.optString("device_id").ifEmpty { null }, carried[0])
                }
            } finally {
                pause.resume(databaseReplaced = replaced)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Восстановление не удалось", e)
            return RestoreResult.Error("Восстановление не удалось: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            work.deleteRecursively()
        }
    }

    // ---------- Снимок ----------

    private fun snapshotDb(context: Context, dest: File) {
        deleteDbFiles(dest)
        synchronized(Diary) {
            val db = Diary.db()
            check(!db.inTransaction()) { "Снимок нельзя делать внутри транзакции" }
            if (Build.VERSION.SDK_INT >= 30) {
                // SQLite 3.27+: согласованная и сжатая копия одной командой.
                db.execSQL("VACUUM INTO '${dest.absolutePath.replace("'", "''")}'")
            } else {
                // Android 10: сбросить WAL в основной файл и скопировать его, не отпуская блокировку.
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                    check(!c.moveToFirst() || c.getInt(0) == 0) { "База занята, снимок не сделан" }
                }
                Diary.dbFile(context).inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
        check(dest.length() > 0) { "Снимок базы пуст" }
    }

    // ---------- Проверка базы из копии ----------

    /** null — база годится; иначе причина. */
    private fun checkDbFile(file: File, manifestSchema: Int): String? {
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val check = db.rawQuery("PRAGMA quick_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (check != "ok") return "База в копии повреждена"
                BackupMath.checkDbVersion(db.version, manifestSchema, Diary.SCHEMA_VERSION)?.let { return it }
                val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                    buildSet { while (c.moveToNext()) add(c.getString(0)) }
                }
                val missing = BackupMath.requiredTables(db.version) - tables
                if (missing.isNotEmpty()) return "В базе копии нет таблиц: ${missing.joinToString()}"
                val accountRows = db.rawQuery("SELECT COUNT(*) FROM account", null).use { c -> c.moveToFirst(); c.getInt(0) }
                if (accountRows != 1) return "В базе копии нет итога счёта"
                val consistent = db.rawQuery(
                    "SELECT (SELECT balance_ms FROM account WHERE id = 1) = (SELECT COALESCE(SUM(delta_ms), 0) FROM balance_ops) " +
                        "AND (SELECT xp FROM account WHERE id = 1) = (SELECT COALESCE(SUM(delta_xp), 0) FROM balance_ops)",
                    null,
                ).use { c -> c.moveToFirst() && c.getInt(0) != 0 }
                if (!consistent) return "В копии баланс не сходится с журналом операций"
            }
        } catch (e: Exception) {
            Log.e(TAG, "База копии не открывается", e)
            return "База в копии не открывается"
        } finally {
            // Открытие могло перевести файл в WAL: после закрытия всё уже в основном файле.
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
            File(file.path + "-journal").delete()
        }
        return null
    }

    // ---------- Подмена ----------

    private val SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    private const val BAK = ".restore-bak"

    private const val STAGING = ".restore-new"
    private const val MARKER = ".restore-marker"
    private const val MARKER_OLD_PREFS = "old_prefs"
    private const val MARKER_NEW_PREFS = "new_prefs"

    private fun markerFile(dbFile: File) = File(dbFile.path + MARKER)

    /**
     * Заменяет базу и настройки. null — успех; иначе причина, а данные возвращены как были.
     *
     * Порядок рассчитан на остановку процесса в любой момент: до первого переноса пишется маркер
     * (с прежними и новыми настройками), и следующий запуск по нему доводит или откатывает подмену
     * ([recoverInterruptedRestore]). Основной файл базы переносится в `.restore-bak` первым, а новая
     * база кладётся на место одним переименованием последней, так что по наличию файлов видно стадию.
     */
    private fun swap(
        context: Context, newDb: File, newPrefs: Store.PrefsSnapshot, newPrefsJson: JSONObject,
        onCarried: (Long) -> Unit,
    ): String? = synchronized(Diary) {
        val dbFile = Diary.dbFile(context)
        val localDeviceId = runCatching { Ledger.deviceId() }.getOrNull()
        // Якорь доверенных часов, номер загрузки и учётный пояс — здешние; пояс копии — отложенной сменой.
        val localClockMeta = runCatching { TrustedClock.localMetaForRestore() }.getOrNull()
        val nowMs = TrustedClock.nowMs()
        val zone = runCatching { TrustedClock.zone() }.getOrElse { java.time.ZoneId.systemDefault() }
        // Расход текущего журнала: то, чего нет в копии, переносится. Журнал не читается (база испорчена) —
        // восстановление не запрещаем: ради такого случая копия и делается; переноса тогда нет.
        val currentSpent = try {
            Ledger.spentRowsForCarry()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать расход текущего журнала: восстановление без переноса расхода", e)
            null
        }
        // Исходящие переносы на второй телефон: восстановление не должно вернуть отданные минуты.
        val currentTransfers = try {
            Ledger.transfersForCarry()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать переносы текущего журнала", e)
            emptyList()
        }
        // Связь с вторым телефоном — здешняя: копия её не переносит.
        val localPeer = runCatching { Ledger.peerId() }.getOrNull()
        // Источник активности пары — тоже здешний: копия со старым источником позволила бы оплатить шаги дважды.
        val localSource = runCatching {
            synchronized(Diary) {
                Diary.query("SELECT value, version, author FROM shared_settings WHERE key = ?", SharedSettings.ACTIVITY_SOURCE) { c ->
                    android.content.ContentValues().apply {
                        put("key", SharedSettings.ACTIVITY_SOURCE); put("value", c.getString(0))
                        put("version", c.getLong(1)); put("author", c.getString(2))
                    }
                }.firstOrNull()
            }
        }.getOrNull()
        val localPairMeta = runCatching {
            synchronized(Diary) { listOf(Ledger.KEY_PAIRED_DAY).associateWith { Diary.metaGet(it) } }
        }.getOrDefault(emptyMap())
        // Переносы доли дневного лимита (отданные и полученные) — тоже: без них восстановление вернуло бы отданную
        // долю (свои переносы с другого телефона не принимаются) или потеряло полученную.
        val currentMoves = try {
            synchronized(Diary) {
                Diary.query("SELECT uid, author, epoch, aseq, to_device, day, ms, created_utc_ms FROM limit_moves") { c ->
                    android.content.ContentValues().apply {
                        put("uid", c.getString(0)); put("author", c.getString(1)); put("epoch", c.getString(2))
                        put("aseq", c.getLong(3)); put("to_device", c.getString(4)); put("day", c.getLong(5))
                        put("ms", c.getLong(6)); put("created_utc_ms", c.getLong(7))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать переносы доли лимита", e)
            emptyList()
        }
        // Приёмы пищи и их удаления текущей базы — тоже: приёмы не дают наград (переносить безопасно), фото их
        // остаются в filesDir, а удаление после копии не должно воскресить приём.
        val currentMeals = try {
            synchronized(Diary) {
                Diary.query(
                    "SELECT uid, author, epoch, aseq, captured_utc_ms, accounting_day, zone_id, photo_name, note FROM meals",
                ) { c ->
                    android.content.ContentValues().apply {
                        put("uid", c.getString(0)); put("author", c.getString(1)); put("epoch", c.getString(2))
                        put("aseq", c.getLong(3)); put("captured_utc_ms", c.getLong(4)); put("accounting_day", c.getLong(5))
                        put("zone_id", c.getString(6))
                        if (c.isNull(7)) putNull("photo_name") else put("photo_name", c.getString(7))
                        if (c.isNull(8)) putNull("note") else put("note", c.getString(8))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать приёмы пищи", e)
            emptyList()
        }
        val currentMealDeletes = try {
            synchronized(Diary) {
                Diary.query("SELECT uid, meal_uid, author, epoch, aseq, utc_ms, day FROM meal_deletes") { c ->
                    android.content.ContentValues().apply {
                        put("uid", c.getString(0)); put("meal_uid", c.getString(1)); put("author", c.getString(2))
                        put("epoch", c.getString(3)); put("aseq", c.getLong(4)); put("utc_ms", c.getLong(5)); put("day", c.getLong(6))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать удаления приёмов пищи", e)
            emptyList()
        }
        // Ответы на карточки текущей базы — тоже: оплаченные попытки и израсходованный потолок дня должны пережить
        // восстановление (иначе карточку можно было бы оплатить второй раз), а расписание повторений — не откатиться.
        val currentCardAttempts = try {
            synchronized(Diary) {
                Diary.query(
                    "SELECT uid, author, epoch, aseq, card_id, deck, utc_ms, accounting_day, zone_id, first_correct, paid_ms " +
                        "FROM card_attempts",
                ) { c ->
                    android.content.ContentValues().apply {
                        put("uid", c.getString(0)); put("author", c.getString(1)); put("epoch", c.getString(2))
                        put("aseq", c.getLong(3)); put("card_id", c.getString(4)); put("deck", c.getString(5))
                        put("utc_ms", c.getLong(6)); put("accounting_day", c.getLong(7)); put("zone_id", c.getString(8))
                        put("first_correct", c.getInt(9)); put("paid_ms", c.getLong(10))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать ответы на карточки", e)
            emptyList()
        }
        var carriedMs = 0L
        val oldPrefsJson = Store.exportPrefs()
        val oldPrefs = Store.parsePrefs(oldPrefsJson)
        val staging = File(dbFile.path + STAGING)
        val marker = markerFile(dbFile)
        // Каждый успешный шаг отмечается, чтобы откат трогал только то, что действительно сделано.
        val moved = ArrayList<Pair<File, File>>()
        var installed = false
        var prefsWritten = false

        // Новая база готовится целиком до остановки текущей: копия, device_id этой установки,
        // здешние часы и пояс, перенос расхода после копии.
        try {
            staging.delete()
            newDb.inputStream().use { input -> staging.outputStream().use { input.copyTo(it) } }
            SQLiteDatabase.openDatabase(staging.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // Старая схема копии догоняет текущую до запросов переноса (они читают новые столбцы).
                // Авторство старых строк — device_id копии: миграция выполняется до записи здешнего device_id.
                migrateStaging(db)
                fun putMeta(key: String, value: String) {
                    db.insertWithOnConflict(
                        "meta", null,
                        android.content.ContentValues().apply {
                            put("key", key)
                            put("value", value)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                if (localDeviceId != null) putMeta(Ledger.KEY_DEVICE_ID, localDeviceId)
                // Новые поколения журнала и фактов: второй телефон заберёт журнал заново, а потерянные подходы
                // вернёт по старым поколениям. Курсоры и копия журнала второго телефона — заново.
                putMeta(Diary.KEY_OPS_EPOCH, UUID.randomUUID().toString())
                putMeta(Diary.KEY_FACTS_EPOCH, UUID.randomUUID().toString())
                putMeta(Diary.KEY_FACTS_SEQ, "0")
                db.delete("sync_cursors", null, null)
                db.delete("peer_ops", null, null)
                for (move in currentMoves) db.insertWithOnConflict("limit_moves", null, move, SQLiteDatabase.CONFLICT_IGNORE)
                for (meal in currentMeals) db.insertWithOnConflict("meals", null, meal, SQLiteDatabase.CONFLICT_IGNORE)
                for (del in currentMealDeletes) db.insertWithOnConflict("meal_deletes", null, del, SQLiteDatabase.CONFLICT_IGNORE)
                db.execSQL("UPDATE meals SET deleted = 1 WHERE uid IN (SELECT meal_uid FROM meal_deletes)")
                for (a in currentCardAttempts) db.insertWithOnConflict("card_attempts", null, a, SQLiteDatabase.CONFLICT_IGNORE)
                if (localPeer != null) putMeta(Ledger.KEY_SYNC_PEER, localPeer) else db.delete("meta", "key = ?", arrayOf(Ledger.KEY_SYNC_PEER))
                db.delete("shared_settings", "key = ?", arrayOf(SharedSettings.ACTIVITY_SOURCE))
                if (localSource != null) db.insertWithOnConflict("shared_settings", null, localSource, SQLiteDatabase.CONFLICT_REPLACE)
                // Копия журнала второго телефона стёрта выше — до следующего обмена она не полная.
                db.delete("meta", "key = ?", arrayOf(Ledger.KEY_PEER_COMPLETE))
                for ((key, value) in localPairMeta) {
                    if (value != null) putMeta(key, value) else db.delete("meta", "key = ?", arrayOf(key))
                }
                val backupMeta = HashMap<String, String>()
                db.rawQuery("SELECT key, value FROM meta", null).use { c ->
                    while (c.moveToNext()) backupMeta[c.getString(0)] = c.getString(1)
                }
                if (localClockMeta != null) {
                    for ((key, value) in TrustedClockMath.restoredMeta(localClockMeta, backupMeta, nowMs)) {
                        if (value != null) putMeta(key, value) else db.delete("meta", "key = ?", arrayOf(key))
                    }
                }
                if (currentSpent != null) {
                    val deviceId = localDeviceId ?: backupMeta[Ledger.KEY_DEVICE_ID] ?: "restore"
                    carriedMs = Ledger.appendRestoreCarry(db, currentSpent, deviceId, zone, nowMs, currentTransfers)
                    if (carriedMs > 0) Log.i(TAG, "Перенесён расход после копии: $carriedMs мс")
                }
            }
            for (s in listOf("-wal", "-shm", "-journal")) File(staging.path + s).delete()
            // Остатки прошлых попыток мешают понять стадию по файлам — убираем заранее.
            for (suffix in SUFFIXES) {
                val bak = File(dbFile.path + suffix + BAK)
                if (bak.exists() && !bak.delete()) throw IOException("Не удалось убрать старый ${bak.name}")
            }
            writeMarker(marker, JSONObject().put(MARKER_OLD_PREFS, oldPrefsJson).put(MARKER_NEW_PREFS, newPrefsJson))
        } catch (e: Exception) {
            Log.e(TAG, "Подготовка подмены не удалась", e)
            staging.delete()
            marker.delete()
            return@synchronized "Не удалось заменить данные: ${e.message ?: e.javaClass.simpleName}. Текущие данные сохранены."
        }

        // Расчёты, начатые по прежней базе, не должны записать свой результат в новую.
        Diary.bumpGeneration()
        Diary.close()
        try {
            // Старые файлы — в сторону (не удаляем, пока новая база не открылась). Основной — первым.
            for (suffix in SUFFIXES) {
                val f = File(dbFile.path + suffix)
                if (!f.exists()) continue
                val bak = File(f.path + BAK)
                if (!f.renameTo(bak)) throw IOException("Не удалось отложить ${f.name}")
                moved += f to bak
            }
            if (!staging.renameTo(dbFile)) throw IOException("Не удалось положить базу на место")
            installed = true

            Diary.reopen()
            Ledger.onDatabaseReplaced()
            if (!Ledger.isConsistent()) throw IllegalStateException("Баланс не сходится с журналом после подмены")
            // Кеш дней из копии не знает о перенесённых ответах на карточки — эти дни пересчитать («Знание»).
            val cardDays = currentCardAttempts.mapNotNull { it.getAsLong("accounting_day") }.toSet()
            runCatching { com.nextlevellife.app.stats.StatsRepository.invalidateDays(cardDays) }
                .onFailure { Log.e(TAG, "Не удалось пересчитать дни с карточками", it) }

            prefsWritten = true
            if (!Store.commitPrefs(newPrefs)) throw IOException("Не удалось записать настройки")

            // Основной .restore-bak удаляется первым: без него запуск считает подмену завершённой.
            moved.forEach { (_, bak) -> bak.delete() }
            marker.delete()
            // События экрана после снимка копии потеряны: пропуск запишет очередь PhoneEvents при снятии паузы (restore).
            Store.notifyChanged()
            onCarried(carriedMs)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Подмена не удалась, возвращаю прежние данные", e)
            runCatching { Diary.close() }
            // Файлы по исходным путям удаляются, только если новая база уже была установлена:
            // тогда все прежние файлы лежат в .restore-bak, а по исходным путям — только новые.
            // До установки там могут быть ещё не перенесённые прежние файлы — их не трогаем.
            if (installed) for (suffix in SUFFIXES) File(dbFile.path + suffix).delete()
            staging.delete()
            var restoredAll = true
            for ((orig, bak) in moved) {
                if (!bak.renameTo(orig)) {
                    restoredAll = false
                    Log.e(TAG, "Не удалось вернуть ${orig.name}")
                }
            }
            if (prefsWritten) runCatching { Store.commitPrefs(oldPrefs) }
            if (restoredAll) {
                marker.delete()
                runCatching { Diary.reopen() }
            } else {
                // Базу не открываем: иначе на месте прежней появится пустая. Маркер остаётся —
                // следующий запуск повторит возврат (recoverInterruptedRestore).
                Log.e(TAG, "Прежние данные возвращены не полностью; повтор при следующем запуске")
            }
            Ledger.onDatabaseReplaced()
            Store.notifyChanged()
            "Не удалось заменить данные: ${e.message ?: e.javaClass.simpleName}. Текущие данные сохранены."
        }
    }

    /** Доводит базу копии [db] до текущей схемы теми же шагами, что и [Diary] (одной транзакцией). */
    private fun migrateStaging(db: SQLiteDatabase) {
        val from = db.version
        if (from >= Diary.SCHEMA_VERSION) return
        db.beginTransaction()
        try {
            for (v in from until Diary.SCHEMA_VERSION) Diary.MIGRATIONS[v].forEach(db::execSQL)
            db.version = Diary.SCHEMA_VERSION
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Пишет маркер надёжно: во временный файл с fsync, затем переименованием. */
    private fun writeMarker(marker: File, content: JSONObject) {
        val tmp = File(marker.path + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(content.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(marker)) {
            tmp.delete()
            throw IOException("Не удалось записать маркер восстановления")
        }
    }

    // ---------- Прерванное восстановление ----------

    /**
     * Доводит или откатывает восстановление, прерванное остановкой процесса. Вызывается из [Store.init]
     * до открытия дневника: иначе на месте отложенной базы создалась бы пустая. Ошибки только логируются.
     */
    internal fun recoverInterruptedRestore(context: Context) {
        val dbFile = Diary.dbFile(context)
        val marker = markerFile(dbFile)
        if (!marker.exists()) return
        try {
            val markerJson = runCatching { JSONObject(marker.readText(Charsets.UTF_8)) }.getOrNull()
            val dbPresent = dbFile.exists()
            val bakPresent = File(dbFile.path + BAK).exists()
            val usable = dbPresent && bakPresent && isUsableDb(dbFile)
            val action = BackupMath.recoveryAction(markerPresent = true, dbPresent, bakPresent, usable)
            Log.w(TAG, "Найдено прерванное восстановление: $action (база ${dbPresent}, копия старой ${bakPresent}, целая $usable)")
            File(dbFile.path + STAGING).delete()
            when (action) {
                BackupMath.RestoreRecovery.NONE -> Unit
                BackupMath.RestoreRecovery.KEEP_CURRENT -> {
                    for (suffix in SUFFIXES) File(dbFile.path + suffix + BAK).delete()
                    marker.delete()
                }
                BackupMath.RestoreRecovery.KEEP_NEW -> {
                    val prefs = markerJson?.optJSONObject(MARKER_NEW_PREFS)
                    if (prefs != null) {
                        if (!Store.commitPrefs(Store.parsePrefs(prefs))) throw IOException("Не удалось записать настройки копии")
                    } else {
                        Log.e(TAG, "В маркере нет настроек копии: остаются прежние")
                    }
                    for (suffix in SUFFIXES) File(dbFile.path + suffix + BAK).delete()
                    marker.delete()
                }
                BackupMath.RestoreRecovery.ROLL_BACK -> {
                    // Основной файл на месте только после установки новой базы: тогда все прежние файлы
                    // уже в .restore-bak, а по исходным путям — новые (или их остатки).
                    if (dbPresent) for (suffix in SUFFIXES) File(dbFile.path + suffix).delete()
                    var restoredAll = true
                    for (suffix in SUFFIXES) {
                        val orig = File(dbFile.path + suffix)
                        val bak = File(orig.path + BAK)
                        if (!bak.exists() || orig.exists()) continue
                        if (!bak.renameTo(orig)) {
                            restoredAll = false
                            Log.e(TAG, "Не удалось вернуть ${orig.name}")
                        }
                    }
                    markerJson?.optJSONObject(MARKER_OLD_PREFS)?.let { Store.commitPrefs(Store.parsePrefs(it)) }
                    if (restoredAll) marker.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось разобрать прерванное восстановление", e)
        }
    }

    /** Новая база на месте открывается, цела и похожа на дневник. */
    private fun isUsableDb(file: File): Boolean = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val check = db.rawQuery("PRAGMA quick_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            check == "ok" &&
                db.version in BackupMath.MIN_DB_SCHEMA..Diary.SCHEMA_VERSION &&
                tables.containsAll(BackupMath.requiredTables(db.version)) &&
                db.rawQuery("SELECT COUNT(*) FROM account", null).use { c -> c.moveToFirst() && c.getInt(0) == 1 }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Новая база не открывается", e)
        false
    }

    // ---------- Мелочи ----------

    private fun deleteDbFiles(f: File) {
        for (suffix in SUFFIXES) File(f.path + suffix).delete()
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyLimited(input, out, limit)
        return out.toByteArray()
    }

    private fun copyLimited(input: InputStream, out: OutputStream, limit: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("Файл в копии слишком большой")
            out.write(buf, 0, n)
        }
        return total
    }

    /**
     * Фото приёмов из копии — в filesDir/meals после успешной подмены базы. Имеющиеся файлы не заменяются
     * (тот же uid — тот же снимок), фото текущих приёмов, которых нет в копии, остаются. Ошибки только логируются:
     * база уже восстановлена, без фото приём покажется «Фото не сохранилось».
     */
    private fun installPhotos(context: Context, from: File) {
        val names = from.list() ?: return
        val dir = File(context.filesDir, NutritionMath.PHOTO_DIR).apply { mkdirs() }
        for (name in names) {
            if (!NutritionMath.isSafePhotoName(name)) continue
            val src = File(from, name)
            val dest = File(dir, name)
            if (dest.exists()) continue
            try {
                if (!src.renameTo(dest)) {
                    val tmp = File(dir, "$name.tmp")
                    src.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (!tmp.renameTo(dest)) {
                        tmp.delete()
                        throw IOException("не удалось переименовать")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось восстановить фото $name", e)
            }
        }
    }

    private fun appVersionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun appVersionCode(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
}

/** Чистая логика резервной копии, без Android: её проверяют юнит-тесты. */
object BackupMath {
    /** Версия формата копии. Увеличивать при несовместимых изменениях содержимого zip. */
    const val FORMAT_VERSION = 1
    /** Самая старая схема базы, у которой есть журнал баланса. */
    const val MIN_DB_SCHEMA = 2

    const val ENTRY_DB = "diary.db"
    const val ENTRY_PREFS = "prefs.json"
    const val ENTRY_MANIFEST = "manifest.json"
    const val KEY_FORMAT = "format_version"
    const val KEY_DB_SCHEMA = "db_schema_version"

    val REQUIRED_TABLES = setOf("sets", "complex_runs", "balance_ops", "account", "meta")

    /** С версии 5 — таблицы ночного перерыва. */
    const val SLEEP_SCHEMA = 5
    val SLEEP_TABLES = setOf("phone_events", "observation_gaps", "sleep_nights")

    /** С версии 9 — дневник питания. */
    const val MEALS_SCHEMA = 9
    val MEAL_TABLES = setOf("meals", "meal_deletes")

    /** С версии 10 — ответы на карточки. */
    const val CARDS_SCHEMA = 10
    val CARD_TABLES = setOf("card_attempts")

    /** Предел одного фото и всех фото в копии (байт). */
    const val MAX_PHOTO_BYTES = 16L * 1024 * 1024
    const val MAX_PHOTOS_TOTAL_BYTES = 8L * 1024 * 1024 * 1024

    /** Таблицы, которые обязаны быть в базе версии [schema]: более старые копии догоняют миграции. */
    fun requiredTables(schema: Int): Set<String> {
        var t = REQUIRED_TABLES
        if (schema >= SLEEP_SCHEMA) t = t + SLEEP_TABLES
        if (schema >= MEALS_SCHEMA) t = t + MEAL_TABLES
        if (schema >= CARDS_SCHEMA) t = t + CARD_TABLES
        return t
    }

    /** null — манифест годится; иначе причина отказа. */
    fun checkManifest(formatVersion: Int?, dbSchema: Int?, supportedSchema: Int, supportedFormat: Int = FORMAT_VERSION): String? = when {
        formatVersion == null -> "Это не резервная копия Next Level Life: в манифесте нет версии формата"
        formatVersion < 1 -> "Неизвестная версия формата копии: $formatVersion"
        formatVersion > supportedFormat ->
            "Копия сделана более новой версией приложения (формат $formatVersion, поддерживается до $supportedFormat). Обновите приложение."
        dbSchema == null -> "В манифесте нет версии базы"
        dbSchema < MIN_DB_SCHEMA -> "Копия слишком старая: в ней нет журнала баланса (база версии $dbSchema)"
        dbSchema > supportedSchema ->
            "Копия сделана более новой версией приложения (база версии $dbSchema, поддерживается до $supportedSchema). Обновите приложение."
        else -> null
    }

    /** Что делать при запуске с прерванным восстановлением. */
    enum class RestoreRecovery {
        /** Маркера нет — восстановления не было. */
        NONE,
        /** Прежняя база ещё не отложена (или подмена уже завершена): оставить, что на месте, убрать остатки. */
        KEEP_CURRENT,
        /** Новая база установлена и цела: оставить её, дописать настройки копии, убрать прежние файлы. */
        KEEP_NEW,
        /** Новой базы нет или она испорчена: вернуть прежние файлы из .restore-bak и прежние настройки. */
        ROLL_BACK,
    }

    /**
     * Выбор действия по файлам на диске. Порядок подмены: маркер → основной файл в .restore-bak (первым)
     * → прочие файлы → новая база одним переименованием → настройки → удаление .restore-bak (основной первым).
     * [dbPresent] — основной файл базы по исходному пути; [dbBakPresent] — основной файл в .restore-bak;
     * [newDbUsable] — база по исходному пути открывается и цела (имеет смысл, только когда есть оба файла).
     */
    fun recoveryAction(markerPresent: Boolean, dbPresent: Boolean, dbBakPresent: Boolean, newDbUsable: Boolean): RestoreRecovery = when {
        !markerPresent -> RestoreRecovery.NONE
        // Основной файл не отложен: либо подмена не началась, либо уже завершена (настройки записаны раньше).
        !dbBakPresent -> RestoreRecovery.KEEP_CURRENT
        dbPresent && newDbUsable -> RestoreRecovery.KEEP_NEW
        else -> RestoreRecovery.ROLL_BACK
    }

    /** Версия самой базы должна совпадать с манифестом и поддерживаться. */
    fun checkDbVersion(userVersion: Int, manifestSchema: Int, supportedSchema: Int): String? = when {
        userVersion != manifestSchema -> "База в копии не совпадает с манифестом (версия $userVersion, ожидалась $manifestSchema)"
        userVersion < MIN_DB_SCHEMA -> "Копия слишком старая: в ней нет журнала баланса"
        userVersion > supportedSchema -> "База в копии новее приложения. Обновите приложение."
        else -> null
    }
}
