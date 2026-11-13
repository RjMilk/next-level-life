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
 * приложения, серия, рекорды) и manifest.json (версия формата, версия приложения, время, device_id).
 * Записи движений (poselog) в копию не входят. Выбор файла — забота экрана: здесь только потоки.
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
                            else -> Unit // Чужие файлы пропускаем, на диск не пишем.
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
            val pause = com.nextlevellife.app.sleep.PhoneEvents.pauseForRestore()
            var replaced = false
            try {
                return swap(context, dbTmp, newPrefs, newPrefsJson) { carried[0] = it }.let { error ->
                    replaced = error == null
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
                    carriedMs = Ledger.appendRestoreCarry(db, currentSpent, deviceId, zone, nowMs)
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

    private fun copyLimited(input: InputStream, out: OutputStream, limit: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("Файл в копии слишком большой")
            out.write(buf, 0, n)
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

    /** Таблицы, которые обязаны быть в базе версии [schema]: более старые копии догоняют миграции. */
    fun requiredTables(schema: Int): Set<String> =
        if (schema >= SLEEP_SCHEMA) REQUIRED_TABLES + SLEEP_TABLES else REQUIRED_TABLES

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
