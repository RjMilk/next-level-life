package com.nextlevellife.app.data

import kotlin.random.Random

/**
 * Свои домашние программы: пользователь сам собирает программу из упражнений каталога и проходит её
 * тем же экраном, что и готовые ([Programs]). Хранятся только на этом телефоне — в настройках приложения
 * (ключ [CustomPrograms.KEY], строка JSON), с телефоном пары не обмениваются; попадают в резервную копию
 * вместе с настройками ([Store.exportPrefs]).
 *
 * Награда — ровно как у готовых: каждый подход засчитывает камера и оплачивает по обычной цене
 * упражнения ([ProgramMath]), бонуса за прохождение нет. Ручного ввода повторений нет.
 */

/** Чистая логика своих программ: ограничения, нормализация, кодирование. Без Android — проверяется тестами. */
object CustomProgramMath {
    /** Префикс id своей программы; начинается с [Programs.PREFIX] — прохождения не дают бонуса комплекса. */
    const val PREFIX = Programs.PREFIX + "c_"
    private const val ID_LENGTH = 8
    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    const val TITLE_MAX = 40
    const val STEPS_MAX = 10
    const val PROGRAMS_MAX = 10

    val SETS_RANGE = 1..6
    val REPS_RANGE = 1..50
    val HOLD_RANGE = 10..180
    const val HOLD_STEP = 5
    val REST_RANGE = 15..180
    const val REST_STEP = 15

    const val DEFAULT_SETS = 3
    const val DEFAULT_REPS = 10
    const val DEFAULT_HOLD = 30
    const val DEFAULT_REST = 60

    /** Подпись своей программы (и прохождения удалённой своей программы в «Прогрессе»). */
    const val SUBTITLE = "Своя программа"

    /** Версия формата хранения. */
    private const val FORMAT = 1

    /**
     * Упражнения, из которых собирается программа: «Свой вес» и «Гиря» — все их считает камера
     * (у каждого есть счётчик в pose/Counters.kt, это проверяет тест).
     */
    val bodyweight: List<Exercise> get() = Exercises.bodyweight
    val kettlebell: List<Exercise> get() = Exercises.kettlebell
    val exercises: List<Exercise> get() = bodyweight + kettlebell

    fun exerciseById(id: String): Exercise? = exercises.firstOrNull { it.id == id }

    fun isCustomId(id: String): Boolean = id.startsWith(PREFIX)

    /** Правильный id своей программы: префикс и 8 символов [a-z0-9]. */
    fun isValidId(id: String): Boolean =
        isCustomId(id) && id.length == PREFIX.length + ID_LENGTH && id.drop(PREFIX.length).all { it in ID_CHARS }

