package com.nextlevellife.app.pose

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * «Чёрный ящик» подхода: поток суставов и состояние счёта по кадрам, чтобы на компьютере
 * проиграть подход через счётчики с любыми порогами.
 *
 * Формат v3 — текст, разделитель `;`, десятичная точка:
 * ```
 * # nll-poselog v3 exercise=squats front=true device=Pixel_7 app=0.1.0 started=2026-11-12T10:15:30+03:00
 * t;w;h;NOSE_x;NOSE_y;NOSE_c;…;RIGHT_ANKLE_c;phase;count;tracking;inPosition;depth;hint;atStart;heldMs
 * 0;480;640;231.4;118.0;0.99;…;SEARCH;0;1;0;0.000;Встань прямо;0;0
 * # reset
 * …
 * ```
 * `t` — мс от первого кадра; у отсутствующего сустава x, y, c пустые. Состояние — после обработки кадра.
 * Строка-комментарий `# reset` — панель вернулась к поиску вне кадра (пауза, снятие паузы).
 * `heldMs` — засчитанное время удержания ([RepState.heldMs]; у счётчиков повторений 0).
 * v2 — то же без столбца `heldMs` (при разборе heldMs = 0); v1 — ещё и без `atStart` (atStart = false).
 */

/** Состояние панели и счётчика после кадра, как записано на телефоне. */
data class RecordedState(
    val phase: String,
    val count: Int,
    val tracking: Boolean,
    val inPosition: Boolean,
    val depth: Float,
    val hint: String,
    val atStart: Boolean = false,
    val heldMs: Long = 0,
)

/**
 * Разобранная запись. [frames] и [recorded] одной длины. [resets] — индексы кадров,
 * перед которыми панель сбросилась в поиск по внешней причине (строка `# reset`).
 * [version] — версия формата файла (v1, v2, v3).
 */
class PoseLogData(
    val exerciseId: String,
    val front: Boolean,
    val meta: Map<String, String>,
    val frames: List<PoseFrame>,
    val recorded: List<RecordedState>,
    val resets: Set<Int> = emptySet(),
    val version: String = PoseLogFormat.VERSION,
)

/** Чистый Kotlin, без Android: строки формата и разбор. */
object PoseLogFormat {
    const val MAGIC = "nll-poselog"
    const val VERSION = "v3"
    const val RESET_MARK = "# reset"
    private const val SEP = ';'

    /** Версии, которые умеет читать [parse]. */
    val READABLE = setOf("v1", "v2", "v3")

    private val STATE_COLUMNS_V1 = listOf("phase", "count", "tracking", "inPosition", "depth", "hint")
    private val STATE_COLUMNS_V2 = STATE_COLUMNS_V1 + "atStart"
    private val STATE_COLUMNS = STATE_COLUMNS_V2 + "heldMs"

    /**
     * Заголовок-комментарий. Значения без пробелов (пробел → `_`), чтобы строку можно было
     * разобрать как пары `ключ=значение`. [extra] — дополнительные пары (например, цель подхода).
     */
    fun header(
        exerciseId: String,
        front: Boolean,
        device: String,
        app: String,
        started: String,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val pairs = linkedMapOf(
            "exercise" to exerciseId,
            "front" to front.toString(),
            "device" to device,
            "app" to app,
            "started" to started,
        )
        pairs.putAll(extra)
        return buildString {
            append("# ").append(MAGIC).append(' ').append(VERSION)
            for ((k, v) in pairs) append(' ').append(k).append('=').append(metaValue(v))
        }
    }

    fun columns(): String = columnNames(VERSION).joinToString(SEP.toString())

    private fun columnNames(version: String): List<String> = buildList {
        add("t"); add("w"); add("h")
        for (j in Joint.entries) {
            add("${j.name}_x"); add("${j.name}_y"); add("${j.name}_c")
        }
        addAll(
            when (version) {
                "v1" -> STATE_COLUMNS_V1
                "v2" -> STATE_COLUMNS_V2
                else -> STATE_COLUMNS
            },
        )
    }

    /** Строка кадра. [t0] — timeMs первого кадра записи; [phase] — фаза панели (SEARCH/HOLD/COUNT). */
    fun line(frame: PoseFrame, t0: Long, phase: String, state: RepState): String {
        val sb = StringBuilder(400)
        sb.append(frame.timeMs - t0).append(SEP).append(frame.width).append(SEP).append(frame.height)
        for (j in Joint.entries) {
            val p = frame.points[j]
            if (p == null || p.x.isNaN() || p.y.isNaN() || p.confidence.isNaN()) {
                sb.append(SEP).append(SEP).append(SEP)
            } else {
                sb.append(SEP); fixed(sb, p.x, 1)
                sb.append(SEP); fixed(sb, p.y, 1)
                sb.append(SEP); fixed(sb, p.confidence, 2)
            }
        }
        sb.append(SEP).append(clean(phase))
        sb.append(SEP).append(state.count)
        sb.append(SEP).append(if (state.tracking) 1 else 0)
        sb.append(SEP).append(if (state.inPosition) 1 else 0)
        sb.append(SEP); fixed(sb, if (state.depth.isNaN()) 0f else state.depth, 3)
        sb.append(SEP).append(clean(state.hint))
        sb.append(SEP).append(if (state.atStart) 1 else 0)
        sb.append(SEP).append(state.heldMs)
        return sb.toString()
    }

