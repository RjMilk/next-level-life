package com.nextlevellife.app.ui

import com.nextlevellife.app.data.Exercise
import com.nextlevellife.app.data.Weights
import com.nextlevellife.app.stats.Stat
import com.nextlevellife.app.stats.StatsText

/** Краткие сводки свёрнутых разделов, без Android: их проверяют юнит-тесты. */
object ExpandableText {
    /** Неразрывный пробел: «16 кг», «5 упражнений» не разрываются на узком экране. */
    private const val NBSP = ' '

    /** «Сила 2 · Выносливость 1 · Дисциплина 3»: внутри пункта переносов нет, только по « · ». */
    fun statsSummary(points: (Stat) -> Long): String =
        Stat.entries.joinToString(" · ") { StatsText.statLevel(it, points(it)) }

    /** «Отжимания, Приседания, Пресс, Подтягивания, Планка». */
    fun exerciseNames(exercises: List<Exercise>): String = exercises.joinToString(", ") { it.name }

    /** «5 упражнений · цена за 16 кг; 24 кг ×1,2, 32 кг ×1,6» — множители берутся из [Weights]. */
    fun kettlebellSummary(count: Int): String {
        val base = Weights.DEFAULT
        val factors = Weights.all.filter { it != base }
            .joinToString(", ") { w -> "$w${NBSP}кг$NBSP×${factorLabel(Weights.factor(w))}" }
        return "$count$NBSP${pluralRu(count, "упражнение", "упражнения", "упражнений")} · " +
            "цена за $base${NBSP}кг; $factors"
    }

    /** Множитель с запятой: 1.2 → «1,2». */
    fun factorLabel(factor: Double): String = factor.toString().replace('.', ',')

    /**
     * Что лежит в «Рекордах»: «Шаги и бег, Минотавр, Синди, упражнения». Минотавр и Синди есть всегда
     * (с прочерком, если рекорда нет), шаги и упражнения — только при наличии записей.
     */
    fun recordsSummary(hasActivity: Boolean, hasExercises: Boolean): String {
        val parts = buildList {
            if (hasActivity) add("шаги и бег")
            add("Минотавр")
            add("Синди")
            if (hasExercises) add("упражнения")
        }
        return parts.joinToString(", ").replaceFirstChar { it.uppercaseChar() }
    }
}
