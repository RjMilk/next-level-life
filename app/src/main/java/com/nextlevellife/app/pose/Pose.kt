package com.nextlevellife.app.pose

/**
 * Общий контракт между камерой, счётчиками повторений и экранами.
 * Не зависит от ML Kit и Android: счётчики — чистый Kotlin и проверяются тестами.
 */

enum class Joint {
    NOSE,
    LEFT_SHOULDER, RIGHT_SHOULDER,
    LEFT_ELBOW, RIGHT_ELBOW,
    LEFT_WRIST, RIGHT_WRIST,
    LEFT_HIP, RIGHT_HIP,
    LEFT_KNEE, RIGHT_KNEE,
    LEFT_ANKLE, RIGHT_ANKLE,
}

/**
 * Точка сустава в пикселях «прямого» кадра — так, как его видит пользователь на экране
 * (поворот учтён, для фронтальной камеры кадр отзеркален). Начало координат слева сверху, y вниз.
 * [confidence] — уверенность 0..1, что сустав действительно в кадре.
 */
data class PosePoint(val x: Float, val y: Float, val confidence: Float)

/** Один кадр. [points] пуст, если человек не найден. [timeMs] — SystemClock.elapsedRealtime(). */
class PoseFrame(
    val timeMs: Long,
    val width: Int,
    val height: Int,
    val points: Map<Joint, PosePoint>,
)

/**
 * Что счётчик знает после очередного кадра.
 * [tracking] — нужные суставы видны; [inPosition] — человек в исходном положении упражнения;
 * [hint] — короткая подсказка по-русски для экрана («Встань в кадр целиком», «Ниже»);
 * [depth] — 0..1, насколько глубоко выполнено текущее повторение (для индикатора амплитуды);
 * [atStart] — человек в позе упражнения И в исходной точке движения (а не посреди повторения):
 * по нему панель подтверждает готовность перед счётом.
 */
data class RepState(
    val count: Int = 0,
    val tracking: Boolean = false,
    val inPosition: Boolean = false,
    val hint: String = "",
    val depth: Float = 0f,
    val atStart: Boolean = false,
)

interface RepCounter {
    val state: RepState

    /** Обработать кадр и вернуть новое состояние. Вызывается из главного потока. */
    fun onFrame(frame: PoseFrame): RepState

    /** Сбросить счёт и фазу (новый подход). */
    fun reset()
}
