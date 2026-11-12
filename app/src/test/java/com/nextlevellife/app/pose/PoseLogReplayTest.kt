package com.nextlevellife.app.pose

import com.nextlevellife.app.ui.HINT_LOST
import com.nextlevellife.app.ui.HINT_RETURN_TO_START
import com.nextlevellife.app.ui.PanelTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Логика фаз панели камеры один в один с `handleFrame` в ui/CameraRepPanel.kt (звук — списком
 * запрошенных подсказок, без ограничения частоты). Тайминги — общие, из [PanelTiming]:
 * ПОИСК → [PanelTiming.HOLD_MS] в исходной точке (tracking && atStart; разрывы до
 * [PanelTiming.HOLD_GAP_MS] прощаются) → reset счётчика → СЧЁТ; потеря человека во время счёта:
 * после [PanelTiming.LOST_HINT_MS] — «Не вижу тебя», после [PanelTiming.LOST_SEARCH_MS] — ПОИСК.
 * Родительский счёт = объявленные повторения.
 * При изменении handleFrame правьте здесь — это эталон для проигрывания записей.
 */
internal class PanelSim(val counter: RepCounter, private val target: Int?) {
    enum class Phase { SEARCH, HOLD, COUNT }

    var phase = Phase.SEARCH
        private set
    private var holdSince = -1L
    private var holdBadSince = -1L
    private var lostSince = -1L
    private var counterSeen = 0

    /** Подсказка на экране панели (как tracker.hint). */
    var hint = ""
        private set

    /** Голосовые подсказки, которые панель запросила бы (до ограничения частоты в RepSound). */
    val voiceHints = ArrayList<String>()

    /** Сколько повторений объявлено (= счёт родителя). */
    var announced = 0
        private set

    /** Моменты (timeMs кадра) объявленных повторений. */
    val repTimes = ArrayList<Long>()

    fun toSearch() {
        phase = Phase.SEARCH
        holdSince = -1L
        holdBadSince = -1L
        lostSince = -1L
    }

    /** Что вернул счётчик на последнем кадре — это и пишет панель в запись. */
    private var lastState = RepState()

    /** Обработать кадр; вернуть то, что вернул onFrame счётчика (как пишет панель). */
    fun onFrame(frame: PoseFrame): RepState {
        process(frame)
        return lastState
    }

    private fun process(frame: PoseFrame) {
        val parentCount = announced
        if (phase == Phase.COUNT && target != null && maxOf(parentCount, announced) >= target) return

        val state = counter.onFrame(frame)
        lastState = state
        hint = state.hint
        val t = frame.timeMs
        when (phase) {
            Phase.SEARCH, Phase.HOLD -> {
                val atStart = state.tracking && state.atStart
                if (atStart) {
                    holdBadSince = -1L
                    if (holdSince < 0) holdSince = t
                } else if (holdSince >= 0) {
                    if (holdBadSince < 0) holdBadSince = t
                    if (t - holdBadSince > PanelTiming.HOLD_GAP_MS) toSearch()
                }

                if (holdSince >= 0) {
                    val held = t - holdSince
                    phase = Phase.HOLD
                    if (atStart && held >= PanelTiming.HOLD_MS) {
                        counter.reset()
                        counterSeen = counter.state.count
                        announced = parentCount
                        lostSince = -1L
                        phase = Phase.COUNT
                    }
                } else if (state.tracking && state.inPosition) {
                    hint = HINT_RETURN_TO_START
                    voiceHints += HINT_RETURN_TO_START
                } else if (state.tracking && state.hint.isNotEmpty()) {
                    voiceHints += state.hint
                }
            }

            Phase.COUNT -> {
                var lostLong = false
                if (!state.tracking) {
                    if (lostSince < 0) lostSince = t
                    val lost = t - lostSince
                    if (lost > PanelTiming.LOST_SEARCH_MS) {
                        toSearch()
                        voiceHints += HINT_LOST
                        return
                    }
                    lostLong = lost > PanelTiming.LOST_HINT_MS
                } else {
                    lostSince = -1L
                }
                val grown = state.count - counterSeen
                counterSeen = state.count
                if (grown > 0) {
                    var n = maxOf(parentCount, announced)
                    repeat(grown) {
                        if (target != null && n >= target) return@repeat
                        n++
                        repTimes += t
                    }
                    announced = n
                } else if (lostLong) {
                    voiceHints += HINT_LOST
                } else if (state.hint.isNotEmpty()) {
                    voiceHints += state.hint
                }
            }
        }
    }
}

