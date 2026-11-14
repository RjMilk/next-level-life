package com.nextlevellife.app.sync

import com.nextlevellife.app.sync.transport.PairingInvite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingInviteTest {
    private val id = "3f2c9a1e-7b4d-4c8e-9f10-2a3b4c5d6e7f"
    private val token = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun roundTrip_latinNameWithSpaces() {
        val invite = PairingInvite(id, token, "Galaxy Z Fold8")
        val text = invite.format()
        assertTrue(text.startsWith("nll-pair:1:$id:"))
        assertFalse("в QR не должно быть пробелов", text.contains(' '))
        assertTrue(text.endsWith(":Galaxy%20Z%20Fold8"))
        val parsed = PairingInvite.parse(text)
        assertEquals(invite, parsed)
        assertArrayEquals(token, parsed!!.token)
    }

    @Test
    fun roundTrip_cyrillicAndColon() {
        val name = "Телефон Ивана: S25"
        val parsed = PairingInvite.parse(PairingInvite(id, token, name).format())
        assertNotNull(parsed)
        assertEquals(name, parsed!!.name)
        assertEquals(id, parsed.deviceId)
    }

    @Test
    fun tokenIsBase64UrlWithoutPadding() {
        val text = PairingInvite(id, ByteArray(32) { -1 }, "A").format()
        val tokenPart = text.split(':')[3]
        assertFalse(tokenPart.contains('='))
        assertFalse(tokenPart.contains('+') || tokenPart.contains('/'))
        assertEquals(43, tokenPart.length)
    }

    @Test
    fun brokenText_returnsNull() {
        val good = PairingInvite(id, token, "S25").format()
        val t = Base64.getUrlEncoder().withoutPadding().encodeToString(token)
        val short = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        assertNotNull(PairingInvite.parse(good))
        assertNull(PairingInvite.parse(null))
        assertNull(PairingInvite.parse(""))
        assertNull(PairingInvite.parse("https://example.com"))
        assertNull(PairingInvite.parse(good.replace("nll-pair:1", "nll-pair:2")))
        assertNull(PairingInvite.parse(good.replace("nll-pair", "nll-pain")))
        assertNull(PairingInvite.parse("nll-pair:1:$id:$t"))
        assertNull(PairingInvite.parse("nll-pair:1:$id:$short:S25"))
        assertNull(PairingInvite.parse("nll-pair:1:$id:@@@@:S25"))
        assertNull(PairingInvite.parse("nll-pair:1:bad id!:$t:S25"))
        assertNull(PairingInvite.parse("nll-pair:1:$id:$t:"))
        assertNull(PairingInvite.parse("nll-pair:1:$id:$t:%20%20"))
        assertNull(PairingInvite.parse("nll-pair:1:$id:$t:%E0%A4%A"))
        assertNull(PairingInvite.parse(good + "x".repeat(600)))
    }

    @Test
    fun longName_isTrimmed() {
        val parsed = PairingInvite.parse(PairingInvite(id, token, "Я".repeat(100)).format())
        assertEquals(64, parsed!!.name.length)
    }
}
