package com.nextlevellife.app.data

import kotlin.math.roundToLong

/**
 * Упражнение и его «цена»: сколько секунд экранного времени даёт одно повторение.
 * Для гиревых упражнений цена указана для гири 16 кг и умножается на [Weights.factor].
 * Одно повторение стоит одинаково и отдельно, и внутри Минотавра.
 *
 * [minSecondsPerRep] защищает от «натыкивания»: засчитывается не больше повторений,
 * чем физически можно сделать за прошедшее время.
 *
 * [hold] — упражнение-удержание (планка): считается не повторениями, а временем. У него
 * [rewardSecondsPerRep] — секунды экранного времени за секунду засчитанного удержания
 * (см. [Exercises.holdRewardMs]), [minSecondsPerRep] не используется.
 *
 * [counterId] — профиль счёта камерой ([com.nextlevellife.app.pose.RepCounters.forExercise]); обычно совпадает с id.
 * Упражнения зала берут уже проверенные профили (присед со штангой — профиль приседаний) без копирования порогов.
 *
 * [gym] — упражнение зала ([GymSpec]: снаряд, способ учёта веса, шаг роста). Цена у него фиксированная за
 * повторение: заявленный вес камера не проверяет и на минуты он не влияет.
 */
data class Exercise(
    val id: String,
    val name: String,
    val rewardSecondsPerRep: Int,
    val minSecondsPerRep: Double,
    val kettlebell: Boolean = false,
    val hold: Boolean = false,
    val counterId: String = id,
    val gym: GymSpec? = null,
) {
    /** Упражнение зала: вес подхода заявляется перед стартом и пишется в подход (load_g). */
    val isGym: Boolean get() = gym != null
}

object Weights {
    val all = listOf(16, 24, 32)
    const val DEFAULT = 16

    /** Мягкий рост: тяжёлая гиря — это развитие, а не способ быстро набить минуты. */
    fun factor(weightKg: Int): Double = when (weightKg) {
        32 -> 1.6
        24 -> 1.2
        else -> 1.0
    }
}

object Exercises {
    val pushups = Exercise("pushups", "Отжимания", 30, 0.8)
    val squats = Exercise("squats", "Приседания", 40, 0.8)
    val abs = Exercise("abs", "Пресс", 35, 0.7)
    val pullups = Exercise("pullups", "Подтягивания", 60, 1.2)

    /** Планка на предплечьях: 5 секунд за секунду удержания — минута = 5 минут = 10 отжиманий. */
    val plank = Exercise("plank", "Планка", 5, 0.0, hold = true)

    val thruster = Exercise("thruster", "Трастер", 45, 1.5, kettlebell = true)
    val swing = Exercise("swings", "Мах гирей", 30, 0.8, kettlebell = true)
    val sumoPull = Exercise("sumo_pull", "Тяга сумо к подбородку", 30, 1.0, kettlebell = true)
    val row = Exercise("row", "Тяга к поясу в наклоне", 25, 1.0, kettlebell = true)
    val deadlift = Exercise("deadlift", "Становая тяга", 25, 1.0, kettlebell = true)

    // Из каталога docs/exercise-catalog.md (раздел 1): цена для 16 кг и минимум секунд на повтор.
    val gobletSquat = Exercise("goblet_squat", "Присед с гирей у груди", 35, 2.5, kettlebell = true)

    /** Считается любая рука; обе руки разом — один повтор. */
    val press = Exercise("press", "Жим гири одной рукой", 30, 2.5, kettlebell = true)

    /** Каждый шаг назад (любой ногой) — один повтор. */
    val reverseLunge = Exercise("reverse_lunge", "Выпад назад с гирей", 35, 3.0, kettlebell = true)
    val curl = Exercise("curl", "Сгибание рук с гирей", 20, 3.0, kettlebell = true)
    val gluteBridge = Exercise("glute_bridge", "Ягодичный мост с гирей", 20, 3.0, kettlebell = true)