/** Проигрывание одной записи и отчёт по ней. */
internal object PoseLogReplay {

    class Result(
        val data: PoseLogData,
        /** Состояние после каждого кадра при проигрывании. */
        val states: List<RepState>,
        val phases: List<String>,
        val replayCount: Int?,
        val phoneCount: Int,
        val repTimes: List<Long>,
        val report: String,
    )

    fun read(file: File): PoseLogData =
        file.bufferedReader(Charsets.UTF_8).useLines { PoseLogFormat.parse(it) }

    /** [counterFor] — чем проигрывать (по умолчанию текущие счётчики приложения). */
    fun analyze(
        name: String,
        data: PoseLogData,
        counterFor: (String) -> RepCounter? = { RepCounters.forExercise(it) },
    ): Result {
        val frames = data.frames
        val rec = data.recorded
        val target = data.meta["target"]?.toIntOrNull()
        val counter = counterFor(data.exerciseId)
        val sim = counter?.let { PanelSim(it, target) }

        val states = ArrayList<RepState>(frames.size)
        val phases = ArrayList<String>(frames.size)
        if (sim != null) {
            for ((i, f) in frames.withIndex()) {
                if (i in data.resets) sim.toSearch()
                states += sim.onFrame(f)
                phases += sim.phase.name
            }
        }

        val out = StringBuilder()
        fun line(s: String = "") = out.append(s).append('\n')

        line("=== $name")
        line("упражнение: ${data.exerciseId}   камера: ${if (data.front) "фронтальная" else "задняя"}" +
            (target?.let { "   цель: $it" } ?: ""))
        line("заголовок (${data.version}): " + data.meta.entries.joinToString(" ") { "${it.key}=${it.value}" })
        if (frames.isEmpty()) {
            line("кадров нет")
            return Result(data, states, phases, sim?.announced, 0, emptyList(), out.toString())
        }

        // --- Время и частота кадров
        val t0 = frames.first().timeMs
        val durMs = frames.last().timeMs - t0
        var maxGap = 0L
        var maxGapAt = 0L
        for (i in 1 until frames.size) {
            val g = frames[i].timeMs - frames[i - 1].timeMs
            if (g > maxGap) { maxGap = g; maxGapAt = frames[i - 1].timeMs }
        }
        val avgFps = if (durMs > 0) (frames.size - 1) * 1000.0 / durMs else 0.0
        // Минимум «кадров за последнюю секунду» по всем полным секундным окнам.
        var minFps = Int.MAX_VALUE
        var minFpsAt = 0L
        var lo = 0
        for (i in frames.indices) {
            val t = frames[i].timeMs
            while (frames[lo].timeMs <= t - 1000) lo++
            if (t - t0 >= 1000) {
                val n = i - lo + 1
                if (n < minFps) { minFps = n; minFpsAt = t }
            }
        }
        line("длительность: ${fmt(durMs / 1000.0, 1)} с   кадров: ${frames.size}   с человеком: ${pct(frames.count { it.points.isNotEmpty() }, frames.size)}")
        line("частота: средняя ${fmt(avgFps, 1)} к/с, минимальная за секунду " +
            (if (minFps == Int.MAX_VALUE) "— (запись короче 1 с)" else "$minFps к/с (t=${minFpsAt - t0} мс)") +
            ", самый длинный разрыв $maxGap мс (после t=${maxGapAt - t0} мс)")

        // --- Состояния: телефон и проигрывание
        line("tracking: телефон ${pct(rec.count { it.tracking }, rec.size)}" +
            (if (sim != null) ", проигрывание ${pct(states.count { it.tracking }, states.size)}" else ""))
        line("inPosition: телефон ${pct(rec.count { it.inPosition }, rec.size)}" +
            (if (sim != null) ", проигрывание ${pct(states.count { it.inPosition }, states.size)}" else ""))
        line("atStart: телефон " + (if (data.version == "v1") "— (запись v1)" else pct(rec.count { it.atStart }, rec.size)) +
            (if (sim != null) ", проигрывание ${pct(states.count { it.atStart }, states.size)}" else ""))
        line("фазы на телефоне: " + rec.groupingBy { it.phase }.eachCount().entries
            .joinToString(", ") { "${it.key} ${pct(it.value, rec.size)}" })
        line("уходов в ПОИСК во время счёта: телефон ${dropsToSearch(rec.map { it.phase })}" +
            (if (sim != null) ", проигрывание ${dropsToSearch(phases)}" else "") +
            (if (data.resets.isNotEmpty()) "   (внешних сбросов — пауз: ${data.resets.size})" else ""))

        val phoneCount = phoneCount(rec)
        val phoneRepTimes = phoneRepTimes(frames, rec)
        if (sim == null) {
            line("СЧЁТ: телефон $phoneCount, проигрывание — нет счётчика для «${data.exerciseId}»")
        } else {
            line("СЧЁТ: телефон $phoneCount, проигрывание ${sim.announced}")
            val same = rec.indices.count { i ->
                val r = rec[i]
                val s = states[i]
                r.count == s.count && r.tracking == s.tracking && r.inPosition == s.inPosition &&
                    r.phase == phases[i] && (data.version == "v1" || r.atStart == s.atStart)
            }
            line("совпадение состояний проигрывания с телефоном: ${pct(same, rec.size)} кадров")
        }
        line("повторения на телефоне (t, мс): " + phoneRepTimes.joinToString(", ") { "${it - t0}" }.ifEmpty { "—" })
        if (sim != null) {
            line("повторения при проигрывании (t, мс): " + sim.repTimes.joinToString(", ") { "${it - t0}" }.ifEmpty { "—" })
        }

        // --- Углы и высоты, посчитанные прямо из кадров
        val cfg = ExerciseConfigs.byId[data.exerciseId]
        val required = cfg?.required ?: setOf(Limb.SHOULDER, Limb.HIP)
        val optional = Limb.entries.toSet() - required
        val elbow = ArrayList<Float>()
        val knee = ArrayList<Float>()
        val torso = ArrayList<Float>()
        val wrist = ArrayList<Float>()
        var found = 0
        var sideChanges = 0
        var lastSide: Side? = null
        for (f in frames) {
            val located = BodyLocator.locate(f, required, optional)
            if (located !is Located.Found) continue
            found++
            val side = located.body.side
            if (lastSide != null && side != lastSide) sideChanges++
            lastSide = side
            val s = Signals.measure(located.body)
            elbow.addIfNum(s.elbow)
            knee.addIfNum(s.knee)
            torso.addIfNum(s.torsoTilt)
            wrist.addIfNum(s.wristOverShoulder)
        }
        line("тело найдено (все нужные суставы ≥ ${BodyLocator.MIN_CONFIDENCE}): ${pct(found, frames.size)} кадров" +
            (cfg?.let { "; нужны: " + it.required.joinToString(",") { l -> l.name.lowercase() } } ?: ""))
        line("сигналы по кадрам (без сглаживания)        мин     p5    p95   макс   кадров")
        line(statLine("локоть, °", elbow, 0))
        line(statLine("колено, °", knee, 0))
        line(statLine("наклон торса от вертикали, °", torso, 0))
        line(statLine("запястье над плечом, торсов", wrist, 2))

        // --- Сторона тела
        line("смена выбранной стороны тела: ${pct(sideChanges, max(found - 1, 0))} кадров ($sideChanges раз)")
        val ambiguous = frames.count { f -> f.points.isNotEmpty() && ambiguousSides(f) }
        line("кадров, где уверенность левой и правой руки/ноги отличается < 0,1: ${pct(ambiguous, frames.count { it.points.isNotEmpty() })}")

        // --- Подсказки
        line("подсказки на телефоне (кадров):")
        val hints = rec.groupingBy { it.hint.ifEmpty { "(пусто)" } }.eachCount().entries.sortedByDescending { it.value }
        for ((h, n) in hints) line("  ${n.toString().padStart(6)}  $h")
        line()

        return Result(data, states, phases, sim?.announced, phoneCount, phoneRepTimes, out.toString())
    }

