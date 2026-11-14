package com.nextlevellife.app.sync

import com.nextlevellife.app.sync.transport.Hkdf
import org.junit.Assert.assertEquals
import org.junit.Test

/** HKDF-SHA256 по тестовым векторам RFC 5869 (приложение A, случаи 1–3). */
class HkdfTest {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun range(from: Int, toInclusive: Int) = ByteArray(toInclusive - from + 1) { (from + it).toByte() }

    @Test
    fun rfc5869_case1_basic() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = range(0x00, 0x0c)
        val info = range(0xf0, 0xf9)
        val prk = Hkdf.extract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.hex())
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hkdf.expand(prk, info, 42).hex(),
        )
    }

    @Test
    fun rfc5869_case2_longInputs() {
        val ikm = range(0x00, 0x4f)
        val salt = range(0x60, 0xaf)
        val info = range(0xb0, 0xff)
        val prk = Hkdf.extract(salt, ikm)
        assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", prk.hex())
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            Hkdf.expand(prk, info, 82).hex(),
        )
    }

    @Test
    fun rfc5869_case3_emptySaltAndInfo() {
        val ikm = ByteArray(22) { 0x0b }
        val prk = Hkdf.extract(ByteArray(0), ikm)
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", prk.hex())
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hkdf.derive(null, ikm, ByteArray(0), 42).hex(),
        )
    }

    @Test
    fun hexHelperSanity() {
        assertEquals("00ff10", hex("00ff10").hex())
    }
}