    // Зал: телефон на стойке или штативе, повторения считает камера по проверенным профилям ([Exercise.counterId]).
    // Цена фиксированная за повторение (без множителя веса), минимум секунд на повтор — как у гиревых собратьев.
    val barbellSquat = Exercise(
        "bb_squat", "Присед со штангой", 40, 2.5, counterId = "squats",
        gym = GymSpec(Equipment.BARBELL, LoadMode.BARBELL_TOTAL, stepUpG = 5_000),
    )
    val barbellDeadlift = Exercise(
        "bb_deadlift", "Становая тяга со штангой", 35, 1.0, counterId = "deadlift",
        gym = GymSpec(Equipment.BARBELL, LoadMode.BARBELL_TOTAL, stepUpG = 5_000),
    )

    /** Штанга — обе руки вместе: профиль жима без раздельного счёта рук ([com.nextlevellife.app.pose.GymCounters]). */
    val barbellPress = Exercise(
        "bb_press", "Жим штанги стоя", 30, 2.5, counterId = "press_barbell",
        gym = GymSpec(Equipment.BARBELL, LoadMode.BARBELL_TOTAL, stepUpG = 2_500),
    )
    val barbellRow = Exercise(
        "bb_row", "Тяга штанги в наклоне", 30, 1.0, counterId = "row_barbell",
        gym = GymSpec(Equipment.BARBELL, LoadMode.BARBELL_TOTAL, stepUpG = 2_500),
    )

    val barbellFrontSquat = Exercise("bb_front_squat", "Фронтальный присед", 45, 2.0, counterId = "squats",
        gym = GymSpec(Equipment.BARBELL, LoadMode.BARBELL_TOTAL, 5_000))
    val barbellRomanianDeadlift = Exercise("bb_romanian_deadlift", "Румынская тяга", 35, 2.5,
        gym = GymSpec(Equipment.BARBELL, LoadMode.BARBELL_TOTAL, 5_000))
    val barbellBenchPress = Exercise("bb_bench_press", "Жим штанги лёжа", 30, 2.0,
        gym = GymSpec(Equipment.BARBELL, LoadMode.BARBELL_TOTAL, 2_500))
    val barbellInclinePress = Exercise("bb_incline_press", "Жим штанги на наклонной", 30, 2.0,
        gym = GymSpec(Equipment.BARBELL, LoadMode.BARBELL_TOTAL, 2_500))

    val dumbbellBenchPress = Exercise("db_bench_press", "Жим гантелей лёжа", 30, 2.0,
        gym = GymSpec(Equipment.DUMBBELL, LoadMode.DUMBBELL_EACH, 1_000))
    val dumbbellInclinePress = Exercise("db_incline_press", "Жим гантелей на наклонной", 30, 2.0,
        gym = GymSpec(Equipment.DUMBBELL, LoadMode.DUMBBELL_EACH, 1_000))
    val dumbbellSeatedPress = Exercise("db_seated_press", "Жим гантелей сидя", 30, 2.0,
        gym = GymSpec(Equipment.DUMBBELL, LoadMode.DUMBBELL_EACH, 1_000))
    // Одна рабочая рука за подход; обе ноги на полу, опорная рука на скамье.
    val dumbbellRow = Exercise("db_row", "Тяга гантели одной рукой", 25, 2.0, counterId = "row",
        gym = GymSpec(Equipment.DUMBBELL, LoadMode.DUMBBELL_EACH, 1_000))
    val dumbbellCurl = Exercise("db_curl", "Подъём гантели на бицепс", 15, 2.0, counterId = "curl",
        gym = GymSpec(Equipment.DUMBBELL, LoadMode.DUMBBELL_EACH, 500))
    val dumbbellLateralRaise = Exercise("db_lateral_raise", "Разведения гантелей в стороны", 10, 2.0,
        gym = GymSpec(Equipment.DUMBBELL, LoadMode.DUMBBELL_EACH, 500))
    val dumbbellLunge = Exercise("db_lunge", "Выпады с гантелями", 30, 2.5,
        gym = GymSpec(Equipment.DUMBBELL, LoadMode.DUMBBELL_EACH, 1_000))
    val dumbbellBulgarianSplitSquat = Exercise("db_bulgarian_split_squat", "Болгарский сплит-присед", 35, 2.5,
        gym = GymSpec(Equipment.DUMBBELL, LoadMode.DUMBBELL_EACH, 1_000))
    val dumbbellGobletSquat = Exercise("db_goblet_squat", "Гоблет-присед с гантелью", 40, 2.0, counterId = "goblet_squat",
        gym = GymSpec(Equipment.DUMBBELL, LoadMode.DUMBBELL_EACH, 1_000))