    /** Счёт телефона: сумма по непрерывным отрезкам COUNT (без учёта цели подхода). */
    fun phoneCount(rec: List<RecordedState>): Int = phoneRepIndices(rec).size

    /**
     * Кадры, на которых телефон засчитал повторение. На первом кадре отрезка COUNT панель
     * сбрасывает счётчик (в записи там ещё count до сброса), дальше счёт идёт от нуля.
     */
    private fun phoneRepIndices(rec: List<RecordedState>): List<Int> {
        val out = ArrayList<Int>()
        var seen = -1 // -1 — не в отрезке COUNT
        for ((i, r) in rec.withIndex()) {
            if (r.phase != "COUNT") {
                seen = -1
            } else if (seen < 0) {
                seen = 0
            } else {
                repeat(max(0, r.count - seen)) { out += i }
                seen = r.count
            }
        }
        return out
    }

    private fun phoneRepTimes(frames: List<PoseFrame>, rec: List<RecordedState>): List<Long> =
        phoneRepIndices(rec).map { frames[it].timeMs }

    private fun dropsToSearch(phases: List<String>): Int =
        (1 until phases.size).count { phases[it - 1] == "COUNT" && phases[it] == "SEARCH" }

    /** Обе стороны руки или ноги видны почти одинаково уверенно — выбор стороны неустойчив. */
    private fun ambiguousSides(f: PoseFrame): Boolean {
        val pairs = listOf(
            listOf(Joint.LEFT_SHOULDER, Joint.LEFT_ELBOW, Joint.LEFT_WRIST) to
                listOf(Joint.RIGHT_SHOULDER, Joint.RIGHT_ELBOW, Joint.RIGHT_WRIST),
            listOf(Joint.LEFT_HIP, Joint.LEFT_KNEE, Joint.LEFT_ANKLE) to
                listOf(Joint.RIGHT_HIP, Joint.RIGHT_KNEE, Joint.RIGHT_ANKLE),
        )
        return pairs.any { (l, r) ->
            val cl = l.sumOf { (f.points[it]?.confidence ?: 0f).toDouble() } / l.size
            val cr = r.sumOf { (f.points[it]?.confidence ?: 0f).toDouble() } / r.size
            abs(cl - cr) < 0.1
        }
    }

