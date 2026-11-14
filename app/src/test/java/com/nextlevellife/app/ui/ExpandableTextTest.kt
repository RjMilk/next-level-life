package com.nextlevellife.app.ui

import com.nextlevellife.app.data.Exercises
import com.nextlevellife.app.stats.Stat
import com.nextlevellife.app.stats.StatsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExpandableTextTest {

    private val nbsp = ' '

    @Test
    fun statsSummaryListsAllThreeStatsWithoutBreaksInsideItems() {
        val points = Stat.entries.associateWith { listOf(0L, 500L, 5_000L, 50L)[it.ordinal] }
        val line = ExpandableText.statsSummary { points.getValue(it) }
        val items = line.split(" · ")
        assertEquals(Stat.entries.size, items.size)
        Stat.entries.forEachIndexed { i, stat ->
            assertEquals(StatsText.statLevel(stat, points.getValue(stat)), items[i])
            // Внутри пункта только неразрывный пробел: «Сила 2» не разрывается.
            assertFalse(items[i].contains(' '))
        }
    }

    @Test
    fun bodyweightSummaryIsExerciseNames() {
        assertEquals(
            "Отжимания, Приседания, Пресс, Подтягивания, Планка",
            ExpandableText.exerciseNames(Exercises.bodyweight),
        )
    }

    @Test
    fun kettlebellSummaryHasCountAndWeightFactors() {
        assertEquals(
            "5${nbsp}упражнений · цена за 16${nbsp}кг",
            ExpandableText.kettlebellSummary(5),
        )
        // Число в сводке — из списка, а не захардкожено.
        assertEquals(
            "${Exercises.kettlebell.size}${nbsp}упражнений · цена за 16${nbsp}кг",
            ExpandableText.kettlebellSummary(Exercises.kettlebell.size),
        )
        assertEquals("1${nbsp}упражнение · цена за 16${nbsp}кг", ExpandableText.kettlebellSummary(1))
        assertEquals(
            "Цена за 16${nbsp}кг; 24${nbsp}кг$nbsp×1,2, 32${nbsp}кг$nbsp×1,6",
            ExpandableText.kettlebellPrices(),
        )
        assertEquals("3${nbsp}упражнения", ExpandableText.kettlebellSummary(3).substringBefore(" ·"))
    }

    @Test
    fun recordsSummaryDependsOnWhatExists() {
        assertEquals("Минотавр, Синди", ExpandableText.recordsSummary(hasActivity = false, hasExercises = false))
        assertEquals("Минотавр, Синди, упражнения", ExpandableText.recordsSummary(hasActivity = false, hasExercises = true))
        assertEquals(
            "Шаги и бег, Минотавр, Синди, упражнения",
            ExpandableText.recordsSummary(hasActivity = true, hasExercises = true),
        )
    }
}
