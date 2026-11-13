package com.nextlevellife.app.block

import com.nextlevellife.app.data.UsageDebt

/**
 * Отсчёт просмотра заблокированного приложения для списания (чистая логика, без Android).
 * Интервал от прошлой отметки до [take] считается по состоянию экрана, которое было в этом интервале:
 * при смене состояния экрана служба сначала закрывает интервал ([take] по прежнему состоянию),
 * потом меняет состояние ([setScreen]). Так выключение экрана перед тиком не обнуляет уже просмотренное,
 * а включение перед задержавшимся тиком не списывает время, пока экран был выключен.
 * Время — монотонные часы (мс).
 */
internal class SpendMeter(screenOn: Boolean = true) {
    /** Экран включён (просмотр идёт). */
    var screenOn: Boolean = screenOn
        private set

    private var lastAtMs = 0L

    /** Начать отсчёт заново с [nowMs] (новая сессия списания). */
    fun restart(nowMs: Long) {
        lastAtMs = nowMs
    }

    /** Закрывает интервал до [nowMs] по текущему состоянию экрана: сколько в нём смотрели (≥ 0). */
    fun take(nowMs: Long): Long {
        val elapsed = nowMs - lastAtMs
        lastAtMs = nowMs
        return if (screenOn && elapsed > 0L) elapsed else 0L
    }

    /** Новое состояние экрана. Интервал до этого момента надо закрыть раньше ([take]). */
    fun setScreen(on: Boolean) {
        screenOn = on
    }
}

/**
 * Неоплаченные просмотры ([UsageDebt]) в meta (ключ `usage_debts`): JSON-массив объектов
 * `{"start":…,"end":…,"pkg":"…","session":"…"}`. Чистая логика, без Android.
 */
internal object UsageDebtCodec {
    fun encode(debts: List<UsageDebt>): String = debts.joinToString(",", "[", "]") { d ->
        "{\"start\":${d.startMs},\"end\":${d.endMs},\"pkg\":${quote(d.packageName)},\"session\":${quote(d.sessionId)}}"
    }

    /** Список долгов; null — строка повреждена. */
    fun decode(text: String): List<UsageDebt>? = try {
        Parser(text).parseDebts()
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Разбор ровно того вида, который пишет [encode] (порядок полей любой, пробелы допускаются). */
    private class Parser(private val s: String) {
        private var i = 0

        fun parseDebts(): List<UsageDebt> {
            val out = ArrayList<UsageDebt>()
            expect('[')
            if (peek() == ']') {
                i++
            } else {
                while (true) {
                    out += parseDebt()
                    val c = next()
                    if (c == ']') break
                    require(c == ',') { "ожидалась запятая" }
                }
            }
            skipSpaces()
            require(i == s.length) { "лишние символы" }
            return out
        }

        private fun parseDebt(): UsageDebt {
            expect('{')
            var start: Long? = null
            var end: Long? = null
            var pkg: String? = null
            var session: String? = null
            if (peek() == '}') {
                i++
            } else {
                while (true) {
                    val key = parseString()
                    expect(':')
                    when (key) {
                        "start" -> start = parseLong()
                        "end" -> end = parseLong()
                        "pkg" -> pkg = parseString()
                        "session" -> session = parseString()
                        else -> throw IllegalArgumentException("неизвестное поле $key")
                    }
                    val c = next()
                    if (c == '}') break
                    require(c == ',') { "ожидалась запятая" }
                }
            }
            require(start != null && end != null && pkg != null && session != null) { "не все поля" }
            return UsageDebt(start, end, pkg, session)
        }

        private fun parseLong(): Long {
            skipSpaces()
            val from = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(from, i).toLongOrNull() ?: throw IllegalArgumentException("не число")
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "строка не закрыта" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "строка не закрыта" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'u' -> {
                                require(i + 4 <= s.length) { "неполный \\u" }
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw IllegalArgumentException("неверный \\u")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("неизвестная последовательность")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char? {
            skipSpaces()
            return s.getOrNull(i)
        }

        private fun next(): Char {
            skipSpaces()
            require(i < s.length) { "неожиданный конец" }
            return s[i++]
        }

        private fun expect(c: Char) {
            require(next() == c) { "ожидался $c" }
        }
    }
}
