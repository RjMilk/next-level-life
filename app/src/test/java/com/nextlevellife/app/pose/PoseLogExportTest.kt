package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class PoseLogExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun log(dir: File, name: String, ageMs: Long, text: String = "# nll-poselog v4 exercise=swings\n"): File =
        File(dir, name).apply {
            writeText(text)
            setLastModified(now - ageMs)
        }

    @Test
    fun selectsCsvInPeriodFromBothDirs() {
        val ext = tmp.newFolder("ext")
        val int = tmp.newFolder("int")
        log(ext, "20261117-100000-swings.csv", day / 2)
        log(ext, "20261110-100000-thruster.csv", 8 * day)
        log(ext, "20261116-090000-squats.csv", 2 * day)
        log(ext, "notes.txt", 0)
        log(int, "20261117-120000-row.csv", day / 3)
        val three = PoseLogExport.select(listOf(ext, int, File(tmp.root, "нет")), now, 3)
        assertEquals(
            listOf("20261116-090000-squats.csv", "20261117-100000-swings.csv", "20261117-120000-row.csv"),
            three.map { it.name },
        )
        assertEquals(2, PoseLogExport.select(listOf(ext, int), now, 1).size)
        assertEquals(4, PoseLogExport.select(listOf(ext, int), now, 30).size)
    }

    @Test
    fun packKeepsEveryFileAndContent() {
        val a = tmp.newFolder("a")
        val b = tmp.newFolder("b")
        val f1 = log(a, "20261117-100000-swings.csv", 0, "один\n")
        val f2 = log(b, "20261117-100000-swings.csv", 0, "два\n")
        val f3 = log(a, "20261117-110000-thruster.csv", 0, "три\n")
        val packed = PoseLogExport.pack(listOf(f1, f2, f3), File(tmp.root, "exports/poselog-x.zip"))
        assertEquals(3, packed.files)
        assertTrue(packed.bytes > 0)
        ZipFile(packed.zip).use { zip ->
            val entries = zip.entries().toList().associate { it.name to zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
            assertEquals(
                mapOf(
                    "20261117-100000-swings.csv" to "один\n",
                    "20261117-100000-swings-2.csv" to "два\n",
                    "20261117-110000-thruster.csv" to "три\n",
                ),
                entries,
            )
        }
    }

    @Test
    fun texts() {
        assertEquals("820 Б", PoseLogExport.sizeText(820))
        assertEquals("12 КБ", PoseLogExport.sizeText(12 * 1024 + 100))
        assertEquals("1,4 МБ", PoseLogExport.sizeText(1_468_006))
        assertEquals("1 запись", PoseLogExport.countText(1))
        assertEquals("3 записи", PoseLogExport.countText(3))
        assertEquals("11 записей", PoseLogExport.countText(11))
        assertEquals("21 запись", PoseLogExport.countText(21))
        assertEquals("40 записей", PoseLogExport.countText(40))
        assertTrue(PoseLogExport.zipName(now).matches(Regex("poselog-\\d{8}-\\d{6}\\.zip")))
    }
}
