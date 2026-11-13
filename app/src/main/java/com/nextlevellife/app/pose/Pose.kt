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
 *
 * Поля удержания (только у счётчиков вида [CountKind.HOLD], у остальных — нули; [count] у них 0):
 * [heldMs] — ЗАСЧИТАННОЕ время: сумма отрезков не короче минимального (у планки 10 с) плюс
 * текущий отрезок, если он уже дотянул до минимума. Именно оно идёт в итог подхода.
 * [currentSegmentMs] — оплаченное время текущего отрезка (0 — отрезка нет); пока оно меньше
 * минимума, его нет в [heldMs] — это [pendingMs]. Если отрезок оборвётся раньше минимума,
 * [pendingMs] пропадёт, а [heldMs] не изменится.
 * [holding] — время идёт прямо сейчас (последний кадр хороший и отрезок открыт). Исключение — потолок
 * отрезка (у планки 3 минуты): поза держится и [holding] остаётся true, но время больше не растёт.
 * На экране показывать [shownHeldMs] = [heldMs] + [pendingMs].
 */
data class RepState(
    val count: Int = 0,
    val tracking: Boolean = false,
    val inPosition: Boolean = false,
    val hint: String = "",
    val depth: Float = 0f,
    val atStart: Boolean = false,
    val heldMs: Long = 0,
    val currentSegmentMs: Long = 0,
    val pendingMs: Long = 0,
    val holding: Boolean = false,
) {
    /** Что показывать на экране: засчитанное плюс ещё не подтверждённый текущий отрезок. */
    val shownHeldMs: Long get() = heldMs + pendingMs
}

/** Вид счёта: повторения ([RepState.count]) или удержание позы ([RepState.heldMs]). */
enum class CountKind { REPS, HOLD }

interface RepCounter {
    val state: RepState

    /** Что считает счётчик; интерфейс выбирает по нему табло (повторения или время). */
    val kind: CountKind get() = CountKind.REPS

    /** Обработать кадр и вернуть новое состояние. Вызывается из главного потока. */
    fun onFrame(frame: PoseFrame): RepState

    /** Сбросить счёт и фазу (новый подход). */
    fun reset()
}
