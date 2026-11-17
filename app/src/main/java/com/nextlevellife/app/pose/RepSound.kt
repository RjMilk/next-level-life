package com.nextlevellife.app.pose

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

private const val TAG = "RepSound"

/** Почти максимум: телефон в 1,5–2 м, человек тяжело дышит. */
private const val TONE_VOLUME = 90

/**
 * Звук, голос и вибрация для счёта без взгляда на экран.
 * Сигналы — ToneGenerator (работают всегда), голос — TextToSpeech с русской локалью, вибрация — Vibrator.
 * Что звучит на событие, решает [SoundPolicy] по настройкам «Звук на тренировке» ([prefs] читается
 * на каждом событии — переключатель действует сразу). Без голоса (выключен или нет русского) фразы
 * заменяются сигналами; всё выключено — тишина. Вызывать из главного потока.
 */
class RepSound internal constructor(
    context: Context,
    private val prefs: () -> SoundPrefs = { SoundPrefs() },
) {
    private enum class Voice { PENDING, READY, FAILED }

    private val handler = Handler(Looper.getMainLooper())
    private val tones: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
    } catch (e: RuntimeException) {
        // Бывает на некоторых прошивках при нехватке аудиоресурсов: без сигналов, но без падения.
        Log.w(TAG, "ToneGenerator недоступен", e)
        null
    }
    private val vibrator: Vibrator? = runCatching {
        val app = context.applicationContext
        val v = if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }
        v?.takeIf { it.hasVibrator() }
    }.getOrNull()
    private var voice = Voice.PENDING
    private var released = false
    private var tts: TextToSpeech? = null

    private val hints = HintLimiter()

    // Ошибку привязки к движку TTS Android может сообщить прямо из конструктора, когда tts ещё не присвоен.
    private var earlyStatus: Int? = null

    init {
        tts = try {
            TextToSpeech(context.applicationContext) { status ->
                if (tts == null) earlyStatus = status else onTtsInit(status)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TextToSpeech не создан", e)
            voice = Voice.FAILED
            null
        }
        earlyStatus?.let { onTtsInit(it) }
    }

    private fun onTtsInit(status: Int) {
        val engine = tts
        if (released) return
        if (engine == null) {
            voice = Voice.FAILED
            return
        }
        if (status != TextToSpeech.SUCCESS) {
            voice = Voice.FAILED
            return
        }
        voice = try {
            val result = engine.setLanguage(Locale.forLanguageTag("ru-RU"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.i(TAG, "Русского голоса нет — только сигналы")
                Voice.FAILED
            } else {
                // Чуть быстрее обычного: счёт не должен отставать от движения.
                engine.setSpeechRate(1.2f)
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                Voice.READY
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось настроить голос", e)
            Voice.FAILED
        }
    }

    /** Засчитано повторение номер n: короткий высокий сигнал и голосом число. */
    fun rep(n: Int) {
        // QUEUE_FLUSH: если прошлое число ещё звучит, новое важнее.
        play(SoundEvent.REP, n.toString(), TextToSpeech.QUEUE_FLUSH)
    }

    /**
     * Попытка не засчитана (развернулся, не дойдя до крайней точки; вернулся слишком быстро; удержание
     * короче минимума): низкий двойной сигнал, два импульса вибрации и, если есть, фраза [text] в очередь.
     * Подсказка сигналом сразу за ним не звучит — ей выдерживается пауза [SoundPolicy.HINT_CUE_INTERVAL_MS].
     */
    fun miss(text: String? = null) {
        if (released) return
        hints.onMiss(SystemClock.elapsedRealtime())
        play(SoundEvent.MISS, text, TextToSpeech.QUEUE_ADD)
    }

    /** Исходная поза подтверждена, можно начинать: отчётливый двойной сигнал. */
    fun ready() {
        play(SoundEvent.READY, null, TextToSpeech.QUEUE_FLUSH, cancelPending = true)
    }

    /** Подход/шаг завершён: заметный сигнал, отличающийся от rep и ready. */
    fun done() {
        play(SoundEvent.DONE, null, TextToSpeech.QUEUE_FLUSH, cancelPending = true)
    }

    /** Удержание (планка) началось: двойной сигнал «готов» и голосом «Старт». */
    fun holdStart() {
        play(SoundEvent.HOLD_START, HoldSpeech.START, TextToSpeech.QUEUE_FLUSH, cancelPending = true)
    }

    /** Удержание идёт уже [seconds] секунд (кратно 10): щелчок и голосом «десять», «двадцать»… */
    fun holdSeconds(seconds: Int) {
        play(SoundEvent.HOLD_TICK, HoldSpeech.seconds(seconds), TextToSpeech.QUEUE_FLUSH)
    }

    /** Удержание оборвалось: низкий сигнал и «Стоп». */
    fun holdStop() {
        play(SoundEvent.HOLD_STOP, HoldSpeech.STOP, TextToSpeech.QUEUE_FLUSH, cancelPending = true)
    }

    /** Отрезок упёрся в потолок: «Максимум за подход — 3 минуты» (в очередь за числом); без голоса — сигнал подсказки. */
    fun holdMax() {
        play(SoundEvent.SPEECH, HoldSpeech.MAX, TextToSpeech.QUEUE_ADD)
    }

    /** Голос ещё готовится (движок TextToSpeech не ответил): «нарезка» может подождать его долю секунды. */
    val voicePending: Boolean get() = voice == Voice.PENDING && prefs().voice

    /**
     * «Удар» для «нарезки» «Сайтамы»: короткий щелчок и плотная низкая нота сразу за ним —
     * не спутать с сигналами подхода (rep, ready, done).
     */
    fun impact() {
        play(SoundEvent.IMPACT, null, TextToSpeech.QUEUE_FLUSH, cancelPending = true)
    }

    /** Финал «нарезки»: два «удара» и долгая высокая нота. */
    fun finale() {
        play(SoundEvent.FINALE, null, TextToSpeech.QUEUE_FLUSH, cancelPending = true)
    }

    /** Замолчать: отменить отложенные сигналы и оборвать речь (уход с экрана, пропуск «нарезки»). */
    fun stop() {
        if (released) return
        handler.removeCallbacksAndMessages(null)
        runCatching { tones?.stopTone() }
        runCatching { tts?.stop() }
        runCatching { vibrator?.cancel() }
    }

    /**
     * Произнести фразу. interrupt = true прерывает текущую речь. Без голоса фраза заменяется сигналом
     * [fallback] (по умолчанию — «подсказка»); null — без замены (у события уже есть свой сигнал).
     */
    fun say(text: String, interrupt: Boolean = true, fallback: Cue? = Cue.HINT) {
        if (released || text.isBlank()) return
        val p = prefs()
        val plan = SoundPolicy.plan(SoundEvent.SPEECH, p, hasText = true, voiceReady = voice == Voice.READY)
        if (plan.speak) {
            speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD)
        } else if (p.tones && fallback != null) {
            cue(fallback)
        }
    }

    /**
     * Подсказка при потере человека/неполной амплитуде. Голосом — не чаще раза в 4 секунды, сигналом и
     * вибрацией — раза в 3 секунды, одинаковый текст — не чаще раза в 8 секунд ([HintLimiter]).
     */
    fun hint(text: String) {
        if (released || text.isBlank()) return
        val p = prefs()
        // Голос включён, но ещё готовится: подсказку отбрасываем (как раньше), сигналом её не заменяем.
        if (p.voice && voice == Voice.PENDING) return
        val plan = SoundPolicy.plan(SoundEvent.HINT, p, hasText = true, voiceReady = voice == Voice.READY)
        if (plan.silent) return
        if (!hints.allow(text, SystemClock.elapsedRealtime(), byVoice = plan.speak)) return
        // Не перебиваем счёт: подсказка встаёт в очередь за числом.
        perform(plan, text, TextToSpeech.QUEUE_ADD)
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        tts?.let {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        tts = null
        tones?.let { runCatching { it.release() } }
        runCatching { vibrator?.cancel() }
    }

    private fun play(event: SoundEvent, text: String?, queueMode: Int, cancelPending: Boolean = false) {
        if (released) return
        if (cancelPending) handler.removeCallbacksAndMessages(null)
        val plan = SoundPolicy.plan(event, prefs(), hasText = !text.isNullOrBlank(), voiceReady = voice == Voice.READY)
        perform(plan, text, queueMode)
    }

    private fun perform(plan: SoundPlan, text: String?, queueMode: Int) {
        plan.cue?.let(::cue)
        plan.buzz?.let(::buzz)
        if (plan.speak && text != null) speak(text, queueMode)
    }

    /** Ноты сигнала: засчитано, не засчитано и подсказка различаются высотой, длиной и числом нот. */
    private fun cue(c: Cue) {
        when (c) {
            // Короткий высокий щелчок (как был у повторения).
            Cue.COUNTED -> tone(ToneGenerator.TONE_DTMF_9, 60)
            // Низкий двойной: аккорд 300–500 Гц дважды.
            Cue.MISSED -> {
                tone(ToneGenerator.TONE_PROP_NACK, 140)
                later(230) { tone(ToneGenerator.TONE_PROP_NACK, 140) }
            }
            // Средний короткий: 400+1200 Гц — ниже щелчка, звонче «не засчитано», одна нота.
            Cue.HINT -> tone(ToneGenerator.TONE_PROP_PROMPT, 160)
            Cue.READY -> {
                tone(ToneGenerator.TONE_DTMF_D, 120)
                later(220) { tone(ToneGenerator.TONE_DTMF_D, 120) }
            }
            Cue.DONE -> {
                // Три восходящих ноты и долгая последняя — не спутать с одиночным щелчком и двойным «готов».
                tone(ToneGenerator.TONE_DTMF_1, 140)
                later(170) { tone(ToneGenerator.TONE_DTMF_5, 140) }
                later(340) { tone(ToneGenerator.TONE_DTMF_D, 450) }
            }
            Cue.STOP -> tone(ToneGenerator.TONE_PROP_NACK, 200)
            Cue.IMPACT -> {
                tone(ToneGenerator.TONE_DTMF_9, 40)
                later(50) { tone(ToneGenerator.TONE_DTMF_1, 140) }
            }
            Cue.FINALE -> {
                tone(ToneGenerator.TONE_DTMF_1, 90)
                later(120) { tone(ToneGenerator.TONE_DTMF_5, 90) }
                later(240) { tone(ToneGenerator.TONE_DTMF_9, 90) }
                later(360) { tone(ToneGenerator.TONE_DTMF_D, 600) }
            }
        }
    }

    private fun buzz(b: Buzz) {
        val v = vibrator ?: return
        val effect = when (b) {
            Buzz.SHORT -> VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
            Buzz.DOUBLE -> VibrationEffect.createWaveform(longArrayOf(0, 90, 140, 90), -1)
            Buzz.LONG -> VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        try {
            // Как медиа: сигналы и голос тоже идут потоком музыки; касания тут ни при чём.
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Вибрация недоступна", e)
        }
    }

    private fun speak(text: String, queueMode: Int) {
        if (voice != Voice.READY || !prefs().voice) return
        val engine = tts ?: return
        try {
            engine.speak(text, queueMode, null, "nll-${SystemClock.elapsedRealtime()}")
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось произнести", e)
        }
    }

    private fun tone(type: Int, durationMs: Int) {
        if (released) return
        try {
            tones?.startTone(type, durationMs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Сигнал не проигран", e)
        }
    }

    private fun later(delayMs: Long, block: () -> Unit) {
        handler.postDelayed({ if (!released) block() }, delayMs)
    }
}

/** Фразы удержания (планки) для голоса. Чистая логика — проверяется тестами. */
object HoldSpeech {
    const val START = "Старт"
    const val STOP = "Стоп"
    const val MAX = "Максимум за подход — 3 минуты"
    const val SHORT = "Меньше 10 секунд — не засчитано"

    private val tens = listOf(
        "", "десять", "двадцать", "тридцать", "сорок", "пятьдесят",
        "шестьдесят", "семьдесят", "восемьдесят", "девяносто",
    )
    private val hundreds = listOf(
        "", "сто", "двести", "триста", "четыреста", "пятьсот",
        "шестьсот", "семьсот", "восемьсот", "девятьсот",
    )

    /**
     * Число секунд словами для голоса: 10 → «десять», 120 → «сто двадцать», 180 → «сто восемьдесят».
     * Словами — только круглые десятки от 10 до 990; остальное — цифрами (движок речи прочтёт сам).
     */
    fun seconds(n: Int): String {
        if (n <= 0 || n >= 1000 || n % 10 != 0) return n.toString()
        return listOf(hundreds[n / 100], tens[(n % 100) / 10]).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
