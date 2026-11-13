package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PoseLogFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val header = PoseLogFormat.header("squats", true, "Pixel 7 Pro", "0.1.0", "2026-11-12T10:15:30+03:00")

    private fun roundTrip(frames: List<PoseFrame>, states: List<Pair<String, RepState>>): PoseLogData {
        val t0 = frames.first().timeMs
        val lines = listOf(header, PoseLogFormat.columns()) +
            frames.indices.map { PoseLogFormat.line(frames[it], t0, states[it].first, states[it].second) }
        lines.forEach { assertFalse("перевод строки внутри строки", it.contains('\n') || it.contains('\r')) }
        return PoseLogFormat.parse(lines.asSequence())
    }

    @Test
    fun headerAndColumns() {
        assertTrue(header, header.startsWith("# nll-poselog v3 "))
        val data = PoseLogFormat.parse(sequenceOf(header, PoseLogFormat.columns()))
        assertEquals("v3", data.version)
        assertEquals("squats", data.exerciseId)
        assertTrue(data.front)
        assertEquals("Pixel_7_Pro", data.meta["device"])
        assertEquals("0.1.0", data.meta["app"])
        assertEquals("2026-11-12T10:15:30+03:00", data.meta["started"])
        assertTrue(data.frames.isEmpty())
        val cols = PoseLogFormat.columns().split(';')
        assertEquals(3 + 13 * 3 + 8, cols.size)
        assertEquals("atStart", cols[cols.size - 2])
        assertEquals("heldMs", cols.last())
    }

    @Test
    fun lineParseRestoresFramesAndStates() {
        val full = Joint.entries.associateWith { j ->
            PosePoint(100.04f + j.ordinal * 31.37f, 812.96f - j.ordinal * 17.55f, 0.123f + j.ordinal * 0.061f)
        }
        val partial = mapOf(
            Joint.LEFT_SHOULDER to PosePoint(-3.26f, 0.04f, 0.995f),
            Joint.RIGHT_ANKLE to PosePoint(479.95f, 639.99f, 0.5f),
        )
        val frames = listOf(
            PoseFrame(5_000_123, 480, 640, full),
            PoseFrame(5_000_156, 480, 640, emptyMap()),
            PoseFrame(5_000_190, 640, 480, partial),
        )
        val states = listOf(
            "SEARCH" to RepState(0, true, false, "Встань прямо", 0f, atStart = false),
            "HOLD" to RepState(0, false, false, "", 0.4567f, atStart = true),
            "COUNT" to RepState(7, true, true, "Ниже; ещё\nниже\r\n!", 1f, atStart = true, heldMs = 42_517L),
        )
        val data = roundTrip(frames, states)

        assertEquals(3, data.frames.size)
        assertEquals(listOf(0L, 33L, 67L), data.frames.map { it.timeMs })
        assertEquals(listOf(480, 480, 640), data.frames.map { it.width })
        assertEquals(listOf(640, 640, 480), data.frames.map { it.height })

        for (i in frames.indices) {
            val want = frames[i].points
            val got = data.frames[i].points
            assertEquals("набор суставов кадра $i", want.keys, got.keys)
            for ((j, p) in want) {
                val q = got.getValue(j)
                assertEquals(p.x, q.x, 0.051f)
                assertEquals(p.y, q.y, 0.051f)
                assertEquals(p.confidence, q.confidence, 0.0051f)
            }
        }
        assertEquals(-3.3f, data.frames[2].points.getValue(Joint.LEFT_SHOULDER).x, 1e-4f)
        assertEquals(0.0f, data.frames[2].points.getValue(Joint.LEFT_SHOULDER).y, 1e-4f)
        assertNull(data.frames[2].points[Joint.NOSE])

        assertEquals(listOf("SEARCH", "HOLD", "COUNT"), data.recorded.map { it.phase })
        assertEquals(listOf(0, 0, 7), data.recorded.map { it.count })
        assertEquals(listOf(true, false, true), data.recorded.map { it.tracking })
        assertEquals(listOf(false, false, true), data.recorded.map { it.inPosition })
        assertEquals(listOf(false, true, true), data.recorded.map { it.atStart })
        assertEquals(listOf(0L, 0L, 42_517L), data.recorded.map { it.heldMs })
        assertEquals(0.457f, data.recorded[1].depth, 1e-4f)
        assertEquals("Встань прямо", data.recorded[0].hint)
        assertEquals("", data.recorded[1].hint)
        assertEquals("Ниже, ещё ниже !", data.recorded[2].hint)
    }

    @Test
    fun formatIsLocaleIndependent() {
        val frame = PoseFrame(0, 480, 640, mapOf(Joint.NOSE to PosePoint(12.25f, 7.5f, 0.5f)))
        val line = PoseLogFormat.line(frame, 0, "SEARCH", RepState(depth = 0.5f))
        assertTrue(line, line.startsWith("0;480;640;12.3;7.5;0.50;;;"))
        assertTrue(line, line.endsWith(";SEARCH;0;0;0;0.500;;0;0"))
        val at = PoseLogFormat.line(frame, 0, "HOLD", RepState(tracking = true, hint = "x", atStart = true))
        assertTrue(at, at.endsWith(";HOLD;0;1;0;0.000;x;1;0"))
        val held = PoseLogFormat.line(frame, 0, "COUNT", RepState(tracking = true, inPosition = true, heldMs = 31_250L))
        assertTrue(held, held.endsWith(";COUNT;0;1;1;0.000;;0;31250"))
    }

    /** Записи первой версии (без atStart и heldMs) по-прежнему читаются; atStart = false, heldMs = 0. */
    @Test
    fun readsV1() {
        val v1Header = "# nll-poselog v1 exercise=pushups front=false device=X app=0.1.0 started=2026-11-12T10:15:30+03:00"
        val v3Columns = PoseLogFormat.columns().split(';')
        val v1Columns = v3Columns.dropLast(2).joinToString(";")
        val nose = "240.0;100.0;0.95"
        val row = (listOf("0", "480", "640", nose) + List(12) { ";;" } + listOf("COUNT", "3", "1", "1", "0.250", "Ниже"))
            .joinToString(";")
        assertEquals(v3Columns.size - 2, row.split(';').size)
        val data = PoseLogFormat.parse(sequenceOf(v1Header, v1Columns, row, row))
        assertEquals("v1", data.version)
        assertEquals("pushups", data.exerciseId)
        assertFalse(data.front)
        assertEquals(2, data.frames.size)
        assertEquals(setOf(Joint.NOSE), data.frames[0].points.keys)
        assertEquals(RecordedState("COUNT", 3, true, true, 0.25f, "Ниже", atStart = false, heldMs = 0L), data.recorded[0])
    }

    /** Записи v2 (с atStart, без heldMs) по-прежнему читаются; heldMs = 0. */
    @Test
    fun readsV2() {
        val v2Header = "# nll-poselog v2 exercise=squats front=true device=X app=0.2.0 started=2026-11-12T10:15:30+03:00"
        val v2Columns = PoseLogFormat.columns().removeSuffix(";heldMs")
        val nose = "240.0;100.0;0.95"
        val row = (listOf("0", "480", "640", nose) + List(12) { ";;" } + listOf("COUNT", "4", "1", "1", "0.500", "Ниже", "1"))
            .joinToString(";")
        assertEquals(v2Columns.split(';').size, row.split(';').size)
        val data = PoseLogFormat.parse(sequenceOf(v2Header, v2Columns, row))
        assertEquals("v2", data.version)
        assertEquals(RecordedState("COUNT", 4, true, true, 0.5f, "Ниже", atStart = true, heldMs = 0L), data.recorded[0])
    }

    @Test
    fun resetMarksAndComments() {
        val f = PoseFrame(10, 480, 640, emptyMap())
        val l = PoseLogFormat.line(f, 10, "SEARCH", RepState())
        val data = PoseLogFormat.parse(
            sequenceOf(header, PoseLogFormat.columns(), l, "", PoseLogFormat.RESET_MARK, l, "# что-то ещё", l),
        )
        assertEquals(3, data.frames.size)
        assertEquals(setOf(1), data.resets)
    }

    @Test
    fun brokenFilesFail() {
        fun fails(vararg lines: String) = try {
            PoseLogFormat.parse(lines.asSequence())
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(fails())
        assertTrue(fails("t;w;h"))
        assertTrue(fails("# something else", PoseLogFormat.columns()))
        assertTrue(fails("# nll-poselog v9 exercise=squats", PoseLogFormat.columns()))
        assertTrue(fails(header, "t;w;h;phase"))
        // v3 без столбца heldMs — ошибка; v2 без atStart — тоже.
        assertTrue(fails(header, PoseLogFormat.columns().removeSuffix(";heldMs")))
        assertTrue(
            fails(
                "# nll-poselog v2 exercise=squats",
                PoseLogFormat.columns().removeSuffix(";heldMs").removeSuffix(";atStart"),
            ),
        )
        val good = PoseLogFormat.line(PoseFrame(0, 1, 1, emptyMap()), 0, "SEARCH", RepState())
        assertTrue(fails(header, PoseLogFormat.columns(), good.replaceFirst("0;", "x;")))
        assertTrue(fails(header, PoseLogFormat.columns(), good.removeSuffix(";0;0") + ";7;0"))
        assertTrue(fails(header, PoseLogFormat.columns(), good.removeSuffix(";0") + ";x"))
        assertTrue(fails(header, PoseLogFormat.columns(), "0;1;1"))
    }

    /**
     * Сквозная проверка «телефон → файл → проигрывание»: панель (эталон PanelSim) на заглушке
     * счётчика пишет запись; проигрывание той же заглушкой даёт тот же счёт и те же состояния.
     * Заглушка — чтобы не зависеть от того, как настоящие счётчики заполняют atStart.
     */
    @Test
    fun syntheticSetReplaysToSameCount() {
        val s = StubScript()
            .lost(500) // человека ещё нет
            .atStart(1200) // удержание → счёт
            .reps(4)
            .lost(4000) // ушёл из кадра дольше 3 с → ПОИСК
            .atStart(1200)
            .reps(2)
        val pauseAt = s.frames.size
        s.atStart(400).reps(1).atStart(1200).reps(1).atStart(300)

        // «Телефон»: панель с записью. Посередине — пауза (как LaunchedEffect при active=false→true).
        val phone = PanelSim(StubCounter(), target = null)
        val t0 = s.frames.first().timeMs
        val lines = ArrayList<String>()
        lines += PoseLogFormat.header("squats", false, "test", "0", "2026-11-12T00:00:00Z")
        lines += PoseLogFormat.columns()
        for ((i, f) in s.frames.withIndex()) {
            if (i == pauseAt) {
                phone.toSearch()
                lines += PoseLogFormat.RESET_MARK
            }
            val st = phone.onFrame(f)
            lines += PoseLogFormat.line(f, t0, phone.phase.name, st)
        }
        assertEquals("заглушка должна насчитать 7", 7, phone.announced)

        val file = tmp.newFile("20261112-000000-squats.csv")
        file.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)

        val data = PoseLogReplay.read(file)
        assertEquals(s.frames.size, data.frames.size)
        assertEquals(setOf(pauseAt), data.resets)
        val r = PoseLogReplay.analyze(file.name, data) { StubCounter() }
        println(r.report)
        assertEquals(7, r.phoneCount)
        assertEquals(7, r.replayCount)
        assertEquals(phone.repTimes.map { it - t0 }, r.repTimes)
        for (i in data.recorded.indices) {
            val rec = data.recorded[i]
            val st = r.states[i]
            assertEquals("count, кадр $i", rec.count, st.count)
            assertEquals("tracking, кадр $i", rec.tracking, st.tracking)
            assertEquals("inPosition, кадр $i", rec.inPosition, st.inPosition)
            assertEquals("atStart, кадр $i", rec.atStart, st.atStart)
            assertEquals("phase, кадр $i", rec.phase, r.phases[i])
            assertEquals("hint, кадр $i", rec.hint, st.hint)
        }
        assertTrue(r.report, r.report.contains("СЧЁТ: телефон 7, проигрывание 7"))
        assertTrue(r.report, r.report.contains("совпадение состояний проигрывания с телефоном: 100%"))
    }

    /** Отчёт по настоящим счётчикам строится на любой записи (сам счёт здесь не проверяется). */
    @Test
    fun reportWithRealCounters() {
        val stand = Key(0f, 180f, 180f, 90f, 90f)
        val bottom = Key(40f, 95f, 200f, 90f, 90f)
        val tl = Timeline().hold(stand, 1500).reps(listOf(stand, bottom), 3, 900).hold(stand, 500)
        val t0 = tl.frames.first().timeMs
        val lines = listOf(PoseLogFormat.header("squats", true, "test", "0", "x"), PoseLogFormat.columns()) +
            tl.frames.map { PoseLogFormat.line(it, t0, "SEARCH", RepState()) }
        val r = PoseLogReplay.analyze("real", PoseLogFormat.parse(lines.asSequence()))
        assertTrue(r.report, r.report.contains("колено, °"))
        assertTrue(r.replayCount != null)
    }
}
