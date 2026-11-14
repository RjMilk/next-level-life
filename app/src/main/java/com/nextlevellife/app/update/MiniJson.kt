package com.nextlevellife.app.update

/**
 * Минимальный разбор JSON без Android (org.json в JVM-тестах недоступен): объект → [Map],
 * массив → [List], строка → [String], число → [Long] или [Double], true/false → [Boolean], null → null.
 * Нужен только для ответа GitHub со списком релизов; ошибка формата — [IllegalArgumentException].
 */
internal object MiniJson {
    /** Наибольшая вложенность: ответ GitHub — 4–5 уровней, защита от переполнения стека на мусоре. */
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        require(p.pos == text.length) { "лишние символы после JSON на позиции ${p.pos}" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg на позиции $pos")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("слишком глубокая вложенность")
            if (pos >= s.length) fail("неожиданный конец")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("неожиданный символ '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("ожидалось $word")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return m
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("ожидался ключ")
                val k = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("ожидалось ':'")
                pos++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("неожиданный конец объекта")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return m
                    }
                    else -> fail("ожидалось ',' или '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return l
            }
            while (true) {
                skipWs()
                l += value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("неожиданный конец массива")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return l
                    }
                    else -> fail("ожидалось ',' или ']'")
                }
            }
        }

        private fun str(): String {
            pos++ // "
            val b = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("незакрытая строка")
                val c = s[pos++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("незакрытая строка")
                        when (val e = s[pos++]) {
                            '"' -> b.append('"')
                            '\\' -> b.append('\\')
                            '/' -> b.append('/')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("короткая \\u-последовательность")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("плохая \\u-последовательность")
                                b.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("неизвестная escape-последовательность '\\$e'")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var fractional = false
            if (pos < s.length && s[pos] == '.') {
                fractional = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                fractional = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val t = s.substring(start, pos)
            if (t == "-" || t.isEmpty()) fail("плохое число")
            return if (fractional) t.toDoubleOrNull() ?: fail("плохое число") else t.toLongOrNull() ?: t.toDouble()
        }
    }
}
