package com.nextlevellife.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MiniJsonTest {
    @Test
    fun `значения всех типов`() {
        val v = MiniJson.parse("""{"a": 1, "b": -2.5, "c": "x", "d": true, "e": false, "f": null, "g": [1, [2], {}], "h": 1e3}""")
        assertEquals(
            mapOf("a" to 1L, "b" to -2.5, "c" to "x", "d" to true, "e" to false, "f" to null, "g" to listOf(1L, listOf(2L), emptyMap<String, Any?>()), "h" to 1000.0),
            v,
        )
        assertNull(MiniJson.parse(" null "))
        assertEquals(emptyList<Any?>(), MiniJson.parse("[ ]"))
    }

    @Test
    fun `escape-последовательности и юникод`() {
        assertEquals("a\"b\\c/d\n\t«ё»\uD83D\uDE00", MiniJson.parse("\"a\\\"b\\\\c\\/d\\n\\t\\u00ab\\u0451\\u00BB\\ud83d\\ude00\""))
        assertEquals("Привет", MiniJson.parse("\"Привет\""))
    }

    @Test
    fun `большие числа не теряются`() {
        assertEquals(3_000_000_000L, MiniJson.parse("3000000000"))
    }

    @Test
    fun `битый JSON — ошибка, а не зависание`() {
        listOf("", "{", "[1,", "{\"a\" 1}", "\"abc", "[1] 2", "tru", "-", "{\"a\":1,}", "\"\\x\"", "\"\\u12\"").forEach { s ->
            assertThrows(IllegalArgumentException::class.java) { MiniJson.parse(s) }
        }
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("[".repeat(100) + "]".repeat(100)) }
    }
}
