package com.nextlevellife.app.sync

import com.nextlevellife.app.data.Diary
import com.nextlevellife.app.data.SharedSettingsCodec
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.ui.SettingsMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шаг схемы 7 → 8 (проверен прогоном на SQLite 3.54 отдельно: данные версии 7 получают автора, поколение,
 * номера и uid; пустая база проходит все шаги) и мелкая чистая логика встраивания.
 */
class SyncSchemaTest {
    @Test
    fun migration8AddsSyncTablesAndOwnership() {
        assertTrue(Diary.SCHEMA_VERSION >= 8)
        val sql = Diary.MIGRATIONS[7].joinToString("\n")
        for (part in listOf(
            "ALTER TABLE balance_ops ADD COLUMN rev INTEGER NOT NULL DEFAULT 0",
            "UPDATE balance_ops SET rev = seq",
            "'ops_rev'", "'ops_epoch'", "'facts_epoch'", "'facts_seq'",
            "ALTER TABLE sets ADD COLUMN run_uid TEXT",
            "CREATE TABLE peer_ops", "PRIMARY KEY (device_id, id)",
            "CREATE TABLE limit_moves", "CREATE TABLE shared_settings", "CREATE TABLE sync_cursors",
            "CREATE UNIQUE INDEX idx_sets_uid ON sets(uid)",
        )) assertTrue(part, part in sql)
        // device_id создаётся в самом шаге (через мигрируемую базу), а не через Ledger.
        assertTrue("INSERT OR IGNORE INTO meta (key, value) VALUES ('device_id'" in sql)
        // uid старых строк детерминирован (device_id, таблица, id, время): повторная миграция той же истории
        // (восстановление копии схемы 7) даёт те же uid — второй телефон не получит подходы дважды.
        assertTrue("uid = 'm7-' || (SELECT value FROM meta WHERE key = 'device_id') || '-set-' || id || '-' || time_ms" in sql)
        assertTrue("uid = 'm7-' || (SELECT value FROM meta WHERE key = 'device_id') || '-run-' || id || '-' || time_ms" in sql)
        assertTrue(Diary.MIGRATIONS[7].none { it.startsWith("UPDATE sets SET author") && "randomblob" in it })
        // Без UPSERT: на Android 10 SQLite 3.22.
        assertTrue("ON CONFLICT" !in sql && "RETURNING" !in sql)
    }

    @Test
    fun sharedSettingValues() {
        assertEquals("1,1380,420", SharedSettingsCodec.night(true, 1380, 420))
        assertEquals(Triple(false, 60, 120), SharedSettingsCodec.parseNight("0,60,120"))
        assertNull(SharedSettingsCodec.parseNight("1,1440,0"))
        assertNull(SharedSettingsCodec.parseNight("x"))
        assertEquals("", SharedSettingsCodec.steps(null))
        assertNull(SharedSettingsCodec.parseSteps("")!!.getOrNull())
        assertEquals(20_000L, SharedSettingsCodec.parseSteps("20000")!!.getOrNull())
        assertNull(SharedSettingsCodec.parseSteps("abc"))
    }

    @Test
    fun streakFromDiaryDays() {
        assertNull(Store.streakOf(emptySet()))
        assertEquals(100L to 3, Store.streakOf(setOf(90, 98, 99, 100)))
        assertEquals(5L to 1, Store.streakOf(setOf(5, 3)))
    }

    @Test
    fun secondPhoneLines() {
        assertEquals("На этом телефоне 25 мин · на втором −3 мин", SettingsMath.walletsLine(25 * 60_000L + 59_000, -3 * 60_000L))
        assertEquals("На втором телефоне есть ещё 12 мин — включи его экран рядом", SettingsMath.peerResourceLine(12 * 60_000L))
        assertNull(SettingsMath.peerResourceLine(59_000))
        assertEquals("Нет пары", SettingsMath.pairStatus(null))
        assertEquals("Связан с Fold", SettingsMath.pairStatus("Fold"))
        assertEquals("Обмена ещё не было", SettingsMath.lastSyncLine(null, false, 0))
        assertEquals("Второй телефон не найден", SettingsMath.lastSyncLine(null, true, 0))
    }
}
