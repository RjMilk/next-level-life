package com.nextlevellife.app.pose

import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.data.FreeSetStatus
import com.nextlevellife.app.data.FreeWorkoutLog
import com.nextlevellife.app.data.FreeWorkoutMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Вольная тренировка на синтетических кадрах (генераторы — как у тестов счётчиков). */
class FreeSessionTest {
    private fun spec(id: String) = SPECS.first { it.id == id }
    private val squats get() = spec("squats")
    private val pushups get() = spec("pushups")

    /** Подход для проверки: упражнение и повторения (или секунды удержания). */
    private data class Done(val id: String, val reps: Int, val heldMs: Long = 0L)

    private class Run(val classifier: FreeSessionClassifier, val states: List<FreeState>, val events: List<FreeEvent>, val log: FreeWorkoutLog) {
        val sets get() = log.sets
    }

    private fun run(
        frames: List<PoseFrame>,
        candidates: List<FreeCandidate> = FreeWorkoutMath.candidates,
        counterOf: (String) -> RepCounter? = RepCounters::forExercise,
    ): Run {
        val c = FreeSessionClassifier(candidates, counterOf = counterOf)
        val states = frames.map { c.onFrame(it) }
        val events = states.flatMap { it.events } + c.finish(frames.last().timeMs + 33)
        return Run(c, states, events, FreeWorkoutLog().applyAll(events))
    }

    private fun done(r: Run) = r.sets.map { Done(it.exerciseId, it.reps, if (it.heldMs > 0) it.heldMs else 0L) }

    private fun repsOnly(r: Run) = r.sets.map { it.exerciseId to it.reps }

    @Test
    fun onlyBodyweightExercises() {
        // Снаряд камера не видит: в вольном режиме только упражнения со своим весом.
        assertEquals(Exercises.bodyweight.map { it.id }.toSet(), FreeWorkoutMath.exercises.map { it.id }.toSet())
        assertTrue(FreeWorkoutMath.exercises.none { it.kettlebell || it.isGym })
        val c = FreeSessionClassifier(FreeWorkoutMath.candidates)
        assertEquals(FreeWorkoutMath.exercises.map { it.id }, c.candidateIds)
    }

    @Test
    fun squatsPushupsSquatsGiveThreeSets() {
        val sq = squats
        val pu = pushups
        val tl = Timeline()
            .hold(sq.rest, 1_500).reps(sq.path, 5, sq.segmentMs).hold(sq.rest, 1_000)
            .move(sq.rest, pu.rest, 1_200)
            .hold(pu.rest, 1_500).reps(pu.path, 4, pu.segmentMs).hold(pu.rest, 1_000)
            .move(pu.rest, sq.rest, 1_200)
            .hold(sq.rest, 1_500).reps(sq.path, 3, sq.segmentMs).hold(sq.rest, 1_000)
        val r = run(tl.frames)
        assertEquals(listOf("squats" to 5, "pushups" to 4, "squats" to 3), repsOnly(r))
        assertTrue(r.sets.all { it.status == FreeSetStatus.CLOSED })
        // Подходы закрыты сменой упражнения и «Закончить».
        val reasons = r.events.filterIsInstance<FreeEvent.Closed>().map { it.reason }
        assertEquals(listOf(FreeCloseReason.CHANGE, FreeCloseReason.CHANGE, FreeCloseReason.FINISH), reasons)
        // Номера подходов идут подряд: из них ключи записи.
        assertEquals(listOf(1, 2, 3), r.sets.map { it.number })
    }

    @Test
    fun eachRepExerciseIsRecognizedAlone() {
        for (id in listOf("pushups", "squats", "abs", "pullups")) {
            val s = spec(id)
            val tl = Timeline().hold(s.rest, 1_500).reps(s.path, 5, s.segmentMs).hold(s.rest, 1_000)
            val r = run(tl.frames)
            assertEquals(id, listOf(id to 5), repsOnly(r))
            assertEquals(id, id, r.states.last().current)
        }
    }

    @Test
    fun oneRepIsNeverCountedTwice() {
        // Два кандидата с одинаковым счётчиком приседаний: каждое движение засчитали бы оба.
        val twins = listOf(FreeCandidate("a", 40), FreeCandidate("b", 30))
        val sq = squats
        val tl = Timeline().hold(sq.rest, 1_500).reps(sq.path, 6, sq.segmentMs).hold(sq.rest, 1_000)
        val r = run(tl.frames, twins) { RepCounters.forExercise("squats") }
        assertEquals(6, r.events.filterIsInstance<FreeEvent.Reps>().sumOf { it.n })
        assertEquals(1, r.sets.size)
    }

