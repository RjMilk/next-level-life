package com.nextlevellife.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NutritionMathTest {
    private val msk = ZoneId.of("Europe/Moscow")

    private fun meal(uid: String, y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = msk): MealItem {
        val at = LocalDateTime.of(y, mo, d, h, mi).atZone(zone)
        return MealItem(uid, "aaa", at.toInstant().toEpochMilli(), at.toLocalDate().toEpochDay(), zone.id, "$uid.jpg", null)
    }

    @Test
    fun windowFirstAndLast() {
        val meals = listOf(meal("b", 2026, 11, 14, 13, 5), meal("c", 2026, 11, 14, 20, 15), meal("a", 2026, 11, 14, 8, 40))
        assertEquals("08:40 – 20:15 · 11 ч 35 мин", NutritionMath.windowText(meals))
        val w = NutritionMath.window(meals)!!
        assertEquals("a", w.first.uid)
        assertEquals("c", w.last.uid)
        assertEquals(3, w.count)
        assertEquals("Питание: 3 приёма · окно 08:40 – 20:15 · 11 ч 35 мин", NutritionMath.dayLine(meals))
        assertEquals("Сегодня: 3 приёма · 08:40 – 20:15 · 11 ч 35 мин", NutritionMath.homeLine(meals))
    }

    @Test
    fun singleAndEmpty() {
        val one = listOf(meal("a", 2026, 11, 14, 8, 40))
        assertEquals("Один приём", NutritionMath.windowText(one))
        assertEquals("Питание: 1 приём", NutritionMath.dayLine(one))
        assertEquals("Сегодня: 1 приём", NutritionMath.homeLine(one))
        assertNull(NutritionMath.windowText(emptyList()))
        assertNull(NutritionMath.dayLine(emptyList()))
        assertEquals("Сегодня приёмов нет", NutritionMath.homeLine(emptyList()))
    }

    @Test
    fun accountingMidnightSplitsDays() {
        // 23:50 и 00:10 — разные учётные сутки: у каждого свой «Один приём», а не окно через полночь.
        val late = meal("late", 2026, 11, 14, 23, 50)
        val early = meal("early", 2026, 11, 15, 0, 10)
        assertEquals(late.day + 1, early.day)
        val byDay = listOf(late, early).groupBy { it.day }
        assertEquals("Один приём", NutritionMath.windowText(byDay[late.day]!!))
        assertEquals("Один приём", NutritionMath.windowText(byDay[early.day]!!))
        // Сутки — по учётному поясу: тот же момент в UTC ещё 14-е число.
        val utcDay = LedgerMath.accountingDay(early.capturedUtcMs, ZoneId.of("UTC"))
        assertEquals(late.day, utcDay)
    }

    @Test
    fun deletedMealsDoNotCount() {
        val meals = listOf(meal("a", 2026, 11, 14, 8, 0), meal("b", 2026, 11, 14, 12, 0), meal("c", 2026, 11, 14, 21, 30))
        val visible = NutritionMath.visible(meals, setOf("c"))
        assertEquals("08:00 – 12:00 · 4 ч", NutritionMath.windowText(visible))
        assertEquals("Один приём", NutritionMath.windowText(NutritionMath.visible(meals, setOf("a", "c"))))
        assertNull(NutritionMath.windowText(NutritionMath.visible(meals, setOf("a", "b", "c"))))
    }

    @Test
    fun timesInMealZone() {
        val a = meal("a", 2026, 11, 14, 9, 0, ZoneId.of("UTC"))
        assertEquals("09:00", NutritionMath.timeText(a.capturedUtcMs, "UTC"))
        assertEquals("12:00", NutritionMath.timeText(a.capturedUtcMs, "Europe/Moscow"))
        assertEquals("45 мин", NutritionMath.durationText(45 * 60_000L + 59_000))
        assertEquals("2 ч", NutritionMath.durationText(2 * 3_600_000L))
        assertEquals("0 мин", NutritionMath.durationText(-5))
    }

    @Test
    fun words() {
        assertEquals("приём", NutritionMath.mealsWord(1))
        assertEquals("приёма", NutritionMath.mealsWord(3))
        assertEquals("приёмов", NutritionMath.mealsWord(5))
        assertEquals("приёмов", NutritionMath.mealsWord(11))
        assertEquals("приём", NutritionMath.mealsWord(21))
    }

    @Test
    fun notes() {
        assertNull(NutritionMath.normalizeNote("   \n "))
        assertNull(NutritionMath.normalizeNote(null))
        assertEquals("гречка с курицей", NutritionMath.normalizeNote("  гречка\n\nс  курицей "))
        assertEquals(NutritionMath.MAX_NOTE_CHARS, NutritionMath.normalizeNote("x".repeat(500))!!.length)
    }

    @Test
    fun safePhotoPathsOnRestore() {
        val uid = "3f2a9c1e-7b1d-4e8a-9f00-123456789abc"
        assertTrue(NutritionMath.isSafeUid(uid))
        assertEquals("$uid.jpg", NutritionMath.backupPhotoName(NutritionMath.backupEntryFor(NutritionMath.photoNameFor(uid))))
        for (bad in listOf(
            "meals/../diary.db", "meals/../../shared_prefs/nll.xml", "/meals/a.jpg", "meals//a.jpg", "meals/sub/a.jpg",
            "meals/a.jpg/", "meals\\a.jpg", "meals/..\\a.jpg", "meals/.jpg", "meals/a.png", "meals/a.jpg.exe",
            "meals/-a.jpg", "meals/a b.jpg", "a.jpg", "diary.db", "meals/", "MEALS/a.jpg", "meals/а.jpg",
        )) assertNull(bad, NutritionMath.backupPhotoName(bad))
        assertFalse(NutritionMath.isSafePhotoName("../a.jpg"))
        assertFalse(NutritionMath.isSafeUid("a.b"))
    }

    @Test
    fun photoScaleAndOrientation() {
        assertEquals(1600 to 1200, NutritionMath.scaledSize(4000, 3000))
        assertEquals(1200 to 1600, NutritionMath.scaledSize(3000, 4000))
        assertEquals(800 to 600, NutritionMath.scaledSize(800, 600))
        assertEquals(2, NutritionMath.sampleSize(4000, 3000, 1600))
        assertEquals(1, NutritionMath.sampleSize(1920, 1440, 1600))
        assertEquals(90 to false, NutritionMath.exifTransform(6))
        assertEquals(270 to false, NutritionMath.exifTransform(8))
        assertEquals(180 to false, NutritionMath.exifTransform(3))
        assertEquals(0 to false, NutritionMath.exifTransform(1))
        assertEquals(0 to false, NutritionMath.exifTransform(0))
        assertEquals(270 to true, NutritionMath.exifTransform(5))
        assertEquals(90 to true, NutritionMath.exifTransform(7))
    }

    @Test
    fun migration9AddsMealTables() {
        assertEquals(9, Diary.SCHEMA_VERSION)
        val sql = Diary.MIGRATIONS[8].joinToString("\n")
        for (part in listOf(
            "CREATE TABLE meals", "uid TEXT PRIMARY KEY NOT NULL", "author TEXT NOT NULL", "epoch TEXT NOT NULL",
            "aseq INTEGER NOT NULL", "captured_utc_ms INTEGER NOT NULL", "accounting_day INTEGER NOT NULL",
            "zone_id TEXT NOT NULL", "photo_name TEXT", "note TEXT", "deleted INTEGER NOT NULL DEFAULT 0",
            "CREATE TABLE meal_deletes", "meal_uid TEXT NOT NULL", "utc_ms INTEGER NOT NULL",
            "CREATE INDEX idx_meals_author ON meals(author, epoch, aseq)",
            "CREATE INDEX idx_meal_deletes_author ON meal_deletes(author, epoch, aseq)",
        )) assertTrue(part, part in sql)
        // Только новые таблицы: прежние данные шаг не трогает. Без UPSERT (Android 10: SQLite 3.22).
        assertTrue(Diary.MIGRATIONS[8].all { it.startsWith("CREATE ") })
        assertTrue("ON CONFLICT" !in sql && "RETURNING" !in sql)
        // Копия схемы 9 обязана содержать таблицы питания; старые копии догоняют миграцией.
        assertTrue(BackupMath.requiredTables(9).containsAll(listOf("meals", "meal_deletes")))
        assertFalse(BackupMath.requiredTables(8).contains("meals"))
    }
}