    private fun MutableList<Float>.addIfNum(v: Float) {
        if (!v.isNaN()) add(v)
    }

    private fun statLine(label: String, values: List<Float>, decimals: Int): String {
        val name = label.padEnd(40)
        if (values.isEmpty()) return "  $name нет данных"
        val s = values.sorted()
        fun p(q: Double) = s[(ceil(q * s.size).toInt() - 1).coerceIn(0, s.size - 1)].toDouble()
        val cols = listOf(s.first().toDouble(), p(0.05), p(0.95), s.last().toDouble())
            .joinToString("") { fmt(it, decimals).padStart(7) }
        return "  $name$cols${values.size.toString().padStart(9)}"
    }

    private fun pct(n: Int, of: Int): String =
        if (of <= 0) "—" else String.format(Locale.US, "%.0f%%", 100.0 * n / of)

    private fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)
}

/**
 * Инструмент, а не проверка: проигрывает записи с телефона через текущие счётчики.
 * Записи — `*.csv` в каталоге `poselogs/` в корне проекта (или `-Dposelog.dir=…` / переменная
 * окружения `POSELOG_DIR`). Отчёт — в консоль и в `poselogs/report.txt`.
 * Падает только если файл не разбирается.
 */
class PoseLogReplayTest {

    @Test
    fun replayRecordedSets() {
        val root = projectRoot()
        val dir = (System.getProperty("poselog.dir") ?: System.getenv("POSELOG_DIR"))
            ?.let { File(it).let { f -> if (f.isAbsolute) f else File(root, it) } }
            ?: File(root, "poselogs")
        val files = if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".csv") }.sortedBy { it.path }.toList()
        } else {
            emptyList()
        }

        val report = StringBuilder()
        if (files.isEmpty()) {
            report.append("записей нет (каталог ${dir.path})\n")
        } else {
            val broken = ArrayList<String>()
            val summary = ArrayList<String>()
            for (f in files) {
                val name = f.relativeTo(dir).path
                val data = try {
                    PoseLogReplay.read(f)
                } catch (e: IllegalArgumentException) {
                    broken += "$name: ${e.message}"
                    report.append("=== $name\nНЕ РАЗБИРАЕТСЯ: ${e.message}\n\n")
                    continue
                }
                val r = PoseLogReplay.analyze(name, data)
                report.append(r.report)
                summary += String.format(
                    Locale.US, "%-40s %-10s телефон %3d   проигрывание %s",
                    name, data.exerciseId, r.phoneCount, r.replayCount?.toString() ?: "—",
                )
            }
            report.append("=== ИТОГО (${files.size} записей)\n")
            summary.forEach { report.append(it).append('\n') }
            if (broken.isNotEmpty()) {
                emit(report.toString(), dir, root)
                throw AssertionError("Не разбираются записи:\n" + broken.joinToString("\n"))
            }
        }
        emit(report.toString(), dir, root)
    }

    private fun emit(text: String, dir: File, root: File) {
        println(text)
        val out = if (dir.isDirectory) File(dir, "report.txt") else File(root, "app/build/poselog-report.txt")
        try {
            out.parentFile?.mkdirs()
            out.writeText(text, Charsets.UTF_8)
            println("отчёт: ${out.absolutePath}")
        } catch (e: Exception) {
            println("не удалось записать отчёт в $out: $e")
        }
    }

    /** Корень проекта: каталог с settings.gradle(.kts) выше рабочего каталога теста (обычно app/). */
    private fun projectRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").isFile || File(d, "settings.gradle").isFile) return d
            d = d.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}