    /** Новый id «home_c_xxxxxxxx», которого нет среди [taken]. */
    fun newId(taken: Set<String> = emptySet(), random: Random = Random.Default): String {
        while (true) {
            val id = PREFIX + (1..ID_LENGTH).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }.joinToString("")
            if (id !in taken) return id
        }
    }

    // ---------- Числа шага ----------

    private fun snap(value: Int, range: IntRange, step: Int): Int {
        val c = value.coerceIn(range)
        val base = range.first
        val snapped = base + Math.round((c - base).toDouble() / step).toInt() * step
        return snapped.coerceIn(range)
    }

    fun clampSets(sets: Int): Int = sets.coerceIn(SETS_RANGE)

    /** Цель: повторения 1–50, у удержания — секунды 10–180 с шагом 5. */
    fun clampTarget(exercise: Exercise, target: Int): Int =
        if (exercise.hold) snap(target, HOLD_RANGE, HOLD_STEP) else target.coerceIn(REPS_RANGE)

    /** Отдых 15–180 с с шагом 15. */
    fun clampRest(restSeconds: Int): Int = snap(restSeconds, REST_RANGE, REST_STEP)

    /** Шаг с числами, прижатыми к допустимым границам. */
    fun normalize(step: ProgramStep): ProgramStep = ProgramStep(
        exercise = step.exercise,
        sets = clampSets(step.sets),
        target = clampTarget(step.exercise, step.target),
        restSeconds = clampRest(step.restSeconds),
    )

    fun defaultTarget(exercise: Exercise): Int = if (exercise.hold) DEFAULT_HOLD else DEFAULT_REPS

    /** Новый шаг: 3 подхода, 10 повторений (удержание — 30 с), отдых [restSeconds]. */
    fun defaultStep(exercise: Exercise, restSeconds: Int = DEFAULT_REST): ProgramStep =
        normalize(ProgramStep(exercise, DEFAULT_SETS, defaultTarget(exercise), restSeconds))

    /** Шаг кнопок −/+: подходы и повторения по 1, удержание по 5 с, отдых по 15 с. */
    fun withSets(step: ProgramStep, delta: Int): ProgramStep = step.copy(sets = clampSets(step.sets + delta))

    fun withTarget(step: ProgramStep, delta: Int): ProgramStep =
        step.copy(target = clampTarget(step.exercise, step.target + delta * (if (step.hold) HOLD_STEP else 1)))

    fun withRest(step: ProgramStep, delta: Int): ProgramStep =
        step.copy(restSeconds = clampRest(step.restSeconds + delta * REST_STEP))

    /**
     * Другое упражнение в шаге: подходы и отдых остаются; цель остаётся, если вид счёта тот же
     * (повторения → повторения), иначе — цель по умолчанию нового вида.
     */
    fun withExercise(step: ProgramStep, exercise: Exercise): ProgramStep {
        val target = if (exercise.hold == step.exercise.hold) step.target else defaultTarget(exercise)
        return normalize(ProgramStep(exercise, step.sets, target, step.restSeconds))
    }

    /** Можно ли уменьшить/увеличить значение (кнопки −/+ неактивны на границе). */
    fun canChange(step: ProgramStep, field: StepField, delta: Int): Boolean {
        val changed = when (field) {
            StepField.SETS -> withSets(step, delta)
            StepField.TARGET -> withTarget(step, delta)
            StepField.REST -> withRest(step, delta)
        }
        return changed != step
    }

    // ---------- Программа ----------

    /** Новая программа по умолчанию: без названия, один шаг «Приседания» 3×10, отдых 60 с. */
    fun newProgram(id: String = ""): Program =
        Program(id = id, title = "", subtitle = SUBTITLE, steps = listOf(defaultStep(Exercises.squats)))

    /** Программа из полей редактора: название без пробелов по краям, числа прижаты к границам. */
    fun build(id: String, title: String, steps: List<ProgramStep>): Program =
        Program(id = id, title = title.trim(), subtitle = SUBTITLE, steps = steps.map(::normalize))

    /** Можно ли создать ещё одну программу при [count] своих. */
    fun canCreate(count: Int): Boolean = count < PROGRAMS_MAX

    /**
     * Почему программу нельзя сохранить (null — можно): «Добавь название», «Добавь хотя бы одно упражнение»…
     * [isNew] и [count] — новая ли программа и сколько своих уже есть (лимит [PROGRAMS_MAX]).
     */
    fun problem(title: String, stepCount: Int, isNew: Boolean = false, count: Int = 0): String? {
        val t = title.trim()
        return when {
            isNew && !canCreate(count) -> "Не больше $PROGRAMS_MAX программ"
            t.isEmpty() -> "Добавь название"
            t.length > TITLE_MAX -> "Название — не длиннее $TITLE_MAX символов"
            stepCount < 1 -> "Добавь хотя бы одно упражнение"
            stepCount > STEPS_MAX -> "Не больше $STEPS_MAX упражнений"
            else -> null
        }
    }

    fun isValid(program: Program): Boolean = program.steps.all { it.exercise in exercises } &&
        problem(program.title, program.steps.size) == null

    // ---------- Хранение: JSON ----------

    /**
     * `{"v":1,"programs":[{"id":"home_c_…","title":"…","steps":[{"ex":"squats","sets":3,"target":10,"rest":60}]}]}`.
     * Упражнение хранится по id каталога.
     */
    fun encode(programs: List<Program>): String {
        val sb = StringBuilder()
        sb.append("{\"v\":").append(FORMAT).append(",\"programs\":[")
        programs.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":")
            MiniJson.writeString(sb, p.id)
            sb.append(",\"title\":")
            MiniJson.writeString(sb, p.title)
            sb.append(",\"steps\":[")
            p.steps.forEachIndexed { j, s ->
                if (j > 0) sb.append(',')
                sb.append("{\"ex\":")
                MiniJson.writeString(sb, s.exercise.id)
                sb.append(",\"sets\":").append(s.sets)
                sb.append(",\"target\":").append(s.target)
                sb.append(",\"rest\":").append(s.restSeconds)
                sb.append('}')
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * Разбор [encode] без падений: испорченная строка — пустой список. Шаг с неизвестным упражнением
     * отбрасывается, программа без шагов или с неправильным id — целиком; числа вне диапазонов прижимаются
     * к границам, лишние шаги и программы (сверх 10) отбрасываются, повтор id — тоже. Пустое название
     * становится «Своя программа».
     */
    fun decode(text: String?): List<Program> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val list = root["programs"] as? List<*> ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Program>()
        for (item in list) {
            if (out.size >= PROGRAMS_MAX) break
            val o = item as? Map<*, *> ?: continue
            val id = o["id"] as? String ?: continue
            if (!isValidId(id) || id in seen) continue
            val steps = (o["steps"] as? List<*>).orEmpty().mapNotNull(::decodeStep).take(STEPS_MAX)
            if (steps.isEmpty()) continue
            val title = (o["title"] as? String).orEmpty().trim().take(TITLE_MAX).trim().ifEmpty { SUBTITLE }
            seen += id
            out += Program(id = id, title = title, subtitle = SUBTITLE, steps = steps)
        }
        return out
    }

    private fun decodeStep(item: Any?): ProgramStep? {
        val o = item as? Map<*, *> ?: return null
        val exercise = (o["ex"] as? String)?.let(::exerciseById) ?: return null
        fun int(key: String, default: Int): Int = when (val v = o[key]) {
            is Number -> v.toDouble().let { d -> if (d.isNaN()) default else d.coerceIn(-1e9, 1e9).toInt() }
            else -> default
        }
        return normalize(
            ProgramStep(
                exercise = exercise,
                sets = int("sets", DEFAULT_SETS),
                target = int("target", defaultTarget(exercise)),
                restSeconds = int("rest", DEFAULT_REST),
            )
        )
    }
}