    @Test
    fun disputedRepGoesToCurrentThenCheaper() {
        val a = FreeCandidate("a", 40)
        val b = FreeCandidate("b", 30)
        val c = FreeCandidate("c", 30)
        assertEquals("текущее упражнение важнее цены", a, FreeSessionClassifier.winner(listOf(a, b), "a"))
        assertEquals("иначе — более дешёвое", b, FreeSessionClassifier.winner(listOf(a, b), null))
        assertEquals("иначе — более дешёвое", b, FreeSessionClassifier.winner(listOf(a, b), "x"))
        assertEquals("при равной цене — раньше в списке", b, FreeSessionClassifier.winner(listOf(b, c), null))
    }

    @Test
    fun poseJitterDoesNotSwitchExercise() {
        val sq = squats
        val pu = pushups
        // Посреди подхода приседаний 5 кадров (≈ 0,17 с) «упора лёжа» — выброс, а не смена упражнения.
        val tl = Timeline().hold(sq.rest, 1_500).reps(sq.path, 3, sq.segmentMs).hold(sq.rest, 300)
        repeat(5) { tl.frame(pu.rest) }
        tl.hold(sq.rest, 300).reps(sq.path, 3, sq.segmentMs).hold(sq.rest, 1_000)
        val r = run(tl.frames)
        assertEquals(listOf("squats" to 6), repsOnly(r))
        assertTrue(r.states.dropWhile { it.current == null }.all { it.current == "squats" })

        // Шум углов на каждом кадре (до ±6° в суставе), 10 зёрен: ни переключений, ни лишних подходов.
        for (seed in 1..10) {
            val noisy = Timeline(noise = Random(seed * 17))
                .hold(sq.rest, 1_500).reps(sq.path, 5, sq.segmentMs).hold(sq.rest, 1_000)
            val rn = run(noisy.frames)
            assertEquals("зерно $seed", listOf("squats" to 5), repsOnly(rn))
        }
    }

    @Test
    fun pauseOfTwentySecondsClosesSet() {
        val sq = squats
        val tl = Timeline().hold(sq.rest, 1_500).reps(sq.path, 5, sq.segmentMs)
            .hold(sq.rest, 21_000).reps(sq.path, 3, sq.segmentMs).hold(sq.rest, 1_000)
        val r = run(tl.frames)
        assertEquals(listOf("squats" to 5, "squats" to 3), repsOnly(r))
        assertEquals(FreeCloseReason.PAUSE, r.events.filterIsInstance<FreeEvent.Closed>().first().reason)

        // Пауза короче 20 с подход не закрывает.
        val short = Timeline().hold(sq.rest, 1_500).reps(sq.path, 5, sq.segmentMs)
            .hold(sq.rest, 15_000).reps(sq.path, 3, sq.segmentMs).hold(sq.rest, 1_000)
        assertEquals(listOf("squats" to 8), repsOnly(run(short.frames)))
    }

    @Test
    fun plankCountsAsHold() {
        // Живой вход: стоит перед телефоном, потом ложится в планку на 30 с.
        val tl = Timeline().hold(STAND, 1_000).hold(PLANK, 30_000).hold(STAND, 2_000)
        val r = run(tl.frames)
        // Тот же отрезок, посчитанный обычным счётчиком планки.
        val alone = RepCounters.forExercise("plank")!!.feed(tl.frames).last().heldMs
        assertTrue("планка одна: $alone", alone in 29_000L..31_000L)
        assertEquals(listOf(Done("plank", 0, alone)), done(r))
        assertEquals(FreeCloseReason.HOLD_END, r.events.filterIsInstance<FreeEvent.Closed>().first().reason)
        assertTrue(r.events.none { it is FreeEvent.Reps })
        // Длительность подхода не короче засчитанного времени (иначе запись его урежет).
        assertTrue(r.sets.single().durationMs >= alone)
    }

    @Test
    fun plankIsNotLongerThanCounterCap() {
        val tl = Timeline().hold(STAND, 1_000).hold(PLANK, 200_000)
        val r = run(tl.frames)
        val set = r.sets.single()
        assertEquals("plank", set.exerciseId)
        assertEquals(CounterThresholds.Plank.MAX_SEGMENT_MS, set.heldMs)
    }

    @Test
    fun shortPlankIsNothing() {
        // Меньше 10 с — счётчик планки не засчитывает, подхода нет.
        val tl = Timeline().hold(STAND, 1_000).hold(PLANK, 8_000).hold(STAND, 2_000)
        assertTrue(run(tl.frames).sets.isEmpty())
    }