/**
 * Заглушка счётчика для проверки логики панели, не зависящая от настоящих счётчиков.
 * Состояние закодировано в самом кадре: x точки NOSE — набор битов [TRACKING], [IN_POSITION],
 * [AT_START], [REP] (на этом кадре завершилось повторение); пустой кадр — человека нет.
 * Целые x переживают запись в файл, поэтому заглушкой можно и проигрывать записи.
 */
internal class StubCounter : RepCounter {
    override var state = RepState()
        private set
    private var count = 0

    override fun onFrame(frame: PoseFrame): RepState {
        val bits = frame.points[Joint.NOSE]?.x?.roundToInt() ?: 0
        val tracking = bits and TRACKING != 0
        if (tracking && bits and REP != 0) count++
        state = RepState(
            count = count,
            tracking = tracking,
            inPosition = tracking && bits and IN_POSITION != 0,
            hint = if (tracking) "" else "Встань в кадр целиком",
            depth = 0f,
            atStart = tracking && bits and AT_START != 0,
        )
        return state
    }

    override fun reset() {
        count = 0
        state = RepState()
    }

    companion object {
        const val TRACKING = 1
        const val IN_POSITION = 2
        const val AT_START = 4
        const val REP = 8
    }
}

/** Последовательность кадров для [StubCounter], шаг [stepMs] (по умолчанию ~30 к/с). */
internal class StubScript(private val stepMs: Long = 33) {
    val frames = ArrayList<PoseFrame>()
    var t = 100_000L
        private set