    val machineLatPulldown = Exercise("machine_lat_pulldown", "Тяга верхнего блока", 25, 2.0,
        gym = GymSpec(Equipment.MACHINE, LoadMode.STACK, 2_500))
    val machineSeatedRow = Exercise("machine_seated_row", "Тяга горизонтального блока", 25, 2.0,
        gym = GymSpec(Equipment.MACHINE, LoadMode.STACK, 2_500))
    val machineChestPress = Exercise("machine_chest_press", "Жим от груди в тренажёре", 25, 2.0,
        gym = GymSpec(Equipment.MACHINE, LoadMode.STACK, 2_500))
    val machineLegExtension = Exercise("machine_leg_extension", "Разгибание ног", 15, 2.0,
        gym = GymSpec(Equipment.MACHINE, LoadMode.STACK, 2_500))
    val machineLegCurl = Exercise("machine_leg_curl", "Сгибание ног сидя", 15, 2.0,
        gym = GymSpec(Equipment.MACHINE, LoadMode.STACK, 2_500))
    val machineLyingLegCurl = Exercise("machine_lying_leg_curl", "Сгибание ног лёжа", 15, 2.0,
        gym = GymSpec(Equipment.MACHINE, LoadMode.STACK, 2_500))
    val assistedPullup = Exercise("assisted_pullup", "Подтягивания в гравитроне", 35, 2.5, counterId = "pullups",
        gym = GymSpec(Equipment.ASSISTED, LoadMode.COUNTERWEIGHT, 2_500))
    // Здесь заявляется дополнительный вес диска/гантели у груди, а не масса тела.
    val hyperextension = Exercise("hyperextension", "Гиперэкстензия", 20, 2.5,
        gym = GymSpec(Equipment.MACHINE, LoadMode.EXTERNAL_ADDED, 2_500))

    // Брусья: свой вес, заявляется только дополнительный вес на поясе (по умолчанию 0). Свои угловые профили
    // (pose/GymAngularProfiles): камера сбоку, корпус вертикален — не путается с отжиманиями от пола и приседом.
    val barsDip = Exercise("bars_dip", "Отжимания на брусьях", 40, 1.5,
        gym = GymSpec(Equipment.BARS, LoadMode.BELT_ADDED, 2_500))
    val barsLegRaise = Exercise("bars_leg_raise", "Подъём ног в упоре на брусьях", 25, 1.5,
        gym = GymSpec(Equipment.BARS, LoadMode.BELT_ADDED, 2_500))

    val bodyweight = listOf(pushups, squats, abs, pullups, plank)
    val kettlebell = listOf(thruster, swing, sumoPull, row, deadlift, gobletSquat, press, reverseLunge, curl, gluteBridge)

    /** Домашние упражнения (без веса и с гирей): из них собираются комплексы и свои программы. */
    val all = bodyweight + kettlebell

    /** Зал — отдельно от домашних: в свои программы и комплексы не входит. */
    val gym = listOf(
        barbellSquat, barbellFrontSquat, barbellDeadlift, barbellRomanianDeadlift,
        barbellBenchPress, barbellInclinePress, barbellPress, barbellRow,
        dumbbellBenchPress, dumbbellInclinePress, dumbbellSeatedPress, dumbbellRow, dumbbellCurl,
        dumbbellLateralRaise, dumbbellLunge, dumbbellBulgarianSplitSquat, dumbbellGobletSquat,
        machineLatPulldown, machineSeatedRow, machineChestPress, machineLegExtension,
        machineLegCurl, machineLyingLegCurl, assistedPullup, hyperextension,
        barsDip, barsLegRaise,
    )