    @Test
    fun nothingCountsWithoutStartPose() {
        // Движения не в позе упражнения (например, «приседания» лёжа на боку).
        for (id in listOf("pushups", "squats", "abs", "pullups")) {
            val s = spec(id)
            val tl = Timeline().hold(s.wrong[0], 1_500).reps(s.wrong, 5, s.segmentMs).hold(s.wrong[0], 1_000)
            val r = run(tl.frames)
            assertTrue("$id: ${repsOnly(r)}", r.sets.none { it.exerciseId == id })
        }
        // Повторения сразу, без секунды в исходной позе: исходная поза ни разу не держалась 0,7 с.
        val sq = squats
        val tl = Timeline().reps(sq.path, 5, sq.segmentMs)
        val r = run(tl.frames)
        assertTrue(repsOnly(r).toString(), r.sets.isEmpty())
        assertNull(r.states.last().current)
        // Человека нет в кадре.
        val empty = Timeline().empty(3_000)
        val re = run(empty.frames)
        assertTrue(re.sets.isEmpty())
        assertNull(re.states.last().current)
        assertEquals("Встань в кадр целиком", re.states.last().hint)
    }

    @Test
    fun realisticStreamOfThreeExercises() {
        // 12 к/с, неравномерные кадры, шум, «дыхание» уверенности, три четверти; 10 зёрен.
        val results = (1..10).map { seed ->
            val sq = squats
            val pu = pushups
            val ab = spec("abs")
            val stream = RealisticStream(seed * 4243)
                .hold(sq.rest, 1_500).reps(sq.path, 10).hold(sq.rest, 1_000)
                .hold(pu.rest, 1_500).reps(pu.path, 10).hold(pu.rest, 1_000)
                .hold(ab.rest, 1_500).reps(ab.path, 10).hold(ab.rest, 1_000)
            seed to repsOnly(run(stream.frames))
        }
        val bad = results.filter { (_, sets) ->
            sets.map { it.first } != listOf("squats", "pushups", "abs") || sets.any { it.second !in 9..10 }
        }
        assertTrue("ожидалось 3 подхода по 9–10: $bad", bad.isEmpty())
    }

    @Test
    fun resumeContinuesOpenSet() {
        // Экран пересоздан посреди подхода: новый классификатор продолжает тот же подход.
        val sq = squats
        val first = Timeline().hold(sq.rest, 1_500).reps(sq.path, 4, sq.segmentMs).hold(sq.rest, 500)
        val c1 = FreeSessionClassifier(FreeWorkoutMath.candidates)
        var log = FreeWorkoutLog().applyAll(first.frames.flatMap { c1.onFrame(it).events })
        assertEquals(4, log.open?.reps)

        val restored = FreeWorkoutLog.decode(log.encode())
        assertEquals(log, restored)
        val c2 = FreeSessionClassifier(FreeWorkoutMath.candidates, resume = restored.resume())
        assertEquals("squats", c2.current)
        val second = Timeline().hold(sq.rest, 1_500).reps(sq.path, 3, sq.segmentMs).hold(sq.rest, 1_000)
        val shifted = second.frames.map { PoseFrame(it.timeMs + 20_000, it.width, it.height, it.points) }
        log = restored.applyAll(shifted.flatMap { c2.onFrame(it).events } + c2.finish(shifted.last().timeMs))
        assertEquals(listOf("squats" to 7), log.sets.map { it.exerciseId to it.reps })
    }

    @Test
    fun repSetStartsAtFirstRepNotAtStartPose() {
        // Реальный счётчик приседаний: 5 с стоит в исходной позе, потом 5 повторов. Длительность подхода — работа.
        val sq = squats
        val tl = Timeline().hold(sq.rest, 5_000)
        val firstRepAt = tl.nextTimeMs
        tl.reps(sq.path, 5, sq.segmentMs).hold(sq.rest, 1_000)
        val set = run(tl.frames).sets.single()
        assertEquals(5, set.reps)
        assertTrue("начало ${set.startMs - firstRepAt} мс от первого повтора", set.startMs >= firstRepAt - 100)
        assertTrue("длительность ${set.durationMs}", set.durationMs <= 5 * 2 * sq.segmentMs + 200)
    }

    // ---------------------------------------------------------------------------------------------
    // Сценарии на счётчиках-«куклах»: каждый повтор и каждая поза задаются тестом точно.
    // ---------------------------------------------------------------------------------------------

