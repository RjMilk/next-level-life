package com.nextlevellife.app.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthDiagnosticMathTest {
    @Test
    fun readsAllPagesAndKeepsOriginsSeparate() = runBlocking {
        val totals = LinkedHashMap<String, DiagnosticRawTotal>()
        var calls = 0
        HealthDiagnosticMath.readRawPages(totals) { token ->
            assertEquals(if (calls == 0) null else calls.toString(), token)
            calls++
            DiagnosticRawPage(
                List(1000) { DiagnosticRawStep("watch", 100) } + DiagnosticRawStep("phone", 2),
                if (calls < 4) calls.toString() else "",
            )
        }
        assertEquals(4, calls)
        assertEquals(DiagnosticRawTotal(400_000, 4000), totals["watch"])
        assertEquals(DiagnosticRawTotal(8, 4), totals["phone"])
    }

    @Test
    fun repeatedProviderTokenFailsInsteadOfLooping() = runBlocking {
        val totals = LinkedHashMap<String, DiagnosticRawTotal>()
        var calls = 0
        val result = runCatching {
            HealthDiagnosticMath.readRawPages(totals) {
                calls++
                DiagnosticRawPage(listOf(DiagnosticRawStep("watch", 100)), "same")
            }
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(2, calls)
    }

    @Test
    fun providerErrorRetainsOnlyDevicePrefixes() {
        val me = "abcd-0123456789"
        val peer = "wxyz-9876543210"
        val report = HealthDiagnosticMath.maskDeviceIds("Телефон $me, ошибка для $peer; снова $me", listOf(me, peer))
        assertFalse(report.contains(me))
        assertFalse(report.contains(peer))
        assertEquals("Телефон abcd, ошибка для wxyz; снова abcd", report)
    }
}
