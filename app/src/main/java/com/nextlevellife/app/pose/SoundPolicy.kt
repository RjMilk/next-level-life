package com.nextlevellife.app.pose

// ---------------------------------------------------------------------------------------------
// Звук на тренировке: что проиграть на событие при данных настройках. Чистая логика (покрыта тестами),
// воспроизведение — в RepSound.
// ---------------------------------------------------------------------------------------------

/**
 * Настройки «Звук на тренировке»: [voice] — счёт и подсказки голосом, [tones] — сигналы,
 * [vibration] — вибрация. По умолчанию как до появления настроек: голос и сигналы, без вибрации.
 */
data class SoundPrefs(
    val voice: Boolean = true,
    val tones: Boolean = true,
    val vibration: Boolean = false,
)

/** Сигнал (набор нот ToneGenerator). */
enum class Cue {
    /** Засчитано: короткий высокий. */
    COUNTED,

    /** Не засчитано / сброс: низкий двойной. */
    MISSED,

    /** Подсказка: средний короткий. */
    HINT,

    /** Исходная поза подтверждена, можно начинать: двойной высокий (как раньше). */
    READY,

    /** Подход/шаг завершён: три восходящие ноты (как раньше). */
    DONE,

    /** Удержание оборвалось: низкая нота (как раньше). */
    STOP,

    /** «Удар» нарезки «Сайтамы». */
    IMPACT,

    /** Финал нарезки «Сайтамы». */
    FINALE,
}

/** Рисунок вибрации. */
enum class Buzz {
    /** Засчитано: один короткий импульс. */
    SHORT,

    /** Не засчитано: два импульса. */
    DOUBLE,

    /** Подсказка: один длинный. */
    LONG,
}

/** Событие тренировки, на которое отвечает звук. */
enum class SoundEvent(
    /** Сигнал, если сигналы включены. */
    val cue: Cue?,
    /** Сигнал звучит, только когда голос это событие не произнёс (замена речи). */
    val cueOnlyWithoutSpeech: Boolean,
    /** Вибрация, если она включена. */
    val buzz: Buzz?,
) {
    /** Засчитан повтор: сигнал всегда (как раньше), голосом — число. */
    REP(Cue.COUNTED, cueOnlyWithoutSpeech = false, buzz = Buzz.SHORT),

    /** Попытка не засчитана (не дошёл, вернулся слишком быстро, удержание короче минимума). */
    MISS(Cue.MISSED, cueOnlyWithoutSpeech = false, buzz = Buzz.DOUBLE),

    /** Подсказка («Ниже», «Встань в кадр»): голосом, а без голоса — сигналом. */
    HINT(Cue.HINT, cueOnlyWithoutSpeech = true, buzz = Buzz.LONG),

    /** Исходная поза подтверждена. */
    READY(Cue.READY, cueOnlyWithoutSpeech = false, buzz = null),

    /** Подход/шаг завершён. */
    DONE(Cue.DONE, cueOnlyWithoutSpeech = false, buzz = null),

    /** Удержание началось: «готов» и голосом «Старт». */
    HOLD_START(Cue.READY, cueOnlyWithoutSpeech = false, buzz = null),

    /** Удержание идёт ещё 10 секунд: щелчок «засчитано» и голосом число. */
    HOLD_TICK(Cue.COUNTED, cueOnlyWithoutSpeech = false, buzz = Buzz.SHORT),

    /** Удержание оборвалось: низкая нота и голосом «Стоп». */
    HOLD_STOP(Cue.STOP, cueOnlyWithoutSpeech = false, buzz = null),

    /** Фраза без своего сигнала («Время!», шаг комплекса, обратный отсчёт): без голоса — сигнал подсказки. */
    SPEECH(Cue.HINT, cueOnlyWithoutSpeech = true, buzz = null),

    /** «Удар» нарезки: только свой сигнал, фраза отдельно. */
    IMPACT(Cue.IMPACT, cueOnlyWithoutSpeech = false, buzz = null),

    /** Финал нарезки. */
    FINALE(Cue.FINALE, cueOnlyWithoutSpeech = false, buzz = null),
}

/** Что проиграть: [speak] — произнести фразу события, [cue] — сигнал, [buzz] — вибрация. */
data class SoundPlan(val speak: Boolean, val cue: Cue?, val buzz: Buzz?) {
    val silent: Boolean get() = !speak && cue == null && buzz == null

    companion object {
        val SILENT = SoundPlan(speak = false, cue = null, buzz = null)
    }
}

object SoundPolicy {
    /** Подсказка сигналом или вибрацией — не чаще раза в 3 секунды. */
    const val HINT_CUE_INTERVAL_MS = 3_000L

    /** Подсказка голосом — не чаще раза в 4 секунды… */
    const val HINT_VOICE_INTERVAL_MS = 4_000L

    /** …одинаковая подсказка (голосом или сигналом) — не чаще раза в 8 секунд. */
    const val HINT_SAME_TEXT_MS = 8_000L

    /**
     * План на событие. [hasText] — у события есть фраза; [voiceReady] — голос готов (движок TextToSpeech
     * с русской речью). Голос выключен или недоступен — фраза заменяется сигналом, если сигналы включены;
     * всё выключено — тишина. [fallback] заменяет сигнал события, когда фраза не произнесена
     * (например, «не засчитано» вместо фразы «Меньше 10 секунд…»).
     */
    fun plan(
        event: SoundEvent,
        prefs: SoundPrefs,
        hasText: Boolean,
        voiceReady: Boolean,
        fallback: Cue? = null,
    ): SoundPlan {
        val speak = hasText && prefs.voice && voiceReady
        val eventCue = if (!speak && fallback != null) fallback else event.cue
        val cue = when {
            !prefs.tones || eventCue == null -> null
            event.cueOnlyWithoutSpeech && speak -> null
            else -> eventCue
        }
        val buzz = if (prefs.vibration) event.buzz else null
        return SoundPlan(speak, cue, buzz)
    }
}

/**
 * Ограничение частоты подсказок: голосом — не чаще раза в [SoundPolicy.HINT_VOICE_INTERVAL_MS],
 * сигналом/вибрацией — раза в [SoundPolicy.HINT_CUE_INTERVAL_MS], одинаковый текст — раза в
 * [SoundPolicy.HINT_SAME_TEXT_MS]. «Не засчитано» ([onMiss]) тоже считается сигналом: подсказка
 * («Ниже») сразу за ним не пищит второй раз. Время — монотонные миллисекунды.
 */
class HintLimiter {
    private var lastVoiceAt = Long.MIN_VALUE / 2
    private var lastCueAt = Long.MIN_VALUE / 2
    private val lastByText = HashMap<String, Long>()

    /** Можно ли подать подсказку [text] сейчас; да — отмечает её. [byVoice] — она будет произнесена. */
    fun allow(text: String, nowMs: Long, byVoice: Boolean): Boolean {
        val interval = if (byVoice) SoundPolicy.HINT_VOICE_INTERVAL_MS else SoundPolicy.HINT_CUE_INTERVAL_MS
        val last = if (byVoice) lastVoiceAt else lastCueAt
        if (nowMs - last < interval) return false
        val sameAt = lastByText[text]
        if (sameAt != null && nowMs - sameAt < SoundPolicy.HINT_SAME_TEXT_MS) return false
        if (byVoice) lastVoiceAt = nowMs else lastCueAt = nowMs
        lastByText[text] = nowMs
        return true
    }

    /** Прозвучал сигнал «не засчитано»: ближайшая подсказка сигналом подождёт. */
    fun onMiss(nowMs: Long) {
        lastCueAt = nowMs
    }
}
