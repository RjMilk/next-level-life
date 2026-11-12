package com.nextlevellife.app.data

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

    @Test
    fun requiredTablesIncludeLedger() {
        assertTrue(BackupMath.REQUIRED_TABLES.containsAll(listOf("balance_ops", "account", "meta", "sets", "complex_runs")))
    }
}
