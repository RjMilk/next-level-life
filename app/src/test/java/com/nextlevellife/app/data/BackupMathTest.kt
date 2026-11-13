package com.nextlevellife.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMathTest {

    @Test
    fun currentManifestAccepted() {
        assertNull(BackupMath.checkManifest(BackupMath.FORMAT_VERSION, 2, supportedSchema = 2))
    }

    @Test
    fun olderSchemaWithLedgerAccepted() {
        assertNull(BackupMath.checkManifest(1, 2, supportedSchema = 3))
    }

    @Test
    fun newerFormatRejectedWithReason() {
        val reason = BackupMath.checkManifest(BackupMath.FORMAT_VERSION + 1, 2, supportedSchema = 2)
        assertNotNull(reason)
        assertTrue(reason!!.contains("более новой версией"))
    }

    @Test
    fun newerSchemaRejected() {
        val reason = BackupMath.checkManifest(1, 3, supportedSchema = 2)
        assertNotNull(reason)
        assertTrue(reason!!.contains("Обновите приложение"))
    }

    @Test
    fun missingOrBrokenVersionsRejected() {
        assertNotNull(BackupMath.checkManifest(null, 2, supportedSchema = 2))
        assertNotNull(BackupMath.checkManifest(0, 2, supportedSchema = 2))
        assertNotNull(BackupMath.checkManifest(-1, 2, supportedSchema = 2))
        assertNotNull(BackupMath.checkManifest(1, null, supportedSchema = 2))
    }

    @Test
    fun schemaWithoutLedgerRejected() {
        assertNotNull(BackupMath.checkManifest(1, 1, supportedSchema = 2))
    }

    @Test
    fun dbVersionMustMatchManifest() {
        assertNull(BackupMath.checkDbVersion(2, 2, 2))
        assertNotNull(BackupMath.checkDbVersion(1, 2, 2))
        assertNotNull(BackupMath.checkDbVersion(3, 3, 2))
    }

    // ---------- Прерванное восстановление ----------

    private fun action(marker: Boolean = true, db: Boolean, bak: Boolean, usable: Boolean) =
        BackupMath.recoveryAction(marker, db, bak, usable)

    @Test
    fun noMarkerMeansNothingToDo() {
        assertEquals(BackupMath.RestoreRecovery.NONE, action(marker = false, db = true, bak = true, usable = true))
        assertEquals(BackupMath.RestoreRecovery.NONE, action(marker = false, db = false, bak = true, usable = false))
    }

    @Test
    fun killedBeforeMovingKeepsCurrentDb() {
        // Маркер записан, основной файл ещё на месте и не отложен.
        assertEquals(BackupMath.RestoreRecovery.KEEP_CURRENT, action(db = true, bak = false, usable = false))
    }

    @Test
    fun killedWhileOldDbAsideRollsBack() {
        // Основной файл уже в .restore-bak, новая база ещё не положена: прежнюю вернуть, не создавать пустую.
        assertEquals(BackupMath.RestoreRecovery.ROLL_BACK, action(db = false, bak = true, usable = false))
    }

    @Test
    fun installedIntactNewDbIsKept() {
        assertEquals(BackupMath.RestoreRecovery.KEEP_NEW, action(db = true, bak = true, usable = true))
    }

    @Test
    fun installedBrokenNewDbRollsBack() {
        assertEquals(BackupMath.RestoreRecovery.ROLL_BACK, action(db = true, bak = true, usable = false))
    }

    @Test
    fun killedWhileCleaningUpKeepsNewDb() {
        // Основной .restore-bak уже удалён (настройки записаны раньше): подмена фактически завершена.
        assertEquals(BackupMath.RestoreRecovery.KEEP_CURRENT, action(db = true, bak = false, usable = true))
    }

    @Test
    fun stepsRewardRestoredOnlyWithItsStartDay() {
        assertEquals(20_400L, Store.restoredStepsSince(enabled = true, sinceDay = 20_400L))
        // Старая копия без дня включения: переключатель остаётся выключенным.
        assertNull(Store.restoredStepsSince(enabled = true, sinceDay = null))
        assertNull(Store.restoredStepsSince(enabled = false, sinceDay = 20_400L))
    }

    @Test
    fun requiredTablesIncludeLedger() {
        assertTrue(BackupMath.REQUIRED_TABLES.containsAll(listOf("balance_ops", "account", "meta", "sets", "complex_runs")))
    }
}
