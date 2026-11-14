package com.nextlevellife.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.Locale
import java.util.UUID

/**
 * Версия карточки своего продукта. Правка карточки — новая версия ([versionUid]) того же продукта ([productUid]);
 * актуальная — с наибольшим (utcMs, versionUid) ([ProductsMath.latest]). Пищевая ценность — на 100 г:
 * [kcal] — ккал, [proteinDg]/[fatDg]/[carbsDg] — белки/жиры/углеводы в десятых долях грамма (125 = 12,5 г).
 * [portionG] — порция по умолчанию в граммах. null — не указано.
 */
data class ProductVersion(
    val versionUid: String,
    val productUid: String,
    val author: String,
    val utcMs: Long,
    val name: String,
    val barcode: String?,
    val kcal: Int?,
    val proteinDg: Int?,
    val fatDg: Int?,
    val carbsDg: Int?,
    val portionG: Int?,
)

/**
 * Позиция приёма пищи: продукт (конкретная версия карточки — правка карточки не меняет прошлые приёмы) и порция
 * [grams] в граммах (null — «без веса»). [author] — телефон, где позицию добавили.
 */
data class MealEntry(
    val uid: String,
    val author: String,
    val mealUid: String,
    val versionUid: String,
    val grams: Int?,
    val utcMs: Long,
)

/** Позиция с её версией карточки; [product] null — версия ещё не пришла со второго телефона. */
data class MealEntryLine(val entry: MealEntry, val product: ProductVersion?)

/**
 * Сумма по позициям. Ккал — по позициям, где известны порция и ккал на 100 г ([kcalCounted] из [entries]);
 * БЖУ — по позициям, где известны порция и все три значения на 100 г ([macrosCounted]). Ккал — в тысячных
 * долях (ккал·г/100 без округления не делится), граммы БЖУ — в десятых долях грамма, округлённые к ближайшему.
 */
data class NutritionTotals(
    val entries: Int,
    val kcalCounted: Int,
    val kcal: Long,
    val macrosCounted: Int,
    val proteinDg: Long,
    val fatDg: Long,
    val carbsDg: Long,
)

/** Поле карточки продукта (для ошибок ввода). [MACROS] — сумма БЖУ. */
enum class ProductField { NAME, BARCODE, KCAL, PROTEIN, FAT, CARBS, MACROS, PORTION }

/**
 * Разобранная форма карточки: значения и ошибки по полям. Сохранять можно только при пустом [errors].
 */
data class ProductForm(
    val name: String,
    val barcode: String?,
    val kcal: Int?,
    val proteinDg: Int?,
    val fatDg: Int?,
    val carbsDg: Int?,
    val portionG: Int?,
    val errors: Map<ProductField, String>,
) {
    val ok: Boolean get() = errors.isEmpty()
}

/** Вид штрихкода, который отдаёт сканер (ML Kit). */
enum class BarcodeKind { EAN_8, EAN_13, UPC_A, UPC_E }

/**
 * Свои карточки продуктов и позиции приёмов на дневнике (diary.db, схема 11). Версия карточки, удаление продукта,
 * позиция приёма и удаление позиции — факты (author, epoch, aseq, uid), как приёмы пищи: пишутся одной транзакцией
 * с номером из общего счётчика фактов и уходят на второй телефон обменом. Никаких внешних баз: всё вводит
 * пользователь. Минут и очков питание не даёт.
 */
object Products {
    /**
     * Сохраняет карточку [form] (без ошибок): новый продукт при [productUid] null, иначе — новая версия того же
     * продукта. Если значения не изменились — возвращает текущую версию без нового факта. Удалённый продукт не
     * правится — null.
     */
    fun save(productUid: String?, form: ProductForm, time: TimeSnapshot = TrustedClock.snapshot()): ProductVersion? {
        require(form.ok) { "Карточка с ошибками" }
        val result = synchronized(Diary) {
            Diary.write { db ->
                val pUid = productUid ?: UUID.randomUUID().toString()
                require(NutritionMath.isSafeUid(pUid)) { "Недопустимый uid продукта" }
                if (productUid != null) {
                    if (isProductDeleted(db, pUid)) return@write null
                    val current = ProductsMath.latest(versions(db, "product_uid = ?", pUid))[pUid]
                    if (current != null && ProductsMath.sameValues(current, form)) return@write current
                }
                val v = ProductVersion(
                    versionUid = UUID.randomUUID().toString(), productUid = pUid, author = Ledger.deviceId(),
                    utcMs = time.utcMs, name = form.name, barcode = form.barcode, kcal = form.kcal,
                    proteinDg = form.proteinDg, fatDg = form.fatDg, carbsDg = form.carbsDg, portionG = form.portionG,
                )
                db.insertOrThrow("product_versions", null, versionValues(v, Diary.factsEpoch(), Diary.nextFactSeq(), time.day, time.zone.id))
                v
            }
        }
        Store.notifyChanged()
        return result
    }