    /**
     * Счётчик-кукла: [startRep] — пошёл повтор (не в исходной точке), [finishRep] — повтор засчитан, если цикл
     * не сброшен ([reset] посреди повтора его теряет, как у настоящего счётчика). [held] — засчитанное удержание.
     */
    private class Puppet(override val kind: CountKind = CountKind.REPS) : RepCounter {
        var count = 0
        var held = 0L
        var atStart = true
        var inPos = true
        var resets = 0
        private var repOpen = false
        override var state = RepState()
            private set

        fun startRep() {
            atStart = false
            repOpen = true
        }

        fun finishRep() {
            if (repOpen) count++
            repOpen = false
            atStart = true
        }

        override fun onFrame(frame: PoseFrame): RepState {
            state = RepState(
                count = count, tracking = true, inPosition = inPos, atStart = atStart && inPos,
                heldMs = held, holding = kind == CountKind.HOLD && inPos,
            )
            return state
        }

        override fun reset() {
            resets++
            count = 0
            repOpen = false
            state = RepState()
        }
    }

    /** Кадры по 33 мс в классификатор; всё засчитанное копится в [events]. */
    private class Puppets(vararg ids: Pair<String, CountKind>) {
        val puppets = ids.associate { (id, kind) -> id to Puppet(kind) }
        val c = FreeSessionClassifier(ids.mapIndexed { i, (id, _) -> FreeCandidate(id, 30 - i) }) { puppets[it] }
        val events = ArrayList<FreeEvent>()
        var t = 100_000L

        operator fun get(id: String) = puppets.getValue(id)

        fun tick(ms: Long) {
            val end = t + ms
            while (t < end) {
                events += c.onFrame(PoseFrame(t, 1080, 1920, emptyMap())).events
                t += 33
            }
        }

        /** Полный повтор [id]: [ms] в движении, потом кадры в исходной точке. */
        fun rep(id: String, ms: Long = 600, after: Long = 400) {
            get(id).startRep()
            tick(ms)
            get(id).finishRep()
            tick(after)
        }

        fun log(): FreeWorkoutLog = FreeWorkoutLog().applyAll(events + c.finish(t))

        fun sets(): List<Pair<String, Int>> = log().sets.map { it.exerciseId to it.reps }
    }

    @Test
    fun firstSetStartsOnFirstRepOfAnyExercise() {
        // Ничего ещё не засчитано: первый же повтор другого (не угаданного) упражнения открывает его подход.
        val p = Puppets("squats" to CountKind.REPS, "b" to CountKind.REPS)
        p.tick(1_000)
        assertEquals("squats", p.c.current)
        p.rep("b")
        assertEquals("b", p.c.current)
        assertEquals(1, p.c.state.setReps)
    }

    @Test
    fun strayRepMidSquatDoesNotSwitchOrResetSquat() {
        val p = Puppets("squats" to CountKind.REPS, "b" to CountKind.REPS)
        p.tick(1_000)
        p.rep("squats")
        // Посреди приседа «b» засчитал повтор; присед заканчивается позже окна спора.
        p["squats"].startRep()
        p.tick(300)
        p.rep("b", ms = 100, after = 700)
        assertEquals("одиночный повтор другого упражнения подход не меняет", "squats", p.c.current)
        p["squats"].finishRep()
        p.tick(400)
        p.rep("squats")
        assertEquals(0, p["squats"].resets)
        assertEquals(listOf("squats" to 3), p.sets())
        assertTrue(p.events.none { it is FreeEvent.Closed })
    }

    @Test
    fun strayRepAfterSquatIsDropped() {
        // Тот же физический повтор «b» засчитал позже окна спора: он не оплачивается и подход не делит.
        val p = Puppets("squats" to CountKind.REPS, "b" to CountKind.REPS)
        p.tick(1_000)
        p.rep("squats")
        p.rep("squats", after = 0)
        p.tick(700)
        p.rep("b", ms = 100)
        p.rep("squats")
        assertEquals(listOf("squats" to 3), p.sets())
        assertTrue(p.events.filterIsInstance<FreeEvent.Reps>().none { it.exerciseId == "b" })
    }