    /**
     * Разбор записи v1, v2 или v3. Бросает [IllegalArgumentException] с номером строки, если файл не в формате.
     */
    fun parse(lines: Sequence<String>): PoseLogData {
        var version = VERSION
        var meta: Map<String, String>? = null
        var index: Map<String, Int>? = null
        val frames = ArrayList<PoseFrame>()
        val recorded = ArrayList<RecordedState>()
        val resets = HashSet<Int>()

        for ((n, raw) in lines.withIndex()) {
            val lineNo = n + 1
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                if (meta == null) {
                    val (v, m) = parseHeader(line) ?: fail(lineNo, "нет заголовка «# $MAGIC $VERSION …»")
                    version = v
                    meta = m
                } else if (line.trim() == RESET_MARK) {
                    resets += frames.size
                }
                continue
            }
            if (meta == null) fail(lineNo, "нет заголовка «# $MAGIC $VERSION …»")
            if (index == null) {
                val names = line.split(SEP)
                if (names.firstOrNull() != "t") fail(lineNo, "ожидалась строка названий столбцов")
                index = names.withIndex().associate { it.value to it.index }
                for (need in columnNames(version)) {
                    if (need !in index) fail(lineNo, "нет столбца $need")
                }
                continue
            }
            try {
                parseRow(line, index, frames, recorded)
            } catch (e: IllegalArgumentException) {
                fail(lineNo, e.message ?: "не разобрать строку")
            }
        }
        val m = meta ?: throw IllegalArgumentException("пустой файл: нет заголовка")
        if (index == null) throw IllegalArgumentException("нет строки названий столбцов")
        val exercise = m["exercise"] ?: throw IllegalArgumentException("в заголовке нет exercise=")
        return PoseLogData(
            exerciseId = exercise,
            front = m["front"]?.toBooleanStrictOrNull() ?: false,
            meta = m,
            frames = frames,
            recorded = recorded,
            resets = resets,
            version = version,
        )
    }

    /** Версия и пары ключ=значение заголовка; null — не наш заголовок или неизвестная версия. */
    private fun parseHeader(line: String): Pair<String, Map<String, String>>? {
        val tokens = line.removePrefix("#").trim().split(Regex("\\s+"))
        if (tokens.size < 2 || tokens[0] != MAGIC || tokens[1] !in READABLE) return null
        val out = LinkedHashMap<String, String>()
        for (tok in tokens.drop(2)) {
            val eq = tok.indexOf('=')
            if (eq > 0) out[tok.substring(0, eq)] = tok.substring(eq + 1)
        }
        return tokens[1] to out
    }

    private fun parseRow(
        line: String,
        index: Map<String, Int>,
        frames: MutableList<PoseFrame>,
        recorded: MutableList<RecordedState>,
    ) {
        val cells = line.split(SEP)
        val hintAt = index.getValue("hint")
        require(cells.size >= index.size) { "столбцов ${cells.size}, ожидалось ${index.size}" }
        fun cell(name: String): String {
            val i = index.getValue(name)
            // Подсказка — последний столбец; если в неё всё же попал «;», склеиваем хвост.
            return if (i == hintAt && i == index.size - 1) cells.subList(i, cells.size).joinToString(";") else cells[i]
        }
        fun long(name: String) = cell(name).toLongOrNull() ?: throw IllegalArgumentException("$name=«${cell(name)}» — не число")
        fun int(name: String) = cell(name).toIntOrNull() ?: throw IllegalArgumentException("$name=«${cell(name)}» — не число")
        fun float(name: String) = cell(name).toFloatOrNull() ?: throw IllegalArgumentException("$name=«${cell(name)}» — не число")
        fun flag(name: String) = when (cell(name)) {
            "1" -> true
            "0" -> false
            else -> throw IllegalArgumentException("$name=«${cell(name)}» — ожидалось 0/1")
        }

        val points = LinkedHashMap<Joint, PosePoint>()
        for (j in Joint.entries) {
            val xs = cell("${j.name}_x")
            val ys = cell("${j.name}_y")
            val cs = cell("${j.name}_c")
            if (xs.isEmpty() && ys.isEmpty() && cs.isEmpty()) continue
            val x = xs.toFloatOrNull()
            val y = ys.toFloatOrNull()
            val c = cs.toFloatOrNull()
            require(x != null && y != null && c != null) { "${j.name}: «$xs;$ys;$cs» — не число" }
            points[j] = PosePoint(x, y, c)
        }
        frames += PoseFrame(long("t"), int("w"), int("h"), points)
        recorded += RecordedState(
            phase = cell("phase"),
            count = int("count"),
            tracking = flag("tracking"),
            inPosition = flag("inPosition"),
            depth = float("depth"),
            hint = cell("hint"),
            atStart = if ("atStart" in index) flag("atStart") else false,
            heldMs = if ("heldMs" in index) long("heldMs") else 0L,
        )
    }

    private fun fail(lineNo: Int, msg: String): Nothing = throw IllegalArgumentException("строка $lineNo: $msg")

    /** Без разделителя и переводов строк. */
    private fun clean(s: String): String {
        if (s.none { it == SEP || it == '\n' || it == '\r' }) return s
        return s.replace(SEP, ',').replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
    }

    private fun metaValue(s: String): String = s.trim().replace(Regex("\\s+"), "_").ifEmpty { "-" }

    /** Число с [decimals] знаками после точки, без String.format (дёшево на каждом кадре). */
    private fun fixed(sb: StringBuilder, v: Float, decimals: Int) {
        var mul = 1L
        repeat(decimals) { mul *= 10 }
        val scaled = (v.toDouble() * mul).roundToLong()
        if (scaled < 0) sb.append('-')
        val a = abs(scaled)
        sb.append(a / mul)
        if (decimals > 0) {
            sb.append('.')
            val frac = (a % mul).toString()
            repeat(decimals - frac.length) { sb.append('0') }
            sb.append(frac)
        }
    }
}