    /** Удаляет продукт [productUid]: отдельный факт. Старые приёмы с ним остаются подписанными. */
    fun delete(productUid: String, time: TimeSnapshot = TrustedClock.snapshot()): Unit = synchronized(Diary) {
        Diary.write { db ->
            if (isProductDeleted(db, productUid)) return@write
            db.insertOrThrow("product_deletes", null, ContentValues().apply {
                put("uid", UUID.randomUUID().toString())
                put("product_uid", productUid)
                put("author", Ledger.deviceId())
                put("epoch", Diary.factsEpoch())
                put("aseq", Diary.nextFactSeq())
                put("utc_ms", time.utcMs)
                put("day", time.day)
                put("zone_id", time.zone.id)
            })
        }
        Store.notifyChanged()
    }

    /** Неудалённые продукты (актуальные версии), по названию. */
    fun live(): List<ProductVersion> = synchronized(Diary) {
        val db = Diary.db()
        ProductsMath.live(versions(db, null), deletedProducts(db))
    }

    /** Версия карточки по uid (в том числе удалённого продукта); null — нет. */
    fun version(versionUid: String): ProductVersion? = synchronized(Diary) {
        versions(Diary.db(), "uid = ?", versionUid).firstOrNull()
    }

    /** Актуальная версия неудалённого продукта [productUid]; null — нет или удалён. */
    fun current(productUid: String): ProductVersion? = live().firstOrNull { it.productUid == productUid }

    /**
     * Добавляет в приём [mealUid] позицию: версия [version], порция [grams] (null — порция по умолчанию продукта,
     * а если её нет — «без веса»). Возвращает позицию.
     */
    fun addEntry(mealUid: String, version: ProductVersion, grams: Int?, time: TimeSnapshot = TrustedClock.snapshot()): MealEntry {
        require(NutritionMath.isSafeUid(mealUid)) { "Недопустимый uid приёма" }
        val g = ProductsMath.entryGrams(grams, version)
        val entry = MealEntry(UUID.randomUUID().toString(), Ledger.deviceId(), mealUid, version.versionUid, g, time.utcMs)
        synchronized(Diary) {
            Diary.write { db ->
                db.insertOrThrow("meal_entries", null, ContentValues().apply {
                    put("uid", entry.uid)
                    put("meal_uid", mealUid)
                    put("version_uid", version.versionUid)
                    if (g != null) put("grams", g) else putNull("grams")
                    put("author", entry.author)
                    put("epoch", Diary.factsEpoch())
                    put("aseq", Diary.nextFactSeq())
                    put("utc_ms", time.utcMs)
                    put("day", time.day)
                    put("zone_id", time.zone.id)
                })
            }
        }
        Store.notifyChanged()
        return entry
    }

    /** Убирает позицию [entryUid] из приёма: отдельный факт удаления. */
    fun deleteEntry(entryUid: String, time: TimeSnapshot = TrustedClock.snapshot()): Unit = synchronized(Diary) {
        Diary.write { db ->
            if (exists(db, "SELECT 1 FROM meal_entry_deletes WHERE entry_uid = ? LIMIT 1", entryUid)) return@write
            db.insertOrThrow("meal_entry_deletes", null, ContentValues().apply {
                put("uid", UUID.randomUUID().toString())
                put("entry_uid", entryUid)
                put("author", Ledger.deviceId())
                put("epoch", Diary.factsEpoch())
                put("aseq", Diary.nextFactSeq())
                put("utc_ms", time.utcMs)
                put("day", time.day)
                put("zone_id", time.zone.id)
            })
        }
        Store.notifyChanged()
    }