    @Test
    fun twoRepsInARowSwitchExerciseAndCountBoth() {
        val p = Puppets("squats" to CountKind.REPS, "b" to CountKind.REPS)
        p.tick(1_000)
        p.rep("squats")
        p.rep("squats")
        p.tick(1_000)
        val bStart = p.t
        p.rep("b")
        assertEquals("squats", p.c.current)
        p.rep("b")
        assertEquals("b", p.c.current)
        p.rep("b")
        val log = p.log()
        assertEquals(listOf("squats" to 2, "b" to 3), log.sets.map { it.exerciseId to it.reps })
        // Подход «b» — с его первого повтора, а не с момента подтверждения.
        val b = log.sets.last()
        assertTrue("начало ${b.startMs - bStart}", b.startMs in bStart - 50..bStart)
        val reasons = p.events.filterIsInstance<FreeEvent.Closed>().map { it.reason }
        assertEquals(listOf(FreeCloseReason.CHANGE), reasons)
    }

    @Test
    fun strayRepConfirmedByPoseSwitch() {
        // Повтор «b», потом присед кончился (вне позы), а «b» устойчиво в исходной позе: смена по позе
        // подтверждает и отложенный повтор.
        val p = Puppets("squats" to CountKind.REPS, "b" to CountKind.REPS)
        p.tick(1_000)
        p.rep("squats")
        p.tick(700)
        p.rep("b")
        p["squats"].inPos = false
        p.tick(1_000)
        assertEquals("b", p.c.current)
        p.rep("b")
        assertEquals(listOf("squats" to 1, "b" to 2), p.sets())
    }

    @Test
    fun squatRepBetweenStrayRepsKeepsSquats() {
        // b, присед, b: повторы «b» не подряд — не подтверждение.
        val p = Puppets("squats" to CountKind.REPS, "b" to CountKind.REPS)
        p.tick(1_000)
        p.rep("squats")
        p.tick(700)
        p.rep("b")
        p.rep("squats")
        p.tick(700)
        p.rep("b")
        p.rep("squats")
        assertEquals(listOf("squats" to 3), p.sets())
    }

    @Test
    fun plankCreditKeepsPendingSquatRep() {
        val p = Puppets("squats" to CountKind.REPS, "b" to CountKind.REPS, "plank" to CountKind.HOLD)
        p.tick(1_000)
        p.rep("squats")
        // «b» посреди движения — повтор приседа ждёт спора; на следующем кадре засчитывается планка.
        p["b"].startRep()
        p["squats"].startRep()
        p.tick(600)
        p["squats"].finishRep()
        p.tick(33)
        p["plank"].held = 10_000
        p.tick(33)
        assertEquals("plank", p.c.current)
        val log = p.log()
        assertEquals(listOf("squats" to 2, "plank" to 0), log.sets.map { it.exerciseId to it.reps })
        assertEquals(10_000L, log.sets.last().heldMs)
    }

    @Test
    fun noFramesAfterFinish() {
        val p = Puppets("squats" to CountKind.REPS, "b" to CountKind.REPS)
        p.tick(1_000)
        p.rep("squats")
        val log = p.log()
        assertEquals(listOf("squats" to 1), log.sets.map { it.exerciseId to it.reps })
        // Кадры из очереди камеры после «Закончить»: ни нового подхода, ни событий.
        p.events.clear()
        p.rep("squats")
        p.rep("b")
        assertTrue(p.events.toString(), p.events.isEmpty())
        assertTrue(p.c.finish(p.t).isEmpty())
        assertEquals(0, p.c.state.setReps)
    }

    @Test
    fun setStartIgnoresRestBeforeFirstRep() {
        val p = Puppets("squats" to CountKind.REPS, "b" to CountKind.REPS)
        p.tick(5_000)
        val first = p.t
        p.rep("squats")
        p.rep("squats")
        // Отдых больше 20 с закрывает подход; следующий подход — тоже с первого повтора.
        p.tick(25_000)
        val second = p.t
        p.rep("squats")
        val sets = p.log().sets
        assertEquals(listOf(2, 1), sets.map { it.reps })
        assertTrue("первый: ${sets[0].startMs - first}", sets[0].startMs in first - 50..first)
        assertTrue("второй: ${sets[1].startMs - second}", sets[1].startMs in second - 50..second)
    }

    @Test
    fun firstRepOfNotGuessedExerciseKeepsItsTime() {
        // Угадано «squats», а первый повтор — «b»: подход «b» начинается с начала этого повтора, а не в момент смены
        // (иначе подход из одного повтора длится 0 мс).
        val p = Puppets("squats" to CountKind.REPS, "b" to CountKind.REPS)
        p.tick(1_000)
        assertEquals("squats", p.c.current)
        val start = p.t
        p.rep("b")
        val set = p.log().sets.single()
        assertEquals("b", set.exerciseId)
        assertTrue("начало: ${set.startMs - start}", set.startMs in start - 50..start)
    }
}
