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
        penalty = penaltyText(StatsMath.STRENGTH_FREE_IDLE_DAYS, "без тренировки", "День хотя бы с одним очком силы обнуляет счёт."),
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
        penalty = "Штрафа за простой нет.",
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
            penalty = penaltyText(StatsMath.DISCIPLINE_FREE_IDLE_DAYS, "без очков дисциплины", "Любое очко дисциплины обнуляет счёт."),
            example = "Ночь без телефона и без блокировок, день в лимите → 4 + 3 + 3 = ${points(10)}",
        )
    }

    /** «3 дня подряд без тренировки — без штрафа. С 4-го дня: −1, на 5-й −2, дальше −3 в день. …» */
    private fun penaltyText(free: Int, what: String, reset: String): String {
        val p1 = StatsMath.idlePenalty(free + 1, free)
        val p2 = StatsMath.idlePenalty(free + 2, free)
        val max = -StatsMath.MAX_IDLE_PENALTY
        return "${daysLabel(free)} подряд $what — без штрафа. С ${free + 1}-го дня: ${StatsText.signed(p1)}, " +
            "на ${free + 2}-й ${StatsText.signed(p2)}, дальше ${StatsText.signed(max)} в день. $reset " +
            "Ниже нуля очки не опускаются."
    }

    /** Что входит в уровень — абзацы экрана уровня. */
    val levelFacts: List<String> = listOf(
        "Общий уровень — это сумма очков силы, выносливости и дисциплины за всё время.",
        "Каждая характеристика даёт до ${points(StatsMath.DAY_CAP)} в день, всего до " +
            "${points(StatsMath.DAY_CAP * Stat.entries.size)} в день.",
        "Если забросить тренировки или дисциплину, начинаются штрафы: очки постепенно уменьшаются, " +
            "и уровень может опуститься. Выносливость не штрафуется.",
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

    /** «2 дня», «3 дня», «5 дней». */
    private fun daysLabel(n: Int): String = "$n$NBSP" + when {
        n % 100 in 11..14 -> "дней"
        n % 10 == 1 -> "день"
        n % 10 in 2..4 -> "дня"
        else -> "дней"
    }

    private fun thousands(n: Long): String = ActivityMath.formatThousands(n)
}