    /**
     * Неудалённые позиции неудалённых приёмов учётных суток [day] (по дню приёма) с их версиями карточек:
     * uid приёма → позиции по времени добавления. Позиция, пришедшая раньше приёма, появится вместе с ним.
     */
    fun entriesForDay(day: Long): Map<String, List<MealEntryLine>> = synchronized(Diary) {
        Diary.query(
            "SELECT e.uid, e.author, e.meal_uid, e.version_uid, e.grams, e.utc_ms, $VERSION_COLS_V " +
                "FROM meal_entries e JOIN meals m ON m.uid = e.meal_uid " +
                "LEFT JOIN product_versions v ON v.uid = e.version_uid " +
                "WHERE m.accounting_day = ? AND m.deleted = 0 " +
                "AND NOT EXISTS (SELECT 1 FROM meal_entry_deletes d WHERE d.entry_uid = e.uid) " +
                "ORDER BY e.utc_ms, e.uid",
            day.toString(),
        ) { c ->
            val entry = MealEntry(
                uid = c.getString(0), author = c.getString(1), mealUid = c.getString(2), versionUid = c.getString(3),
                grams = if (c.isNull(4)) null else c.getInt(4), utcMs = c.getLong(5),
            )
            MealEntryLine(entry, if (c.isNull(6)) null else c.toVersion(6))
        }.groupBy { it.entry.mealUid }
    }

    // ---------- Для обмена (DiarySyncStore) ----------

    /** Вставляет копию версии карточки со второго телефона; дубль по uid пропускается. true — вставлена. */
    internal fun insertVersionCopy(db: SQLiteDatabase, v: ProductVersion, epoch: String, aseq: Long, day: Long, zoneId: String): Boolean {
        if (exists(db, "SELECT 1 FROM product_versions WHERE uid = ? LIMIT 1", v.versionUid)) return false
        db.insertOrThrow("product_versions", null, versionValues(v, epoch, aseq, day, zoneId))
        return true
    }

    /** Вставляет копию удаления продукта; дубль по uid пропускается. Удаление может прийти раньше карточки. */
    internal fun insertProductDeleteCopy(
        db: SQLiteDatabase, uid: String, productUid: String, author: String, epoch: String, aseq: Long,
        utcMs: Long, day: Long, zoneId: String,
    ): Boolean {
        if (exists(db, "SELECT 1 FROM product_deletes WHERE uid = ? LIMIT 1", uid)) return false
        db.insertOrThrow("product_deletes", null, ContentValues().apply {
            put("uid", uid); put("product_uid", productUid); put("author", author); put("epoch", epoch); put("aseq", aseq)
            put("utc_ms", utcMs); put("day", day); put("zone_id", zoneId)
        })
        return true
    }

    /** Вставляет копию позиции; дубль по uid пропускается. Позиция может прийти раньше приёма и карточки. */
    internal fun insertEntryCopy(
        db: SQLiteDatabase, uid: String, mealUid: String, versionUid: String, grams: Int?, author: String, epoch: String,
        aseq: Long, utcMs: Long, day: Long, zoneId: String,
    ): Boolean {
        if (exists(db, "SELECT 1 FROM meal_entries WHERE uid = ? LIMIT 1", uid)) return false
        db.insertOrThrow("meal_entries", null, ContentValues().apply {
            put("uid", uid); put("meal_uid", mealUid); put("version_uid", versionUid)
            if (grams != null) put("grams", grams) else putNull("grams")
            put("author", author); put("epoch", epoch); put("aseq", aseq)
            put("utc_ms", utcMs); put("day", day); put("zone_id", zoneId)
        })
        return true
    }

