package com.nextlevellife.app.stats

import com.nextlevellife.app.health.ActivityMath

/** Подписи характеристик для экранов, без Android: их проверяют юнит-тесты. */
object StatsText {
    /** Неразрывный пробел: «Сила +7» не разрывается между словом и числом. */
    private const val NBSP = ' '

    /** «Сила 2» — характеристика и её уровень. */
    fun statLevel(stat: Stat, points: Long): String = "${stat.title}$NBSP${StatsMath.levelFor(points)}"

    /** «7/10» — очки за день из потолка. */
    fun ofCap(points: Int): String = "$points/${StatsMath.DAY_CAP}"

    /** «Сила +7 · Выносливость +3 · Дисциплина +4»: внутри пункта переносов нет, только по « · ». */
    fun dayLine(p: DayPoints): String =
        Stat.entries.joinToString(" · ") { "${it.title}$NBSP${signed(p.of(it))}" }

    /** «+7», «−2», «+0». */
    fun signed(n: Int): String = if (n < 0) "−${-n}" else "+$n"

    /**
     * Предупреждение о штрафе за сегодня: «Если сегодня без очков силы — сила −1»; штрафа не будет — null.
     */
    fun riskLine(stat: Stat, risk: Int): String? {
        if (risk >= 0) return null
        return "Если сегодня ${idleWhat(stat)} — ${stat.title.lowercase()} ${signed(risk)}"
    }

    /** Что значит простой характеристики: «без очков силы», «без очков выносливости», «без очков дисциплины». */
    fun idleWhat(stat: Stat): String = when (stat) {
        Stat.STRENGTH -> "без очков силы"
        Stat.ENDURANCE -> "без очков выносливости"
        Stat.DISCIPLINE -> "без очков дисциплины"
    }

    /** «1 день», «3 дня», «6 дней». */
    fun daysLabel(n: Long): String = "$n$NBSP${plural(n, "день", "дня", "дней")}"

    /** «87 очков», «1 очко», «1 234 очка». */
    fun pointsLabel(points: Long): String =
        "${ActivityMath.formatThousands(points)}$NBSP${pointsWord(points)}"

    fun pointsWord(n: Long): String = plural(n, "очко", "очка", "очков")

    /** «До следующего уровня 40 очков». */
    fun toNextLevel(left: Long): String = "До следующего уровня ${pointsLabel(left)}"

    /** «Опыт до обновления: 120» — старый опыт за минуты, только для истории. */
    fun legacyXpLine(xp: Long): String = "Опыт до обновления: ${ActivityMath.formatThousands(xp)}"

    /**
     * Источник очков: «70 повторений → 7», «планка 1:30 → 3», «завершён комплекс → 2»,
     * «8 400 шагов → 4», «бег 5,2 км → 5», «комплексы 24 мин → 4», «Ночь без телефона → 4»,
     * «расход 60 % лимита → 3», «ночью без блокировок → 3». Срезанное потолком — «→ 1 (потолок)».
     */
    fun sourceLine(s: PointSource): String {
        val what = when (s.kind) {
            SourceKind.REPS -> "${ActivityMath.formatThousands(s.amount)}$NBSP${repsWord(s.amount)}"
            SourceKind.PLANK -> "планка ${formatSeconds(s.amount)}"
            SourceKind.COMPLEX_DONE ->
                if (s.amount > 1) "завершено комплексов: ${s.amount}" else "завершён комплекс"
            SourceKind.WALK -> "${ActivityMath.formatThousands(s.amount)}$NBSP${ActivityMath.stepsWord(s.amount)}"
            SourceKind.RUN -> "бег ${ActivityMath.formatKm(s.amount)}"
            SourceKind.COMPLEX_TIME -> "комплексы ${ActivityMath.formatMinutes(s.amount)}"
            SourceKind.PHONE_FREE_NIGHT -> "Ночь без телефона"
            SourceKind.LIMIT_KEPT -> "расход ${s.amount}$NBSP% лимита"
            SourceKind.NIGHT_NO_BLOCKS -> "ночью без блокировок"
            SourceKind.STRENGTH_IDLE -> "${daysLabel(s.amount)} подряд ${idleWhat(Stat.STRENGTH)}"
            SourceKind.ENDURANCE_IDLE -> "${daysLabel(s.amount)} подряд ${idleWhat(Stat.ENDURANCE)}"
            SourceKind.DISCIPLINE_IDLE -> "${daysLabel(s.amount)} подряд ${idleWhat(Stat.DISCIPLINE)}"
        }
        val why = when {
            s.capped -> " (потолок)"
            s.points != 0 -> ""
            s.kind == SourceKind.REPS && s.amount >= StatsMath.REPS_PER_POINT ->
                " (нет подхода от ${StatsMath.FULL_SET_REPS})"
            s.kind == SourceKind.PLANK && s.amount < StatsMath.PLANK_MIN_SECONDS -> " (меньше минуты)"
            s.kind == SourceKind.WALK -> " (меньше ${ActivityMath.formatThousands(StatsMath.WALK_FIRST_POINT_STEPS)})"
            else -> ""
        }
        val pts = if (s.points < 0) signed(s.points) else s.points.toString()
        return "$what → $pts$why"
    }

    /**
     * Прирост силы на экране результата (предварительно, за сегодня): «+3 силы (сегодня 7/10)»;
     * без прироста — «сила за сегодня уже 10/10» или «сила сегодня 7/10». Не известно — null.
     */
    fun strengthResult(gain: Int?, today: Int?): String? {
        if (gain == null || today == null || gain < 0 || today < 0) return null
        return when {
            gain > 0 -> "+$gain силы (сегодня ${ofCap(today)})"
            today >= StatsMath.DAY_CAP -> "сила за сегодня уже ${ofCap(today)}"
            else -> "сила сегодня ${ofCap(today)}"
        }
    }

    /** «1:30», «12:05». */
    private fun formatSeconds(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

    private fun repsWord(n: Long): String = plural(n, "повторение", "повторения", "повторений")

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
