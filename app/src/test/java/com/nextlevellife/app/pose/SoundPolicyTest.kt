package com.nextlevellife.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundPolicyTest {

    private val all = SoundPrefs(voice = true, tones = true, vibration = true)
    private val noVoice = SoundPrefs(voice = false, tones = true, vibration = false)
    private val off = SoundPrefs(voice = false, tones = false, vibration = false)

    private fun plan(e: SoundEvent, p: SoundPrefs, text: Boolean = true, ready: Boolean = true, fallback: Cue? = null) =
        SoundPolicy.plan(e, p, hasText = text, voiceReady = ready, fallback = fallback)

    @Test
    fun defaultsAreVoiceAndTonesWithoutVibration() {
        assertEquals(SoundPrefs(voice = true, tones = true, vibration = false), SoundPrefs())
        // По умолчанию повтор — щелчок и число голосом, как до настроек.
        assertEquals(SoundPlan(speak = true, cue = Cue.COUNTED, buzz = null), plan(SoundEvent.REP, SoundPrefs()))
        // Подсказка голосом сигналом не дублируется.
        assertEquals(SoundPlan(speak = true, cue = null, buzz = null), plan(SoundEvent.HINT, SoundPrefs()))
    }

    @Test
    fun threeCuesAreDistinct() {
        val cues = listOf(SoundEvent.REP, SoundEvent.MISS, SoundEvent.HINT).map { plan(it, noVoice).cue }
        assertEquals(listOf(Cue.COUNTED, Cue.MISSED, Cue.HINT), cues)
        // «Готов» и «готово» — прежние, отдельные сигналы.
        assertEquals(Cue.READY, plan(SoundEvent.READY, noVoice, text = false).cue)
        assertEquals(Cue.DONE, plan(SoundEvent.DONE, noVoice, text = false).cue)
    }

    @Test
    fun voiceOffReplacesSpeechWithCues() {
        assertEquals(SoundPlan(speak = false, cue = Cue.HINT, buzz = null), plan(SoundEvent.HINT, noVoice))
        assertEquals(SoundPlan(speak = false, cue = Cue.HINT, buzz = null), plan(SoundEvent.SPEECH, noVoice))
        assertEquals(SoundPlan(speak = false, cue = Cue.COUNTED, buzz = null), plan(SoundEvent.REP, noVoice))
        assertEquals(SoundPlan(speak = false, cue = Cue.COUNTED, buzz = null), plan(SoundEvent.HOLD_TICK, noVoice))
        // Фраза «не засчитано» без голоса — сигнал «не засчитано».
        assertEquals(Cue.MISSED, plan(SoundEvent.SPEECH, noVoice, fallback = Cue.MISSED).cue)
    }

    @Test
    fun missingRussianVoiceActsLikeVoiceOff() {
        val p = SoundPrefs()
        assertEquals(SoundPlan(speak = false, cue = Cue.HINT, buzz = null), plan(SoundEvent.HINT, p, ready = false))
        assertEquals(SoundPlan(speak = false, cue = Cue.COUNTED, buzz = null), plan(SoundEvent.REP, p, ready = false))
    }

    @Test
    fun voiceOnSpeaksWithoutFallbackCue() {
        val p = SoundPrefs()
        assertEquals(SoundPlan(speak = true, cue = null, buzz = null), plan(SoundEvent.SPEECH, p, fallback = Cue.MISSED))
        // «Не засчитано» сигналом звучит и при голосе: подсказка («Ниже») голосом идёт следом.
        assertEquals(SoundPlan(speak = false, cue = Cue.MISSED, buzz = null), plan(SoundEvent.MISS, p, text = false))
        assertEquals(SoundPlan(speak = true, cue = Cue.MISSED, buzz = null), plan(SoundEvent.MISS, p, text = true))
    }

    @Test
    fun allOffIsSilent() {
        for (e in SoundEvent.entries) {
            for (text in listOf(true, false)) {
                assertTrue("$e text=$text", plan(e, off, text = text).silent)
                assertTrue("$e fallback", plan(e, off, text = text, fallback = Cue.MISSED).silent)
            }
        }
    }

    @Test
    fun tonesOffVoiceOnOnlySpeaks() {
        val p = SoundPrefs(voice = true, tones = false, vibration = false)
        assertEquals(SoundPlan(speak = true, cue = null, buzz = null), plan(SoundEvent.REP, p))
        assertTrue(plan(SoundEvent.READY, p, text = false).silent)
        assertTrue(plan(SoundEvent.MISS, p, text = false).silent)
    }

    @Test
    fun vibrationPatterns() {
        val p = SoundPrefs(voice = false, tones = false, vibration = true)
        assertEquals(SoundPlan(speak = false, cue = null, buzz = Buzz.SHORT), plan(SoundEvent.REP, p))
        assertEquals(SoundPlan(speak = false, cue = null, buzz = Buzz.DOUBLE), plan(SoundEvent.MISS, p))
        assertEquals(SoundPlan(speak = false, cue = null, buzz = Buzz.LONG), plan(SoundEvent.HINT, p))
        // С голосом и сигналами вибрация добавляется, а не заменяет.
        assertEquals(SoundPlan(speak = true, cue = Cue.COUNTED, buzz = Buzz.SHORT), plan(SoundEvent.REP, all))
        assertEquals(SoundPlan(speak = true, cue = null, buzz = Buzz.LONG), plan(SoundEvent.HINT, all))
        // Выключена — не вибрирует ни на что.
        for (e in SoundEvent.entries) assertNull("$e", plan(e, noVoice).buzz)
    }

    // ---------- Частота подсказок ----------

    @Test
    fun hintCueAtMostOnceInThreeSeconds() {
        val l = HintLimiter()
        assertTrue(l.allow("Ниже", 10_000, byVoice = false))
        assertFalse(l.allow("Встань в кадр", 12_999, byVoice = false))
        assertTrue(l.allow("Встань в кадр", 13_000, byVoice = false))
    }

    @Test
    fun sameHintNotMoreOftenThanEightSeconds() {
        val l = HintLimiter()
        assertTrue(l.allow("Ниже", 0, byVoice = false))
        assertFalse(l.allow("Ниже", 3_000, byVoice = false))
        assertFalse(l.allow("Ниже", 7_999, byVoice = false))
        assertTrue(l.allow("Ниже", 8_000, byVoice = false))
    }

    @Test
    fun voiceHintKeepsFourSecondInterval() {
        val l = HintLimiter()
        assertTrue(l.allow("Ниже", 0, byVoice = true))
        assertFalse(l.allow("До конца", 3_999, byVoice = true))
        assertTrue(l.allow("До конца", 4_000, byVoice = true))
    }

    @Test
    fun missDelaysNextHintCue() {
        val l = HintLimiter()
        l.onMiss(5_000)
        // «Ниже» сразу за «не засчитано» второй раз не пищит.
        assertFalse(l.allow("Ниже", 5_100, byVoice = false))
        assertTrue(l.allow("Ниже", 8_000, byVoice = false))
        // Голосом подсказка после «не засчитано» звучит: сигнал её не заменяет.
        val v = HintLimiter()
        v.onMiss(5_000)
        assertTrue(v.allow("Ниже", 5_100, byVoice = true))
    }

    // ---------- Признак «не засчитано» у счётчиков ----------

    @Test
    fun halfAmplitudeRepsAreMarkedMissed() {
        val failures = SPECS.mapNotNull { spec ->
            val tl = Timeline().hold(spec.rest, 1000).reps(spec.half, 5, spec.segmentMs).hold(spec.rest, 1000)
            val states = RepCounters.forExercise(spec.id)!!.feed(tl.frames)
            val misses = states.count { it.missed }
            when {
                states.last().count != 0 -> "${spec.id}: count=${states.last().count}"
                misses != 5 -> "${spec.id}: отметок «не засчитано» $misses, ожидалось 5"
                else -> null
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test
    fun fullRepsAreNeverMarkedMissed() {
        val failures = SPECS.mapNotNull { spec ->
            val tl = Timeline().hold(spec.rest, 1000).reps(spec.path, 5, spec.segmentMs).hold(spec.rest, 1000)
            val states = RepCounters.forExercise(spec.id)!!.feed(tl.frames)
            if (states.any { it.missed }) "${spec.id}: ложная отметка" else null
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test
    fun goingBackDownHalfwayIsOneMissThenRepCounts() {
        val top = Key(75f, 255f, 255f, 180f, 180f)
        val deep = Key(85f, 265f, 265f, 280f, 150f)
        val half = top.lerp(deep, 0.45f)
        val tl = Timeline().hold(top, 1000)
            .move(top, deep, 900).move(deep, half, 700).move(half, deep, 700).move(deep, top, 900)
            .hold(top, 1000)
        val states = RepCounters.forExercise("pushups")!!.feed(tl.frames)
        assertEquals(1, states.last().count)
        assertEquals(1, states.count { it.missed })
        // Отметка — на одном кадре, не залипает.
        assertFalse(states.last().missed)
    }
}
