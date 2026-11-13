package com.nextlevellife.app.stats

import com.nextlevellife.app.data.Store
import com.nextlevellife.app.health.ActivityMath

/**
 * Тексты экранов «Как получить» для характеристик и уровня, без Android: их проверяют юнит-тесты.
 * Все числа берутся из [StatsMath], так что после настройки правил описание не разойдётся с расчётом.
 */
object StatsGuide {
    /** Неразрывный пробел между числом и словом. */
    private const val NBSP = ' '

    /** Одно правило: за что ([what]) → сколько ([reward]); [note] — уточнение мелким шрифтом. */
    data class Rule(val what: String, val reward: String, val note: String? = null)

    /** Описание характеристики: подзаголовок, правила, ограничения, штраф за простой и пример. */
    data class StatGuide(
        val stat: Stat,
        val subtitle: String,
        val rules: List<Rule>,
        val limits: List<String>,
        val penalty: String,
        val example: String,
    )

    /** Ступень лестницы уровней: уровень, сколько очков нужно, звание. */
    data class LevelStep(val level: Int, val points: Long, val title: String)

    /** Уровни, на которых меняется звание ([Store.titleFor]). */
    val TITLE_LEVELS = listOf(1, 3, 5, 8, 12, 20)

    fun forStat(stat: Stat, limitMin: Int): StatGuide = when (stat) {
        Stat.STRENGTH -> strength()
        Stat.ENDURANCE -> endurance()
        Stat.DISCIPLINE -> discipline(limitMin)
    }

    private fun strength() = StatGuide(
        stat = Stat.STRENGTH,
        subtitle = "Упражнения перед камерой",
        rules = listOf(
            Rule(
                "Каждые ${StatsMath.REPS_PER_POINT} повторений",
                points(1),
                "Считаются, если за день был хотя бы один полный подход — от ${StatsMath.FULL_SET_REPS} повторений " +
                    "(или завершён комплекс). Отжимания, приседания и остальное, в комплексах тоже",
            ),
            Rule(
                "Каждые ${StatsMath.PLANK_SECONDS_PER_POINT} секунд планки",
                points(1),
                "Считается от минуты за день суммарно: меньше минуты — без очков, минута — ${points(2)}",
            ),
            Rule(
                "Завершённый комплекс",
                "+${points(StatsMath.COMPLEX_BONUS)}",
                "Один раз за день, сколько бы комплексов ни было",
            ),
        ),
        limits = listOf(
            "За повторения и планку вместе — не больше ${points(StatsMath.STRENGTH_WORK_CAP)} в день",
            "Всего — не больше ${points(StatsMath.DAY_CAP)} в день",
        ),
        penalty = penaltyText(Stat.STRENGTH, "День хотя бы с одним очком силы обнуляет счёт."),
        example = "3 подхода по 15 отжиманий и минута планки → 4 + 2 = ${points(6)}",
    )

    private fun endurance() = StatGuide(
        stat = Stat.ENDURANCE,
        subtitle = "Движение за день",
        rules = listOf(
            Rule(
                "${thousands(StatsMath.WALK_FIRST_POINT_STEPS)} шагов за день",
                points(1),
                "Меньше — без очков. Шаги с часов или телефона через Health Connect",
            ),
            Rule("Каждые следующие ${thousands(StatsMath.WALK_STEPS_PER_POINT)} шагов", "+${points(1)}"),
            Rule("${StatsMath.RUN_METERS_PER_POINT / 1000} км бега", points(1), "Шаги пробежки второй раз как ходьба не считаются"),
            Rule(
                "${StatsMath.COMPLEX_MS_PER_POINT / 60_000} минут завершённого комплекса",
                points(1),
                "Не больше ${points(StatsMath.COMPLEX_TIME_CAP)} в день",
            ),
        ),
        limits = listOf(
            "Всего — не больше ${points(StatsMath.DAY_CAP)} в день",
            "Без Health Connect очки за шаги и бег не начисляются",
        ),
        penalty = penaltyText(
            Stat.ENDURANCE,
            "Очко выносливости — от ${thousands(StatsMath.WALK_FIRST_POINT_STEPS)} шагов, " +
                "${StatsMath.RUN_METERS_PER_POINT / 1000} км бега или ${StatsMath.COMPLEX_MS_PER_POINT / 60_000} минут " +
                "комплекса; такой день обнуляет счёт. Дни без данных о шагах не считаются и тоже обнуляют счёт.",
        ),
        example = "10${NBSP}000 шагов → 1 + 2 = ${points(3)}",
    )