    fun frame(bits: Int) = apply {
        val points = if (bits == 0) emptyMap() else mapOf(Joint.NOSE to PosePoint(bits.toFloat(), 0f, 0.9f))
        frames += PoseFrame(t, 480, 640, points)
        t += stepMs
    }

    private fun span(ms: Long, bits: Int) = apply { repeat(max(1, (ms / stepMs).toInt())) { frame(bits) } }

    /** В позе и в исходной точке движения. */
    fun atStart(ms: Long) = span(ms, StubCounter.TRACKING or StubCounter.IN_POSITION or StubCounter.AT_START)

    /** В позе, посреди движения. */
    fun moving(ms: Long) = span(ms, StubCounter.TRACKING or StubCounter.IN_POSITION)

    /** Виден, но не в позе упражнения. */
    fun outOfPose(ms: Long) = span(ms, StubCounter.TRACKING)

    /** Человека нет в кадре. */
    fun lost(ms: Long) = span(ms, 0)

    /** [n] повторений: движение, возврат в исходную точку (повторение засчитано), короткая пауза. */
    fun reps(n: Int, ms: Long = 900) = apply {
        repeat(n) {
            moving(ms - 100)
            frame(StubCounter.TRACKING or StubCounter.IN_POSITION or StubCounter.AT_START or StubCounter.REP)
            atStart(66)
        }
    }
}

/** Логика фаз панели (через эталон [PanelSim]) на заглушке счётчика. */
class PanelLogicTest {

    private class Run(val frames: List<PoseFrame>, val phases: List<PanelSim.Phase>, val sim: PanelSim) {
        fun firstIndex(from: Int = 0, p: PanelSim.Phase) = (from until phases.size).first { phases[it] == p }
        fun time(i: Int) = frames[i].timeMs
        fun drops() = (1 until phases.size).count {
            phases[it - 1] == PanelSim.Phase.COUNT && phases[it] == PanelSim.Phase.SEARCH
        }
    }

    private fun run(script: StubScript, target: Int? = null): Run {
        val sim = PanelSim(StubCounter(), target)
        val phases = script.frames.map { sim.onFrame(it); sim.phase }
        return Run(script.frames, phases, sim)
    }

    /** (а) Одиночные выпавшие кадры во время удержания не мешают перейти в СЧЁТ за ~1 с. */
    @Test
    fun singleDroppedFramesDoNotBreakHold() {
        val s = StubScript().lost(300)
        val holdStart = s.t
        s.atStart(200).lost(33).atStart(200).moving(33).atStart(150).outOfPose(66).atStart(800)
        val r = run(s)
        val firstHold = r.firstIndex(p = PanelSim.Phase.HOLD)
        assertEquals(holdStart, r.time(firstHold))
        val count = r.firstIndex(firstHold, PanelSim.Phase.COUNT)
        val held = r.time(count) - holdStart
        assertTrue("СЧЁТ через $held мс удержания", held in PanelTiming.HOLD_MS until PanelTiming.HOLD_MS + 100)
        assertTrue(
            "между началом удержания и счётом не было ПОИСКА",
            (firstHold until count).all { r.phases[it] == PanelSim.Phase.HOLD },
        )
    }

    @Test
    fun longGapRestartsHold() {
        val s = StubScript().atStart(600).lost(400)
        val again = s.t
        s.atStart(1200)
        val r = run(s)
        val count = r.firstIndex(p = PanelSim.Phase.COUNT)
        val held = r.time(count) - again
        assertTrue("после разрыва 400 мс удержание заново: $held мс", held in PanelTiming.HOLD_MS until PanelTiming.HOLD_MS + 100)
        assertTrue("разрыв дольше ${PanelTiming.HOLD_GAP_MS} мс уводит в ПОИСК", PanelSim.Phase.SEARCH in r.phases)
    }