    /** Все упражнения приложения: домашние и зал. */
    val catalog = all + gym

    fun byId(id: String): Exercise = catalog.first { it.id == id }

    fun byIdOrNull(id: String): Exercise? = catalog.firstOrNull { it.id == id }

    /** Награда в миллисекундах за [reps] повторений; вес учитывается только для гиревых (у зала — нет). */
    fun rewardMs(ex: Exercise, reps: Int, weightKg: Int): Long {
        val factor = if (ex.kettlebell) Weights.factor(weightKg) else 1.0
        return (reps * ex.rewardSecondsPerRep * factor * 1000).roundToLong()
    }

    /**
     * Награда в миллисекундах за [heldMs] засчитанного удержания (планка): платятся только
     * целые секунды, по [Exercise.rewardSecondsPerRep] секунд за каждую. 1:20 планки = 6 мин 40 сек.
     */
    fun holdRewardMs(ex: Exercise, heldMs: Long): Long =
        (heldMs.coerceAtLeast(0L) / 1000) * ex.rewardSecondsPerRep * 1000L

    /** Опыт: 1 очко за каждые 10 секунд заработанного времени. */
    fun xp(rewardMs: Long): Long = rewardMs / 10_000
}

/**
 * Комплекс «Синди»: 20 минут, как можно больше кругов из
 * 5 подтягиваний, 10 отжиманий и 15 приседаний. Результат — круги + повторения сверх.
 * Награда — по обычным ценам повторений, поэтому один полный круг = 20 минут.
 */
object Cindy {
    const val ID = "cindy"
    const val DURATION_MS = 20 * 60 * 1000L

    data class Part(val exercise: Exercise, val reps: Int)

    val round = listOf(
        Part(Exercises.pullups, 5),
        Part(Exercises.pushups, 10),
        Part(Exercises.squats, 15),
    )
    val repsPerRound = round.sumOf { it.reps }

    /** [repsByExercise] — сколько всего повторений каждого упражнения засчитано за 20 минут. */
    fun rewardMs(repsByExercise: Map<Exercise, Int>): Long =
        repsByExercise.entries.sumOf { (ex, reps) -> Exercises.rewardMs(ex, reps, Weights.DEFAULT) }
}

/** Комплекс «Минотавр»: лесенка 10 → 1 по пяти упражнениям с гирей, 275 повторений. */
object Minotaur {
    const val ID = "minotaur"
    val exercises = listOf(
        Exercises.thruster,
        Exercises.swing,
        Exercises.sumoPull,
        Exercises.row,
        Exercises.deadlift,
    )
    val ladder = (10 downTo 1).toList()
    private val repsPerExercise = ladder.sum()
    val totalReps = repsPerExercise * exercises.size

    /**
     * Фиксированная награда за весь комплекс: 2,5 / 3 / 4 часа.
     * Чуть больше суммы тех же повторений по отдельности (2:22 / 2:50 / 3:47) —
     * небольшой бонус за то, что комплекс пройден целиком.
     */
    fun rewardMs(weightKg: Int): Long = when (weightKg) {
        32 -> 240
        24 -> 180
        else -> 150
    } * 60_000L

    /** Сколько дали бы те же повторения, сделанные по отдельности. */
    fun separateRewardMs(weightKg: Int): Long =
        exercises.sumOf { Exercises.rewardMs(it, repsPerExercise, weightKg) }

    fun xp(weightKg: Int): Long = Exercises.xp(rewardMs(weightKg))

    /** Быстрее этого пройти комплекс нереально: награда не начисляется. */
    const val MIN_DURATION_MS = 7 * 60 * 1000L

    data class Step(val round: Int, val reps: Int, val exercise: Exercise)

    val steps: List<Step> = ladder.flatMapIndexed { i, reps ->
        exercises.map { Step(round = i + 1, reps = reps, exercise = it) }
    }
}