    private fun discipline(limitMin: Int): StatGuide {
        val limitNote = if (limitMin > 0) {
            val allowed = limitMin * StatsMath.LIMIT_SHARE_PERCENT / 100
            "Сейчас лимит $limitMin мин — значит, не больше $allowed мин"
        } else {
            "Сейчас дневной лимит выключен — включи его в настройках"
        }
        return StatGuide(
            stat = Stat.DISCIPLINE,
            subtitle = "Привычки с телефоном",
            rules = listOf(
                Rule(
                    "Ночь без телефона",
                    "+${points(StatsMath.PHONE_FREE_POINTS)}",
                    "Перерыв от 6 часов, и в часы ночного режима телефон не открывали",
                ),
                Rule(
                    "Ночь без блокировок",
                    "+${points(StatsMath.NIGHT_NO_BLOCKS_POINTS)}",
                    "Ночной режим включён, и ночью ни разу не пытался открыть заблокированное",
                ),
                Rule(
                    "Уложился в лимит",
                    "+${points(StatsMath.LIMIT_POINTS)}",
                    "Потратил за сутки не больше ${StatsMath.LIMIT_SHARE_PERCENT}$NBSP% дневного лимита. $limitNote",
                ),
            ),
            limits = listOf(
                "Очки приходят после окончания ночи или суток, поэтому днём они предварительные",
                "Если служба блокировки не работала (телефон выключали или она отключалась), " +
                    "за эту ночь или сутки очков нет",
                "Без ночного режима и дневного лимита дисциплину не заработать",
            ),
            penalty = penaltyText(Stat.DISCIPLINE, "Любое очко дисциплины обнуляет счёт."),
            example = "Ночь без телефона и без блокировок, день в лимите → 4 + 3 + 3 = ${points(10)}",
        )
    }

    /**
     * Сила: «2 дня отдыха и 3-й день на тренировку — без штрафа. Если и 4-й день без очков силы — −1 очко,
     * дальше −1 на 7-й, 10-й день и так далее. …». Остальные: «Каждые 3 дня подряд без очков … — −1 очко: на 3-й…».
     */
    private fun penaltyText(stat: Stat, reset: String): String {
        val n = StatsMath.IDLE_PERIOD_DAYS
        val first = StatsMath.firstIdlePenaltyDay(stat)
        val minus = "${StatsText.signed(-StatsMath.IDLE_PENALTY)}$NBSP${StatsText.pointsWord(StatsMath.IDLE_PENALTY.toLong())}"
        val head = if (stat == Stat.STRENGTH) {
            "${StatsText.daysLabel(StatsMath.STRENGTH_REST_DAYS.toLong())} отдыха и ${StatsMath.STRENGTH_REST_DAYS + 1}-й день " +
                "на тренировку — без штрафа. Если и ${first}-й день подряд прошёл ${StatsText.idleWhat(stat)} — $minus, " +
                "дальше $minus на ${first + n}-й, ${first + 2 * n}-й день и так далее."
        } else {
            "Каждые ${StatsText.daysLabel(n.toLong())} подряд ${StatsText.idleWhat(stat)} — $minus: " +
                "на ${first}-й день, на ${first + n}-й, на ${first + 2 * n}-й и так далее."
        }
        return "$head $reset Ниже нуля очки не опускаются."
    }

    /** Что входит в уровень — абзацы экрана уровня. */
    val levelFacts: List<String> = listOf(
        "Общий уровень — это сумма очков силы, выносливости и дисциплины за всё время.",
        "Каждая характеристика даёт до ${points(StatsMath.DAY_CAP)} в день, всего до " +
            "${points(StatsMath.DAY_CAP * Stat.entries.size)} в день.",
        "Если забросить характеристику, простой медленно снимает очки — по ${points(StatsMath.IDLE_PENALTY)} " +
            "раз в ${StatsMath.IDLE_PERIOD_DAYS} дня; у силы — только после 2 дней отдыха и дня на тренировку. " +
            "Уровень может опуститься.",
        "Минуты экранного времени в уровень не входят: минуты ты тратишь, а очки копятся.",
        "У каждой характеристики свой уровень по той же лестнице.",
    )

    /** Лестница: уровни, на которых меняется звание, и сколько очков для них нужно. */
    fun ladder(): List<LevelStep> = TITLE_LEVELS.map { LevelStep(it, StatsMath.pointsForLevel(it), Store.titleFor(it)) }

    /** «Каждый следующий уровень даётся дольше: 2 — 50, 3 — 200, 4 — 450, 5 — 800 очков». */
    val ladderNote: String = "Каждый следующий уровень даётся дольше: " +
        (2..5).joinToString(", ") { "$it-й$NBSP—$NBSP${thousands(StatsMath.pointsForLevel(it))}" } + " очков"

    /** «1 очко», «2 очка», «10 очков». */
    fun points(n: Int): String = "$n$NBSP${StatsText.pointsWord(n.toLong())}"

    private fun thousands(n: Long): String = ActivityMath.formatThousands(n)
}
