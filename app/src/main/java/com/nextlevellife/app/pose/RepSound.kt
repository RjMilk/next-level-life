package com.nextlevellife.app.pose

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

private const val TAG = "RepSound"

/** Почти максимум: телефон в 1,5–2 м, человек тяжело дышит. */
private const val TONE_VOLUME = 90

/**
 * Звук и голос для счёта без взгляда на экран.
 * Сигналы — ToneGenerator (работают всегда), голос — TextToSpeech с русской локалью.
 * Без русского голоса работает только сигналами. Вызывать из главного потока.
 */
class RepSound internal constructor(context: Context) {
    private enum class Voice { PENDING, READY, FAILED }

    private val handler = Handler(Looper.getMainLooper())
    private val tones: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
    } catch (e: RuntimeException) {
        // Бывает на некоторых прошивках при нехватке аудиоресурсов: без сигналов, но без падения.
        Log.w(TAG, "ToneGenerator недоступен", e)
        null
    }
    private var voice = Voice.PENDING
    private var released = false
    private var tts: TextToSpeech? = null

    private var lastHintAt = Long.MIN_VALUE / 2
    private val lastHintByText = HashMap<String, Long>()

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

    /** Засчитано повторение номер n: короткий щелчок и голосом число. */
    fun rep(n: Int) {
        if (released) return
        tone(ToneGenerator.TONE_DTMF_9, 60)
        // QUEUE_FLUSH: если прошлое число ещё звучит, новое важнее.
        speak(n.toString(), TextToSpeech.QUEUE_FLUSH)
    }

    /** Исходная поза подтверждена, можно начинать: отчётливый двойной сигнал. */
    fun ready() {
        if (released) return
        handler.removeCallbacksAndMessages(null)
        tone(ToneGenerator.TONE_DTMF_D, 120)
        later(220) { tone(ToneGenerator.TONE_DTMF_D, 120) }
    }

    /** Подход/шаг завершён: заметный сигнал, отличающийся от rep и ready. */
    fun done() {
        if (released) return
        handler.removeCallbacksAndMessages(null)
        // Три восходящих ноты и долгая последняя — не спутать с одиночным щелчком и двойным «готов».
        tone(ToneGenerator.TONE_DTMF_1, 140)
        later(170) { tone(ToneGenerator.TONE_DTMF_5, 140) }
        later(340) { tone(ToneGenerator.TONE_DTMF_D, 450) }
    }

    /** Удержание (планка) началось: двойной сигнал «готов» и голосом «Старт». */
    fun holdStart() {
        if (released) return
        ready()
        speak(HoldSpeech.START, TextToSpeech.QUEUE_FLUSH)
    }

    /** Удержание идёт уже [seconds] секунд (кратно 10): щелчок и голосом «десять», «двадцать»… */
    fun holdSeconds(seconds: Int) {
        if (released) return
        tone(ToneGenerator.TONE_DTMF_9, 60)
        speak(HoldSpeech.seconds(seconds), TextToSpeech.QUEUE_FLUSH)
    }

    /** Удержание оборвалось: низкий сигнал и «Стоп». */
    fun holdStop() {
        if (released) return
        handler.removeCallbacksAndMessages(null)
        tone(ToneGenerator.TONE_PROP_NACK, 200)
        speak(HoldSpeech.STOP, TextToSpeech.QUEUE_FLUSH)
    }

    /** Отрезок упёрся в потолок: «Максимум за подход — 3 минуты» (в очередь за числом). */
    fun holdMax() {
        if (released) return
        speak(HoldSpeech.MAX, TextToSpeech.QUEUE_ADD)
    }

    /** Произнести фразу. interrupt = true прерывает текущую речь. */
    fun say(text: String, interrupt: Boolean = true) {
        if (released || text.isBlank()) return
        speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD)
    }

    /** Подсказка при потере человека/неполной амплитуде: не чаще раза в 4 секунды, одинаковый текст — не чаще раза в 8 секунд. */
    fun hint(text: String) {
        if (released || text.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHintAt < 4_000) return
        val sameAt = lastHintByText[text]
        if (sameAt != null && now - sameAt < 8_000) return
        lastHintAt = now
        lastHintByText[text] = now
        when (voice) {
            // Не перебиваем счёт: подсказка встаёт в очередь за числом.
            Voice.READY -> speak(text, TextToSpeech.QUEUE_ADD)
            // Без голоса хотя бы низкий «неверно», чтобы человек посмотрел на экран.
            Voice.FAILED -> tone(ToneGenerator.TONE_PROP_NACK, 300)
            // Голос ещё готовится: подсказку отбрасываем.
            Voice.PENDING -> Unit
        }
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
    }

    private fun speak(text: String, queueMode: Int) {
        if (voice != Voice.READY) return
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

/** Звук живёт, пока экран на месте; освобождается вместе с ним. */
@Composable
fun rememberRepSound(): RepSound {
    val context = LocalContext.current.applicationContext
    val sound = remember(context) { RepSound(context) }
    DisposableEffect(sound) {
        onDispose { sound.release() }
    }
    return sound
}