    /** Вставляет копию удаления позиции; дубль по uid пропускается. */
    internal fun insertEntryDeleteCopy(
        db: SQLiteDatabase, uid: String, entryUid: String, author: String, epoch: String, aseq: Long,
        utcMs: Long, day: Long, zoneId: String,
    ): Boolean {
        if (exists(db, "SELECT 1 FROM meal_entry_deletes WHERE uid = ? LIMIT 1", uid)) return false
        db.insertOrThrow("meal_entry_deletes", null, ContentValues().apply {
            put("uid", uid); put("entry_uid", entryUid); put("author", author); put("epoch", epoch); put("aseq", aseq)
            put("utc_ms", utcMs); put("day", day); put("zone_id", zoneId)
        })
        return true
    }

    /** Столбцы версии карточки (в этом порядке читает [toVersion]). */
    internal const val VERSION_COLS =
        "uid, product_uid, author, utc_ms, name, barcode, kcal, protein_dg, fat_dg, carbs_dg, portion_g"
    private const val VERSION_COLS_V =
        "v.uid, v.product_uid, v.author, v.utc_ms, v.name, v.barcode, v.kcal, v.protein_dg, v.fat_dg, v.carbs_dg, v.portion_g"

    /** Версия карточки из курсора, начиная со столбца [from] (порядок [VERSION_COLS]). */
    internal fun Cursor.toVersion(from: Int = 0): ProductVersion {
        fun int(i: Int): Int? = if (isNull(from + i)) null else getInt(from + i)
        return ProductVersion(
            versionUid = getString(from), productUid = getString(from + 1), author = getString(from + 2),
            utcMs = getLong(from + 3), name = getString(from + 4),
            barcode = if (isNull(from + 5)) null else getString(from + 5),
            kcal = int(6), proteinDg = int(7), fatDg = int(8), carbsDg = int(9), portionG = int(10),
        )
    }

    private fun versionValues(v: ProductVersion, epoch: String, aseq: Long, day: Long, zoneId: String) = ContentValues().apply {
        put("uid", v.versionUid); put("product_uid", v.productUid); put("author", v.author)
        put("epoch", epoch); put("aseq", aseq); put("utc_ms", v.utcMs); put("day", day); put("zone_id", zoneId)
        put("name", v.name)
        fun opt(key: String, value: Int?) = if (value != null) put(key, value) else putNull(key)
        if (v.barcode != null) put("barcode", v.barcode) else putNull("barcode")
        opt("kcal", v.kcal); opt("protein_dg", v.proteinDg); opt("fat_dg", v.fatDg); opt("carbs_dg", v.carbsDg)
        opt("portion_g", v.portionG)
    }

    private fun versions(db: SQLiteDatabase, where: String?, vararg args: String): List<ProductVersion> {
        val sql = "SELECT $VERSION_COLS FROM product_versions" + (if (where != null) " WHERE $where" else "")
        return db.rawQuery(sql, args).use { c ->
            val out = ArrayList<ProductVersion>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) out += c.toVersion()
            out
        }
    }

    private fun deletedProducts(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT product_uid FROM product_deletes", null).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    private fun isProductDeleted(db: SQLiteDatabase, productUid: String): Boolean =
        exists(db, "SELECT 1 FROM product_deletes WHERE product_uid = ? LIMIT 1", productUid)

    private fun exists(db: SQLiteDatabase, sql: String, vararg args: String): Boolean =
        db.rawQuery(sql, args).use { it.moveToFirst() }
}

/** Чистая логика карточек продуктов, штрихкодов и подсчёта, без Android: её проверяют юнит-тесты. */
object ProductsMath {
    /** Предел названия продукта (символов). */
    const val NAME_MAX = 60

    /** Предел ккал на 100 г (чистый жир — около 900). */
    const val KCAL_MAX = 900

    /** Предел белков, жиров, углеводов на 100 г — в десятых долях грамма (100,0 г). */
    const val MACRO_MAX_DG = 1000

    /** Порция и вес позиции, граммов. */
    const val GRAMS_MIN = 1
    const val GRAMS_MAX = 2000

    /**
     * Длины штрихкода: EAN-8, UPC-A (12), EAN-13, GTIN-14. Длины 9–11 стандартных кодов не бывает — не принимаются.
     * UPC-E (8 цифр со сжатой записью) хранится развёрнутым в UPC-A ([expandUpcE]): его контрольная цифра
     * считается по развёрнутому коду, а не как у EAN-8, и тот же товар, считанный как UPC-A, должен совпасть.
     */
    val BARCODE_LENGTHS = setOf(8, 12, 13, 14)

