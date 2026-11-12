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
    /** Данные заменены. [createdAtMs] и [sourceDeviceId] — из манифеста копии. */
    data class Ok(val createdAtMs: Long, val sourceDeviceId: String?) : RestoreResult

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
                .put("created_at_ms", System.currentTimeMillis())
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
     * Идентификатор этой установки (device_id) сохраняется прежним.
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
            val newPrefs = try {
                Store.parsePrefs(JSONObject(prefsRaw))
            } catch (e: Exception) {
                Log.e(TAG, "Настройки копии повреждены", e)
                return RestoreResult.Error("Настройки в копии повреждены")
            }

            // 4. База из копии: целостность, версия схемы, нужные таблицы.
            checkDbFile(dbTmp, schema!!)?.let { return RestoreResult.Error(it) }

            // 5. Подмена.
            return swap(context, dbTmp, newPrefs).let { error ->
                if (error != null) RestoreResult.Error(error)
                else RestoreResult.Ok(manifest.optLong("created_at_ms", 0L), manifest.optString("device_id").ifEmpty { null })
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
                val missing = BackupMath.REQUIRED_TABLES - tables
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

    /** Заменяет базу и настройки. null — успех; иначе причина, а данные возвращены как были. */
    private fun swap(context: Context, newDb: File, newPrefs: Store.PrefsSnapshot): String? = synchronized(Diary) {
        val dbFile = Diary.dbFile(context)
        val localDeviceId = runCatching { Ledger.deviceId() }.getOrNull()
        val oldPrefs = Store.parsePrefs(Store.exportPrefs())
        val moved = ArrayList<Pair<File, File>>()
        var prefsWritten = false

        Diary.close()
        try {
            // Старые файлы — в сторону (не удаляем, пока новая база не открылась).
            for (suffix in SUFFIXES) {
                val f = File(dbFile.path + suffix)
                if (!f.exists()) continue
                val bak = File(f.path + BAK)
                bak.delete()
                if (!f.renameTo(bak)) throw IOException("Не удалось отложить ${f.name}")
                moved += f to bak
            }
            val staging = File(dbFile.path + ".restore-new")
            newDb.inputStream().use { input -> staging.outputStream().use { input.copyTo(it) } }
            if (!staging.renameTo(dbFile)) throw IOException("Не удалось положить базу на место")

            Diary.reopen()
            if (localDeviceId != null) Diary.metaPut(Ledger.KEY_DEVICE_ID, localDeviceId)
            Ledger.onDatabaseReplaced()
            if (!Ledger.isConsistent()) throw IllegalStateException("Баланс не сходится с журналом после подмены")

            prefsWritten = true
            if (!Store.commitPrefs(newPrefs)) throw IOException("Не удалось записать настройки")

            moved.forEach { (_, bak) -> bak.delete() }
            Store.notifyChanged()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Подмена не удалась, возвращаю прежние данные", e)
            runCatching { Diary.close() }
            for (suffix in SUFFIXES) File(dbFile.path + suffix).takeIf { it.exists() }?.delete()
            File(dbFile.path + ".restore-new").delete()
            for ((orig, bak) in moved) if (!bak.renameTo(orig)) Log.e(TAG, "Не удалось вернуть ${orig.name}")
            if (prefsWritten) runCatching { Store.commitPrefs(oldPrefs) }
            runCatching { Diary.reopen() }
            Ledger.onDatabaseReplaced()
            Store.notifyChanged()
            "Не удалось заменить данные: ${e.message ?: e.javaClass.simpleName}. Текущие данные сохранены."
        }
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

    /** Версия самой базы должна совпадать с манифестом и поддерживаться. */
    fun checkDbVersion(userVersion: Int, manifestSchema: Int, supportedSchema: Int): String? = when {
        userVersion != manifestSchema -> "База в копии не совпадает с манифестом (версия $userVersion, ожидалась $manifestSchema)"
        userVersion < MIN_DB_SCHEMA -> "Копия слишком старая: в ней нет журнала баланса"
        userVersion > supportedSchema -> "База в копии новее приложения. Обновите приложение."
        else -> null
    }
}