/** Что меняют кнопки −/+ шага. */
enum class StepField { SETS, TARGET, REST }

/**
 * Маленький разбор JSON без Android (org.json в юнит-тестах — заглушка): объекты → Map, массивы → List,
 * строки, числа (Double), true/false/null. Ошибка формата — исключение.
 */
internal object MiniJson {
    fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "Лишние символы в JSON" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length) { "Конец JSON" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Неожиданный символ $c")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "Ожидалось $w" }
            i += w.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "Незакрытая строка" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "Незакрытая строка" }
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "Короткий \\u" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Неизвестный escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out += value()
                ws()
                require(i < s.length) { "Незакрытый массив" }
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("Ожидалось , или ]")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                ws()
                require(i < s.length && s[i] == '"') { "Ожидался ключ" }
                val key = str()
                ws()
                require(i < s.length && s[i] == ':') { "Ожидалось :" }
                i++
                out[key] = value()
                ws()
                require(i < s.length) { "Незакрытый объект" }
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("Ожидалось , или }")
                }
            }
        }
    }
}

/**
 * Хранилище своих программ в настройках приложения ([Store.prefs]). Список читается из строки JSON
 * и кешируется по самой строке: после восстановления копии (другая строка) кеш обновится сам.
 * Пока [Store] не готов (юнит-тесты), своих программ нет.
 */
object CustomPrograms {
    /** Ключ в настройках; его же переносит резервная копия ([Store.exportPrefs] / [Store.parsePrefs]). */
    const val KEY = "custom_programs"

    @Volatile
    private var cache: Pair<String?, List<Program>> = null to emptyList()

    /** Свои программы по порядку создания. */
    fun all(): List<Program> {
        if (!Store.isReady()) return emptyList()
        val raw = Store.prefs().getString(KEY, null)
        val c = cache
        if (c.first == raw) return c.second
        val list = CustomProgramMath.decode(raw)
        cache = raw to list
        return list
    }

    fun byId(id: String): Program? =
        if (CustomProgramMath.isCustomId(id)) all().firstOrNull { it.id == id } else null

    fun count(): Int = all().size

    /**
     * Сохранить программу: с id из списка — заменить на месте, иначе — добавить новую (id выдаётся здесь).
     * Возвращает сохранённую программу или null — нельзя (невалидна, уже 10 программ, запись не удалась).
     */
    @Synchronized
    fun save(program: Program): Program? {
        if (!Store.isReady()) return null
        val list = all()
        val existing = list.indexOfFirst { it.id == program.id }
        val id = if (existing >= 0) program.id else CustomProgramMath.newId(list.map { it.id }.toSet())
        val saved = CustomProgramMath.build(id, program.title, program.steps)
        if (!CustomProgramMath.isValid(saved)) return null
        val next = when {
            existing >= 0 -> list.toMutableList().also { it[existing] = saved }
            CustomProgramMath.canCreate(list.size) -> list + saved
            else -> return null
        }
        if (!write(next)) return null
        return saved
    }

    /** Удалить программу; история прохождений в дневнике остаётся. */
    @Synchronized
    fun delete(id: String): Boolean {
        if (!Store.isReady()) return false
        val list = all()
        if (list.none { it.id == id }) return false
        return write(list.filterNot { it.id == id })
    }

    private fun write(list: List<Program>): Boolean {
        val raw = CustomProgramMath.encode(list)
        val ok = Store.prefs().edit().putString(KEY, raw).commit()
        if (ok) {
            cache = raw to CustomProgramMath.decode(raw)
            Store.notifyChanged()
        }
        return ok
    }
}