    /** (б) Потеря человека на 2 с посреди подхода не уводит в ПОИСК и не теряет счёт. */
    @Test
    fun twoSecondLossKeepsCountingPhaseAndCount() {
        val s = StubScript().atStart(1200).reps(2).lost(2000).atStart(200).reps(2).atStart(300)
        val r = run(s)
        val count = r.firstIndex(p = PanelSim.Phase.COUNT)
        assertTrue(
            "после начала счёта не уходили в ПОИСК",
            (count until r.phases.size).all { r.phases[it] == PanelSim.Phase.COUNT },
        )
        assertEquals(4, r.sim.announced)
        assertTrue("после 1,5 с потери — «Не вижу тебя»", HINT_LOST in r.sim.voiceHints)
    }

    @Test
    fun shortLossHasNoLostHint() {
        val r = run(StubScript().atStart(1200).reps(1).lost(1200).atStart(300))
        assertFalse(HINT_LOST in r.sim.voiceHints)
        assertEquals(PanelSim.Phase.COUNT, r.phases.last())
    }

    /** (в) Потеря на 4 с уводит в ПОИСК, после возврата и удержания счёт продолжается. */
    @Test
    fun fourSecondLossReturnsToSearchThenCountContinues() {
        val s = StubScript().atStart(1200).reps(2)
        val lostStart = s.t
        s.lost(4000)
        val back = s.t
        s.atStart(1200).reps(3).atStart(300)
        val r = run(s)
        assertEquals(1, r.drops())
        val search = r.firstIndex(r.firstIndex(p = PanelSim.Phase.COUNT), PanelSim.Phase.SEARCH)
        val lostFor = r.time(search) - lostStart
        assertTrue("в ПОИСК после $lostFor мс потери", lostFor > PanelTiming.LOST_SEARCH_MS)
        val recount = r.firstIndex(search, PanelSim.Phase.COUNT)
        assertTrue("после возврата снова удержание", r.time(recount) - back >= PanelTiming.HOLD_MS)
        assertEquals(5, r.sim.announced)
        assertEquals(PanelSim.Phase.COUNT, r.phases.last())
    }

    /** (г) Удержание не засчитывается, пока человек двигается (atStart = false при inPosition = true). */
    @Test
    fun movingInPositionDoesNotStartCount() {
        val s = StubScript().moving(3000)
        // Человек проходит через исходную точку на мгновения, но не задерживается в ней.
        repeat(5) { s.atStart(100).moving(500) }
        val r = run(s)
        assertTrue("без удержания в исходной точке счёт не начинается", r.phases.none { it == PanelSim.Phase.COUNT })
        assertEquals(HINT_RETURN_TO_START, r.sim.hint)
        assertTrue(HINT_RETURN_TO_START in r.sim.voiceHints)

        // Остановился в исходной точке — счёт начинается.
        val s2 = StubScript().moving(3000)
        val stop = s2.t
        s2.atStart(1200)
        val r2 = run(s2)
        val count = r2.firstIndex(p = PanelSim.Phase.COUNT)
        assertTrue(r2.time(count) - stop >= PanelTiming.HOLD_MS)
    }

    @Test
    fun outOfPoseShowsCounterHintNotReturnHint() {
        val r = run(StubScript().outOfPose(500))
        assertTrue(HINT_RETURN_TO_START !in r.sim.voiceHints)
        assertTrue(r.phases.all { it == PanelSim.Phase.SEARCH })
    }

    @Test
    fun repsDuringHoldAreDiscardedByReset() {
        // Счётчик успел насчитать «повторение», пока человек устраивался, — оно не объявляется.
        val r = run(StubScript().atStart(300).reps(1).atStart(1200).reps(2).atStart(200))
        assertEquals(2, r.sim.announced)
    }

    @Test
    fun targetStopsCounting() {
        val r = run(StubScript().atStart(1200).reps(5).atStart(200), target = 3)
        assertEquals(3, r.sim.announced)
        assertEquals(3, r.sim.repTimes.size)
    }
}