/**
 * Пишет одну запись подхода в `Android/data/<пакет>/files/poselog/`. Файл создаётся при первом
 * кадре. Весь ввод-вывод — на своём фоновом потоке; [write] в главном потоке только собирает
 * строку и ставит её в очередь. Ошибки записи только логируются.
 *
 * Не потокобезопасен для вызовов: [write], [markReset] и [close] — из одного (главного) потока.
 */
class PoseLogWriter(
    context: Context,
    private val exerciseId: String,
    private val front: Boolean,
    private val target: Int? = null,
) {
    private val appContext = context.applicationContext
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "poselog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    // Главный поток.
    private var t0 = -1L
    private var sawPerson = false
    private var closed = false

    // Только поток io.
    private var file: File? = null
    private var out: BufferedWriter? = null
    private var failed = false

    /** Записать кадр и состояние после его обработки. [phase] — SEARCH/HOLD/COUNT. */
    fun write(frame: PoseFrame, phase: String, state: RepState) {
        if (closed) return
        val first = t0 < 0
        if (first) t0 = frame.timeMs
        if (frame.points.isNotEmpty()) sawPerson = true
        val line = PoseLogFormat.line(frame, t0, phase, state)
        val startedAt = if (first) System.currentTimeMillis() else 0L
        enqueue {
            if (first) open(startedAt)
            append(line)
        }
    }

    /** Панель вернулась к поиску не из-за кадра (пауза, переключение). До первого кадра — ничего. */
    fun markReset() {
        if (closed || t0 < 0) return
        enqueue { append(PoseLogFormat.RESET_MARK) }
    }

    /** Сбросить и закрыть файл. Запись без единого кадра с человеком удаляется. */
    fun close() {
        if (closed) return
        closed = true
        val keep = sawPerson
        enqueue {
            try {
                out?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Не удалось закрыть запись позы", e)
            }
            out = null
            val f = file
            if (f != null && !keep && !f.delete()) Log.w(TAG, "Не удалось удалить пустую запись $f")
        }
        io.shutdown()
    }

    private fun enqueue(task: () -> Unit) {
        try {
            io.execute {
                try {
                    task()
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка записи позы", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Запись позы уже закрыта", e)
        }
    }

    private fun append(line: String) {
        val w = out ?: return
        if (failed) return
        try {
            w.write(line)
            w.newLine()
        } catch (e: IOException) {
            failed = true
            Log.w(TAG, "Ошибка записи позы в $file", e)
        }
    }

    private fun open(startedAt: Long) {
        try {
            val dir = appContext.getExternalFilesDir(DIR) ?: File(appContext.filesDir, DIR)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("нет каталога $dir")
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAt))
            val safeId = exerciseId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            var f = File(dir, "$stamp-$safeId.csv")
            var n = 2
            while (f.exists()) f = File(dir, "$stamp-$safeId-${n++}.csv")
            val w = f.bufferedWriter(Charsets.UTF_8)
            file = f
            out = w
            prune(dir)

            val started = OffsetDateTime.ofInstant(Instant.ofEpochMilli(startedAt), ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.SECONDS)
                .toString()
            val extra = if (target != null) mapOf("target" to target.toString()) else emptyMap()
            w.write(PoseLogFormat.header(exerciseId, front, Build.MODEL ?: "unknown", appVersion(), started, extra))
            w.newLine()
            w.write(PoseLogFormat.columns())
            w.newLine()
        } catch (e: Exception) {
            failed = true
            Log.w(TAG, "Не удалось начать запись позы", e)
        }
    }

    /** Оставить не больше [KEEP] последних записей (имя начинается с даты-времени). */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") } ?: return
        if (files.size <= KEEP) return
        files.sortedWith(compareByDescending<File> { it.name }).drop(KEEP).forEach {
            if (it != file && !it.delete()) Log.w(TAG, "Не удалось удалить старую запись $it")
        }
    }

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    companion object {
        private const val TAG = "PoseLog"
        const val DIR = "poselog"
        const val KEEP = 40
    }
}
