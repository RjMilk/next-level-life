package com.nextlevellife.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductsMathTest {
    /** Неразрывные пробелы — обычными: так тексты проще сравнивать. */
    private fun plain(s: String?) = s?.replace(' ', ' ')

    private fun product(
        uid: String = "p1", productUid: String = "prod", utcMs: Long = 1_000L, name: String = "Гречка",
        barcode: String? = null, kcal: Int? = null, p: Int? = null, f: Int? = null, c: Int? = null, portion: Int? = null,
    ) = ProductVersion(uid, productUid, "aaa", utcMs, name, barcode, kcal, p, f, c, portion)

    private fun line(grams: Int?, product: ProductVersion?, uid: String = "e${grams}${product?.versionUid}") =
        MealEntryLine(MealEntry(uid, "aaa", "m1", product?.versionUid ?: "nope", grams, 1L), product)

    // ---------- Штрихкод ----------

    @Test
    fun barcodeCheckDigit() {
        // Примеры из стандарта GS1 и Википедии: EAN-13, EAN-8, UPC-A, GTIN-14.
        for (ok in listOf("4006381333931", "96385074", "036000291452", "10012345678902", "4600000000008")) {
            assertTrue(ok, ProductsMath.isValidBarcode(ok))
        }
        // Неверная контрольная цифра.
        for (bad in listOf("4006381333932", "96385075", "036000291453", "10012345678903")) {
            assertFalse(bad, ProductsMath.isValidBarcode(bad))
        }
        assertEquals(1, ProductsMath.gtinCheckDigit("400638133393"))
        assertEquals(4, ProductsMath.gtinCheckDigit("9638507"))
    }

    @Test
    fun barcodeLengthAndDigits() {
        // Длины 9–11 и больше 14 — не стандартные коды; меньше 8 — тоже.
        for (bad in listOf("", "1234567", "123456789", "1234567890", "12345678901", "123456789012345")) {
            assertFalse(bad, ProductsMath.isValidBarcode(bad))
        }
        // Не цифры: буквы, пробел, знак, «чужие» цифры (арабские, полноширинные).
        for (bad in listOf("4006381a33931", "400638 333931", "-400638133393", "٤٠٠٦٣٨١٣٣٣٩٣١", "４００６３８１３３３９３１")) {
            assertFalse(bad, ProductsMath.isValidBarcode(bad))
        }
    }

    @Test
    fun upcEExpandsToUpcA() {
        assertEquals("012345000065", ProductsMath.expandUpcE("01234565"))
        assertEquals("042100005264", ProductsMath.expandUpcE("04252614"))
        // Последняя цифра 3 и 4 — свои правила развёртки.
        assertEquals("012300000451", ProductsMath.expandUpcE("01234531"))
        assertEquals("012340000053", ProductsMath.expandUpcE("01234543"))
        // Система счисления 1.
        val one = ProductsMath.expandUpcE("1123456" + ProductsMath.gtinCheckDigit("11234500006"))!!
        assertEquals("11234500006", one.dropLast(1))
        assertTrue(ProductsMath.isValidBarcode(one))
        // Шесть цифр — система 0, контрольная досчитывается.
        assertEquals("012345000065", ProductsMath.expandUpcE("123456"))
        // Неверная контрольная, система не 0/1, не цифры, не та длина.
        assertNull(ProductsMath.expandUpcE("01234566"))
        assertNull(ProductsMath.expandUpcE("21234565"))
        assertNull(ProductsMath.expandUpcE("0123456a"))
        assertNull(ProductsMath.expandUpcE("0123456"))
    }

    @Test
    fun scanNormalization() {
        assertEquals("012345000065", ProductsMath.normalizeScan(BarcodeKind.UPC_E, "01234565"))
        assertEquals("4006381333931", ProductsMath.normalizeScan(BarcodeKind.EAN_13, " 4006381333931 "))
        assertEquals("96385074", ProductsMath.normalizeScan(BarcodeKind.EAN_8, "96385074"))
        assertEquals("036000291452", ProductsMath.normalizeScan(BarcodeKind.UPC_A, "036000291452"))
        // Вид и длина не совпадают; неверная контрольная — не предлагается.
        assertNull(ProductsMath.normalizeScan(BarcodeKind.EAN_13, "036000291452"))
        assertNull(ProductsMath.normalizeScan(BarcodeKind.EAN_13, "4006381333932"))
        assertNull(ProductsMath.normalizeScan(BarcodeKind.UPC_E, "01234566"))
    }

    @Test
    fun barcodeLookupIgnoresLeadingZeros() {
        val a = product(uid = "v1", productUid = "a", barcode = "036000291452", name = "Сок")
        val b = product(uid = "v2", productUid = "b", barcode = "96385074", name = "Хлеб")
        val live = listOf(a, b)
        assertEquals(a, ProductsMath.findByBarcode(live, "0036000291452"))
        assertEquals(b, ProductsMath.findByBarcode(live, "96385074"))
        assertNull(ProductsMath.findByBarcode(live, "4006381333931"))
        // Один код у двух продуктов (созданы на двух телефонах) — самый свежий.
        val c = product(uid = "v3", productUid = "c", barcode = "036000291452", name = "Сок 2", utcMs = 5_000)
        assertEquals(c, ProductsMath.findByBarcode(live + c, "036000291452"))
        assertEquals("Сок 2", ProductsMath.barcodeOwner(live + c, "036000291452", exceptProductUid = "a"))
        assertNull(ProductsMath.barcodeOwner(listOf(a), "036000291452", exceptProductUid = "a"))
    }

    // ---------- Версии ----------

    @Test
    fun latestVersionByTimeThenUid() {
        val v1 = product(uid = "a", utcMs = 1_000, name = "Старое")
        val v2 = product(uid = "b", utcMs = 2_000, name = "Новое")
        val v3 = product(uid = "c", utcMs = 1_500, name = "Среднее")
        assertEquals(v2, ProductsMath.latest(listOf(v1, v2, v3))["prod"])
        assertEquals(v2, ProductsMath.latest(listOf(v3, v2, v1))["prod"])
        // Одно время — больший uid (одинаково на обоих телефонах при любом порядке прихода).
        val x = product(uid = "x", utcMs = 3_000, name = "X")
        val y = product(uid = "y", utcMs = 3_000, name = "Y")
        assertEquals(y, ProductsMath.latest(listOf(x, y))["prod"])
        assertEquals(y, ProductsMath.latest(listOf(y, x))["prod"])
    }

    @Test
    fun liveSkipsDeletedAndSortsByName() {
        val a = product(uid = "1", productUid = "a", name = "яблоко")
        val b = product(uid = "2", productUid = "b", name = "Банан")
        val c = product(uid = "3", productUid = "c", name = "арбуз")
        val b2 = product(uid = "4", productUid = "b", name = "Бананы", utcMs = 9_000)
        assertEquals(listOf("арбуз", "Бананы", "яблоко"), ProductsMath.live(listOf(a, b, c, b2), emptySet()).map { it.name })
        assertEquals(listOf("арбуз", "яблоко"), ProductsMath.live(listOf(a, b, c, b2), setOf("b")).map { it.name })
    }

    @Test
    fun searchByNameAndBarcode() {
        val live = listOf(
            product(uid = "1", productUid = "a", name = "Творог 5%"),
            product(uid = "2", productUid = "b", name = "Сырок творожный"),
            product(uid = "3", productUid = "c", name = "Ёгурт", barcode = "4006381333931"),
        )
        assertEquals(listOf("Творог 5%", "Сырок творожный"), ProductsMath.search(live, "твор").map { it.name })
        assertEquals(listOf("Ёгурт"), ProductsMath.search(live, "ЕГУ").map { it.name })
        assertEquals(listOf("Ёгурт"), ProductsMath.search(live, "638133").map { it.name })
        assertEquals(live, ProductsMath.search(live, "  "))
        assertTrue(ProductsMath.search(live, "мясо").isEmpty())
    }

    // ---------- Форма ----------

    @Test
    fun formParsesValues() {
        val f = ProductsMath.parseForm("  Гречка   ядрица ", "4006381333931", "343", "12,6", "3.3", "62", "80")
        assertTrue(f.errors.toString(), f.ok)
        assertEquals("Гречка ядрица", f.name)
        assertEquals("4006381333931", f.barcode)
        assertEquals(343, f.kcal)
        assertEquals(126, f.proteinDg)
        assertEquals(33, f.fatDg)
        assertEquals(620, f.carbsDg)
        assertEquals(80, f.portionG)
        // Необязательные поля пустые — null без ошибок.
        val empty = ProductsMath.parseForm("Кофе", "", "", "", " ", "", "")
        assertTrue(empty.ok)
        assertNull(empty.barcode)
        assertNull(empty.kcal)
        assertNull(empty.proteinDg)
        assertNull(empty.portionG)
        // Пробелы и дефисы в штрихкоде убираются.
        assertEquals("4006381333931", ProductsMath.parseForm("Х", "4 006381-333931", "", "", "", "", "").barcode)
    }

    @Test
    fun formErrors() {
        fun err(name: String = "Хлеб", barcode: String = "", kcal: String = "", p: String = "", f: String = "", c: String = "", portion: String = "") =
            ProductsMath.parseForm(name, barcode, kcal, p, f, c, portion).errors

        assertEquals("Введи название", err(name = "   ")[ProductField.NAME])
        assertTrue(ProductField.NAME in err(name = "x".repeat(61)))
        assertTrue(ProductField.NAME !in err(name = "x".repeat(60)))
        assertEquals("Только цифры", err(barcode = "40063813339a1")[ProductField.BARCODE])
        assertEquals("Нужно 8, 12, 13 или 14 цифр", err(barcode = "1234567890")[ProductField.BARCODE])
        assertEquals("Неверная контрольная цифра — проверь код", err(barcode = "4006381333932")[ProductField.BARCODE])
        // Ккал: 0–900, целое.
        assertTrue(ProductField.KCAL !in err(kcal = "0"))
        assertTrue(ProductField.KCAL !in err(kcal = "900"))
        assertEquals("От 0 до 900 ккал", plain(err(kcal = "901")[ProductField.KCAL]))
        assertTrue(ProductField.KCAL in err(kcal = "12,5"))
        assertTrue(ProductField.KCAL in err(kcal = "-1"))
        assertTrue(ProductField.KCAL in err(kcal = "99999999999"))
        // БЖУ: 0–100 г, не больше одного знака после запятой.
        assertTrue(ProductField.PROTEIN !in err(p = "100"))
        assertTrue(ProductField.PROTEIN !in err(p = "0,0"))
        assertEquals("От 0 до 100 г", plain(err(p = "100,1")[ProductField.PROTEIN]))
        assertEquals("Не больше одного знака после запятой", err(f = "1,25")[ProductField.FAT])
        assertEquals("Число, например 12,5", err(c = "1,2,3")[ProductField.CARBS])
        assertTrue(ProductField.CARBS in err(c = "abc"))
        assertTrue(ProductField.CARBS in err(c = ",5"))
        assertTrue(ProductField.CARBS in err(c = "-5"))
        // Вместе больше 100 г на 100 г.
        assertTrue(ProductField.MACROS in err(p = "50", f = "30", c = "20,1"))
        assertTrue(ProductField.MACROS !in err(p = "50", f = "30", c = "20"))
        // Порция: 1–2000 г, целое.
        assertTrue(ProductField.PORTION in err(portion = "0"))
        assertTrue(ProductField.PORTION in err(portion = "2001"))
        assertTrue(ProductField.PORTION !in err(portion = "2000"))
        assertTrue(ProductField.PORTION in err(portion = "50,5"))
        // Штрихкод занят другим продуктом.
        val taken = ProductsMath.parseForm("Х", "4006381333931", "", "", "", "", "") { "Йогурт" }
        assertEquals("Этот штрихкод уже у «Йогурт»", taken.errors[ProductField.BARCODE])
    }

    @Test
    fun formFieldsRoundTrip() {
        val v = product(barcode = "96385074", kcal = 52, p = 3, f = 0, c = 138, portion = 150)
        val fields = ProductsMath.formFields(v)
        assertEquals(listOf("Гречка", "96385074", "52", "0,3", "0,0", "13,8", "150"), fields)
        val form = ProductsMath.parseForm(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6])
        assertTrue(ProductsMath.sameValues(v, form))
        assertFalse(ProductsMath.sameValues(v.copy(kcal = 53), form))
        assertEquals(List(7) { "" }, ProductsMath.formFields(null))
    }

    @Test
    fun nameRules() {
        assertTrue(ProductsMath.isValidName("Гречка"))
        assertTrue(ProductsMath.isValidName("x".repeat(60)))
        assertFalse(ProductsMath.isValidName(""))
        assertFalse(ProductsMath.isValidName("x".repeat(61)))
        assertFalse(ProductsMath.isValidName(" Гречка"))
        assertFalse(ProductsMath.isValidName("Греч  ка"))
        assertFalse(ProductsMath.isValidName("Греч\nка"))
        assertFalse(ProductsMath.isValidName("Греч\u0000ка"))
    }

    // ---------- Порция и подсчёт ----------

    @Test
    fun entryGramsFallsBackToDefaultPortion() {
        assertEquals(200, ProductsMath.entryGrams(200, product(portion = 150)))
        assertEquals(150, ProductsMath.entryGrams(null, product(portion = 150)))
        assertNull(ProductsMath.entryGrams(null, product()))
        assertEquals(null to null, ProductsMath.parseGrams(""))
        assertEquals(250 to null, ProductsMath.parseGrams("250"))
        assertNull(ProductsMath.parseGrams("0").first)
    }

    @Test
    fun totalsOnlyFromKnownEntries() {
        val buckwheat = product(uid = "v1", kcal = 250, p = 125, f = 30, c = 400)
        val juice = product(uid = "v2", kcal = 100)
        val lines = listOf(
            line(150, buckwheat),
            line(200, juice),
            line(null, buckwheat), // без веса
            line(100, null), // карточка ещё не пришла
        )
        val t = ProductsMath.totals(lines)
        assertEquals(4, t.entries)
        assertEquals(2, t.kcalCounted)
        assertEquals(1, t.macrosCounted)
        // 250 × 1,5 + 100 × 2 = 575; белки 12,5 × 1,5 = 18,75 → 18,8.
        assertEquals("≈ 575 ккал (без 2 позиций)", plain(ProductsMath.kcalText(t)))
        assertEquals("≈ Б 18,8 · Ж 4,5 · У 60,0 г (без 3 позиций)", plain(ProductsMath.macrosText(t)))
        assertEquals(186L, ProductsMath.totals(listOf(line(150, buckwheat.copy(proteinDg = 124)))).proteinDg) // 18,6
        assertEquals(19L, ProductsMath.totals(listOf(line(15, buckwheat))).proteinDg) // 1,875 → 1,9
        // Все позиции с данными — без «≈».
        val full = ProductsMath.totals(listOf(line(150, buckwheat), line(50, buckwheat, uid = "e2")))
        assertEquals("500 ккал", plain(ProductsMath.kcalText(full)))
        assertEquals("Б 25,0 · Ж 6,0 · У 80,0 г", plain(ProductsMath.macrosText(full)))
        // Ни одной — понятная подпись и без БЖУ; позиций нет — null.
        val none = ProductsMath.totals(listOf(line(null, juice)))
        assertEquals("Ккал не посчитать: нет веса или ккал на 100 г", plain(ProductsMath.kcalText(none)))
        assertNull(ProductsMath.macrosText(none))
        assertNull(ProductsMath.kcalText(ProductsMath.totals(emptyList())))
        // Строка карточки приёма.
        assertEquals("4 продукта · ≈ 575 ккал (без 2 позиций)", plain(ProductsMath.mealLine(t)))
        assertEquals("2 продукта · 500 ккал", plain(ProductsMath.mealLine(full)))
        assertEquals("1 продукт", plain(ProductsMath.mealLine(none)))
        assertNull(ProductsMath.mealLine(ProductsMath.totals(emptyList())))
        // Округление ккал — после суммы: 3 × (333 ккал/100 г × 10 г = 33,3 ккал) = 99,9 → 100, а не 3 × 33 = 99.
        val third = product(uid = "v3", kcal = 333)
        val sum = ProductsMath.totals(List(3) { line(10, third, uid = "t$it") })
        assertEquals("100 ккал", plain(ProductsMath.kcalText(sum)))
        assertEquals(33L, ProductsMath.entryKcal(line(10, third)))
    }

    @Test
    fun entryTextsAndWords() {
        val v = product(kcal = 250)
        assertEquals("150 г · 375 ккал", plain(ProductsMath.entryText(line(150, v))))
        assertEquals("150 г", plain(ProductsMath.entryText(line(150, product()))))
        assertEquals("без веса", ProductsMath.entryText(line(null, v)))
        assertEquals("Гречка", ProductsMath.entryName(line(1, v)))
        assertEquals("Продукт ещё не пришёл со второго телефона", ProductsMath.entryName(line(1, null)))
        assertEquals("250 ккал · Б 12,5 · Ж 0,0 на 100 г", plain(ProductsMath.per100Text(product(kcal = 250, p = 125, f = 0))))
        assertEquals("Пищевая ценность не указана", ProductsMath.per100Text(product()))
        assertEquals("без 1 позиции", plain(ProductsMath.withoutEntries(1)))
        assertEquals("без 2 позиций", plain(ProductsMath.withoutEntries(2)))
        assertEquals("без 11 позиций", plain(ProductsMath.withoutEntries(11)))
        assertEquals("без 21 позиции", plain(ProductsMath.withoutEntries(21)))
        assertEquals("продукт", ProductsMath.productsWord(1))
        assertEquals("продукта", ProductsMath.productsWord(3))
        assertEquals("продуктов", ProductsMath.productsWord(12))
        assertEquals("продукт", ProductsMath.productsWord(21))
    }

    // ---------- Схема ----------

    @Test
    fun migration11AddsProductTables() {
        assertTrue(Diary.SCHEMA_VERSION >= 11)
        val step = Diary.MIGRATIONS[10]
        val sql = step.joinToString("\n")
        for (part in listOf(
            "CREATE TABLE product_versions", "product_uid TEXT NOT NULL", "name TEXT NOT NULL", "barcode TEXT",
            "kcal INTEGER", "protein_dg INTEGER", "fat_dg INTEGER", "carbs_dg INTEGER", "portion_g INTEGER",
            "CREATE TABLE product_deletes", "CREATE TABLE meal_entries", "meal_uid TEXT NOT NULL",
            "version_uid TEXT NOT NULL", "grams INTEGER", "CREATE TABLE meal_entry_deletes", "entry_uid TEXT NOT NULL",
            "CREATE INDEX idx_product_versions_author ON product_versions(author, epoch, aseq)",
            "CREATE INDEX idx_product_deletes_author ON product_deletes(author, epoch, aseq)",
            "CREATE INDEX idx_meal_entries_author ON meal_entries(author, epoch, aseq)",
            "CREATE INDEX idx_meal_entry_deletes_author ON meal_entry_deletes(author, epoch, aseq)",
        )) assertTrue(part, part in sql)
        // У всех четырёх таблиц — поля факта и момент записи.
        val tables = step.filter { it.startsWith("CREATE TABLE") }
        assertEquals(4, tables.size)
        for (t in tables) for (col in listOf(
            "uid TEXT PRIMARY KEY NOT NULL", "author TEXT NOT NULL", "epoch TEXT NOT NULL", "aseq INTEGER NOT NULL",
            "utc_ms INTEGER NOT NULL", "day INTEGER NOT NULL", "zone_id TEXT NOT NULL",
        )) assertTrue("$col в ${t.lineSequence().first()}", col in t)
        // Только новые таблицы и индексы: прежние данные шаг не трогает. Без UPSERT (Android 10: SQLite 3.22).
        assertTrue(step.all { it.startsWith("CREATE ") })
        assertTrue("ON CONFLICT" !in sql && "RETURNING" !in sql)
        assertTrue(BackupMath.requiredTables(11).containsAll(BackupMath.PRODUCT_TABLES))
        assertFalse(BackupMath.requiredTables(10).any { it in BackupMath.PRODUCT_TABLES })
        assertTrue(BackupMath.requiredTables(10).contains("card_attempts"))
    }
}