    /** Неразрывный пробел: «640 ккал» не разрывается на узком экране. */
    private const val NBSP = ' '

    // ---------- Название ----------

    /** Пробелы и переводы строк — одним пробелом, по краям — без пробелов. */
    fun normalizeName(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()

    /** Название годится: 1–60 символов, уже нормализовано, без управляющих символов. */
    fun isValidName(name: String): Boolean =
        name.length in 1..NAME_MAX && name == normalizeName(name) && name.none { Character.isISOControl(it) }

    // ---------- Штрихкод ----------

    /** Только ASCII-цифры (не «٣» и не «３»). */
    private fun isDigits(s: String): Boolean = s.isNotEmpty() && s.all { it in '0'..'9' }

    /** Контрольная цифра GTIN для [body] (код без последней цифры): веса 3, 1, 3, … справа налево. */
    fun gtinCheckDigit(body: String): Int {
        require(isDigits(body))
        var sum = 0
        for ((i, ch) in body.reversed().withIndex()) sum += (ch - '0') * if (i % 2 == 0) 3 else 1
        return (10 - sum % 10) % 10
    }

    /** Штрихкод годится: только цифры, длина из [BARCODE_LENGTHS], верная контрольная цифра. */
    fun isValidBarcode(code: String): Boolean =
        code.length in BARCODE_LENGTHS && isDigits(code) &&
            gtinCheckDigit(code.dropLast(1)) == code.last() - '0'

    /**
     * UPC-E → UPC-A. Принимает 8 цифр (система счисления 0 или 1, шесть цифр, контрольная) или 6 цифр (система 0,
     * контрольная досчитывается). Контрольная цифра UPC-E — это контрольная цифра развёрнутого UPC-A: при
     * несовпадении — null. Иначе — 12 цифр UPC-A.
     */
    fun expandUpcE(code: String): String? {
        if (!isDigits(code)) return null
        val (ns, d, check) = when (code.length) {
            8 -> Triple(code[0], code.substring(1, 7), code[7])
            6 -> Triple('0', code, null)
            else -> return null
        }
        if (ns != '0' && ns != '1') return null
        val body = when (d[5]) {
            '0', '1', '2' -> "$ns${d[0]}${d[1]}${d[5]}0000${d[2]}${d[3]}${d[4]}"
            '3' -> "$ns${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}"
            '4' -> "$ns${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}"
            else -> "$ns${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${d[5]}"
        }
        val digit = gtinCheckDigit(body)
        if (check != null && check - '0' != digit) return null
        return "$body$digit"
    }

    /**
     * Считанный сканером код → штрихкод карточки: UPC-E разворачивается в UPC-A, остальные — как есть. null —
     * код не прошёл проверку (длина, цифры, контрольная цифра): такой не предлагается.
     */
    fun normalizeScan(kind: BarcodeKind, raw: String): String? {
        val code = raw.trim()
        val out = when (kind) {
            BarcodeKind.UPC_E -> expandUpcE(code) ?: return null
            BarcodeKind.EAN_8 -> code.takeIf { it.length == 8 }
            BarcodeKind.UPC_A -> code.takeIf { it.length == 12 }
            BarcodeKind.EAN_13 -> code.takeIf { it.length == 13 }
        } ?: return null
        return out.takeIf(::isValidBarcode)
    }

    /**
     * Ключ сравнения штрихкодов — GTIN-14 (дополнение нулями слева): UPC-A «012345678905» и EAN-13
     * «0012345678905» — один товар.
     */
    fun gtinKey(code: String): String = code.padStart(14, '0')

    // ---------- Версии и список ----------

    /** Актуальная версия каждого продукта: наибольшие (utcMs, versionUid). */
    fun latest(versions: Collection<ProductVersion>): Map<String, ProductVersion> {
        val out = HashMap<String, ProductVersion>()
        for (v in versions) {
            val old = out[v.productUid]
            if (old == null || newer(v, old)) out[v.productUid] = v
        }
        return out
    }

    private fun newer(a: ProductVersion, b: ProductVersion): Boolean =
        a.utcMs > b.utcMs || (a.utcMs == b.utcMs && a.versionUid > b.versionUid)

    /** Неудалённые продукты (актуальные версии) по названию без учёта регистра. */
    fun live(versions: Collection<ProductVersion>, deletedProductUids: Set<String>): List<ProductVersion> =
        latest(versions).values.filter { it.productUid !in deletedProductUids }
            .sortedWith(compareBy<ProductVersion> { it.name.lowercase(Locale.ROOT) }.thenBy { it.productUid })

    /** Продукт со штрихкодом [code] среди [live]; несколько (созданы на двух телефонах) — самый свежий. */
    fun findByBarcode(live: Collection<ProductVersion>, code: String): ProductVersion? {
        val key = gtinKey(code)
        return live.filter { it.barcode != null && gtinKey(it.barcode) == key }
            .maxWithOrNull(compareBy<ProductVersion> { it.utcMs }.thenBy { it.versionUid })
    }

    private fun searchKey(s: String): String = s.lowercase(Locale.ROOT).replace('ё', 'е')

    /**
     * Поиск по названию (без учёта регистра, «ё» = «е») или по цифрам штрихкода. Сначала названия, начинающиеся
     * с запроса. Пустой запрос — весь список.
     */
    fun search(live: List<ProductVersion>, query: String): List<ProductVersion> {
        val q = searchKey(normalizeName(query))
        if (q.isEmpty()) return live
        val digits = q.all { it in '0'..'9' }
        val found = live.filter { searchKey(it.name).contains(q) || (digits && it.barcode?.contains(q) == true) }
        return found.sortedBy { if (searchKey(it.name).startsWith(q)) 0 else 1 }
    }

    // ---------- Форма карточки ----------

    /**
     * Разбор формы карточки. Пустые необязательные поля — null. [barcodeOwner] — название другого продукта
     * с тем же штрихкодом (null — свободен).
     */
    fun parseForm(
        name: String,
        barcode: String,
        kcal: String,
        protein: String,
        fat: String,
        carbs: String,
        portion: String,
        barcodeOwner: (String) -> String? = { null },
    ): ProductForm {
        val errors = LinkedHashMap<ProductField, String>()
        val n = normalizeName(name)
        when {
            n.isEmpty() -> errors[ProductField.NAME] = "Введи название"
            n.length > NAME_MAX -> errors[ProductField.NAME] = "Не длиннее $NAME_MAX символов"
            n.any { Character.isISOControl(it) } -> errors[ProductField.NAME] = "Недопустимые символы"
        }
        val code = barcode.filterNot { it == ' ' || it == '-' }
        val codeError = when {
            code.isEmpty() -> null
            !isDigits(code) -> "Только цифры"
            code.length !in BARCODE_LENGTHS -> "Нужно 8, 12, 13 или 14 цифр"
            !isValidBarcode(code) -> "Неверная контрольная цифра — проверь код"
            else -> barcodeOwner(code)?.let { "Этот штрихкод уже у «$it»" }
        }
        if (codeError != null) errors[ProductField.BARCODE] = codeError
        val k = parseInt(kcal, 0, KCAL_MAX, "ккал").also { (_, e) -> if (e != null) errors[ProductField.KCAL] = e }.first
        val p = parseDecimal(protein).also { (_, e) -> if (e != null) errors[ProductField.PROTEIN] = e }.first
        val f = parseDecimal(fat).also { (_, e) -> if (e != null) errors[ProductField.FAT] = e }.first
        val c = parseDecimal(carbs).also { (_, e) -> if (e != null) errors[ProductField.CARBS] = e }.first
        if ((p ?: 0) + (f ?: 0) + (c ?: 0) > MACRO_MAX_DG) {
            errors[ProductField.MACROS] = "Белков, жиров и углеводов вместе больше 100${NBSP}г на 100${NBSP}г"
        }
        val g = parseGrams(portion).also { (_, e) -> if (e != null) errors[ProductField.PORTION] = e }.first
        return ProductForm(n, code.ifEmpty { null }, k, p, f, c, g, errors)
    }

    /** Вес порции: пусто — null без ошибки; иначе целое 1–2000. */
    fun parseGrams(text: String): Pair<Int?, String?> = parseInt(text, GRAMS_MIN, GRAMS_MAX, "г")

    private fun parseInt(text: String, min: Int, max: Int, unit: String): Pair<Int?, String?> {
        val t = text.trim()
        if (t.isEmpty()) return null to null
        if (!isDigits(t)) return null to "Целое число от $min до $max"
        val v = t.trimStart('0').ifEmpty { "0" }
        if (v.length > 6 || v.toInt() !in min..max) return null to "От $min до $max$NBSP$unit"
        return v.toInt() to null
    }

    /** Граммы на 100 г с одним знаком после запятой («12,5», «12.5», «12») → десятые доли грамма, 0–100 г. */
    fun parseDecimal(text: String): Pair<Int?, String?> {
        val t = text.trim().replace('.', ',')
        if (t.isEmpty()) return null to null
        val parts = t.split(',')
        if (parts.size > 2 || !isDigits(parts[0]) || (parts.size == 2 && !isDigits(parts[1]))) {
            return null to "Число, например 12,5"
        }
        if (parts.size == 2 && parts[1].length > 1) return null to "Не больше одного знака после запятой"
        val whole = parts[0].trimStart('0').ifEmpty { "0" }
        if (whole.length > 4) return null to "От 0 до 100${NBSP}г"
        val dg = whole.toInt() * 10 + (if (parts.size == 2) parts[1][0] - '0' else 0)
        if (dg > MACRO_MAX_DG) return null to "От 0 до 100${NBSP}г"
        return dg to null
    }

    /** Значения формы совпадают с версией [v]: новая версия не нужна. */
    fun sameValues(v: ProductVersion, form: ProductForm): Boolean =
        v.name == form.name && v.barcode == form.barcode && v.kcal == form.kcal && v.proteinDg == form.proteinDg &&
            v.fatDg == form.fatDg && v.carbsDg == form.carbsDg && v.portionG == form.portionG

    /** Поля формы для правки версии [v] (как ввёл бы пользователь). */
    fun formFields(v: ProductVersion?): List<String> = listOf(
        v?.name.orEmpty(), v?.barcode.orEmpty(), v?.kcal?.toString().orEmpty(),
        v?.proteinDg?.let(::dgText).orEmpty(), v?.fatDg?.let(::dgText).orEmpty(), v?.carbsDg?.let(::dgText).orEmpty(),
        v?.portionG?.toString().orEmpty(),
    )

    /** Название другого неудалённого продукта с тем же штрихкодом (не [exceptProductUid]); null — свободен. */
    fun barcodeOwner(live: Collection<ProductVersion>, code: String, exceptProductUid: String?): String? =
        findByBarcode(live.filter { it.productUid != exceptProductUid }, code)?.name

    // ---------- Порция и подсчёт ----------

    /** Вес позиции: указанный, иначе порция по умолчанию, иначе null («без веса»). */
    fun entryGrams(grams: Int?, product: ProductVersion): Int? = grams ?: product.portionG

    /** Сумма ккал и БЖУ по позициям [lines]. */
    fun totals(lines: List<MealEntryLine>): NutritionTotals {
        var kcalCounted = 0
        var kcalMilli = 0L // ккал × 1000
        var macrosCounted = 0
        var p = 0L
        var f = 0L
        var c = 0L // дг × г
        for (line in lines) {
            val g = line.entry.grams ?: continue
            val v = line.product ?: continue
            if (v.kcal != null) {
                kcalCounted++
                kcalMilli += v.kcal.toLong() * g * 10 // ккал/100 г × г × 1000 / 100
            }
            if (v.proteinDg != null && v.fatDg != null && v.carbsDg != null) {
                macrosCounted++
                p += v.proteinDg.toLong() * g
                f += v.fatDg.toLong() * g
                c += v.carbsDg.toLong() * g
            }
        }
        fun dg(x: Long) = (x + 50) / 100 // дг × г / 100 г → дг, к ближайшему
        return NutritionTotals(lines.size, kcalCounted, kcalMilli, macrosCounted, dg(p), dg(f), dg(c))
    }

    /** Ккал из тысячных долей — к ближайшему целому. */
    private fun kcalRounded(milli: Long): Long = (milli + 500) / 1000

    /** Ккал одной позиции; null — нет веса или ккал на 100 г. */
    fun entryKcal(line: MealEntryLine): Long? {
        val g = line.entry.grams ?: return null
        val k = line.product?.kcal ?: return null
        return kcalRounded(k.toLong() * g * 10)
    }

    /** «1 позиции», «2 позиций», «21 позиции» — родительный падеж после «без». */
    fun withoutEntries(n: Int): String {
        val m100 = n % 100
        val word = if (n % 10 == 1 && m100 != 11) "позиции" else "позиций"
        return "без $n$NBSP$word"
    }

    fun productsWord(n: Int): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> "продуктов"
            m10 == 1 -> "продукт"
            m10 in 2..4 -> "продукта"
            else -> "продуктов"
        }
    }

    /**
     * «640 ккал»; часть позиций без данных — «≈ 640 ккал (без 2 позиций)»; ни одной с данными — «Ккал не
     * посчитать: нет веса или ккал на 100 г». Позиций нет — null.
     */
    fun kcalText(t: NutritionTotals): String? = when {
        t.entries == 0 -> null
        t.kcalCounted == 0 -> "Ккал не посчитать: нет веса или ккал на${NBSP}100${NBSP}г"
        t.kcalCounted == t.entries -> "${kcalRounded(t.kcal)}${NBSP}ккал"
        else -> "≈${NBSP}${kcalRounded(t.kcal)}${NBSP}ккал (${withoutEntries(t.entries - t.kcalCounted)})"
    }

    /** «Б 32,5 · Ж 20,1 · У 80,0 г», часть без данных — «≈ …» и «(без N позиций)»; ни одной — null. */
    fun macrosText(t: NutritionTotals): String? {
        if (t.entries == 0 || t.macrosCounted == 0) return null
        val body = "Б${NBSP}${dgText(t.proteinDg)} · Ж${NBSP}${dgText(t.fatDg)} · У${NBSP}${dgText(t.carbsDg)}${NBSP}г"
        if (t.macrosCounted == t.entries) return body
        return "≈${NBSP}$body (${withoutEntries(t.entries - t.macrosCounted)})"
    }

    /**
     * Строка карточки приёма в ленте: «2 продукта · 640 ккал», «3 продукта · ≈ 640 ккал (без 1 позиции)»;
     * ккал не посчитать — «1 продукт». Позиций нет — null.
     */
    fun mealLine(t: NutritionTotals): String? {
        if (t.entries == 0) return null
        val count = "${t.entries}$NBSP${productsWord(t.entries)}"
        return if (t.kcalCounted == 0) count else "$count · ${kcalText(t)}"
    }

    /** Десятые доли грамма → «12,5», «0,0». */
    fun dgText(dg: Int): String = dgText(dg.toLong())

    fun dgText(dg: Long): String = "${dg / 10},${dg % 10}"

    /** На 100 г: «250 ккал · Б 12,5 · Ж 3,0 · У 40,0»; ничего не указано — «Пищевая ценность не указана». */
    fun per100Text(v: ProductVersion): String {
        val parts = ArrayList<String>()
        v.kcal?.let { parts += "$it${NBSP}ккал" }
        v.proteinDg?.let { parts += "Б${NBSP}${dgText(it)}" }
        v.fatDg?.let { parts += "Ж${NBSP}${dgText(it)}" }
        v.carbsDg?.let { parts += "У${NBSP}${dgText(it)}" }
        if (parts.isEmpty()) return "Пищевая ценность не указана"
        return parts.joinToString(" · ") + " на${NBSP}100${NBSP}г"
    }

    /** Строка позиции: «150 г · 375 ккал», «150 г», «без веса». */
    fun entryText(line: MealEntryLine): String {
        val g = line.entry.grams ?: return "без веса"
        val k = entryKcal(line)
        return if (k != null) "$g${NBSP}г · $k${NBSP}ккал" else "$g${NBSP}г"
    }

    /** Название позиции; версия ещё не пришла со второго телефона — понятная подпись. */
    fun entryName(line: MealEntryLine): String = line.product?.name ?: "Продукт ещё не пришёл со второго телефона"
}
