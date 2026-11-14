package com.nextlevellife.app.ui

import com.nextlevellife.app.block.BlockRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Чистая логика экрана «Настройки» и строки расхода на главной, без Android: её проверяют юнит-тесты. */
internal object SettingsMath {

    /** Варианты дневного лимита на экране «Настройки», минуты. */
    val LIMIT_CHOICES = listOf(90, 120, 180)

    /** Целые минуты (с округлением вниз): 35 мин 59 сек — это ещё «35». */
    fun wholeMinutes(ms: Long): Long = ms.coerceAtLeast(0L) / 60_000L

    /** «35 из 120 мин»; без лимита ([limitMin] ≤ 0) — просто «35 мин». */
    fun usageText(spentMs: Long, limitMin: Int): String {
        val spent = wholeMinutes(spentMs)
        return if (limitMin > 0) "$spent из $limitMin мин" else "$spent мин"
    }

    /** «Сегодня использовано: 35 из 120 мин» — для «Настроек». */
    fun usedTodayLine(spentMs: Long, limitMin: Int): String = "Сегодня использовано: ${usageText(spentMs, limitMin)}"

    /** «Сегодня потрачено 35 из 120 мин» — для карточки баланса на главной. */
    fun spentTodayLine(spentMs: Long, limitMin: Int): String = "Сегодня потрачено ${usageText(spentMs, limitMin)}"

    /** Доля израсходованного лимита для полосы, 0..1; без лимита — 0. */
    fun usageFraction(spentMs: Long, limitMin: Int): Float {
        if (limitMin <= 0) return 0f
        return (spentMs.coerceAtLeast(0L).toFloat() / (limitMin * 60_000f)).coerceIn(0f, 1f)
    }

    /** «23:00». */
    fun clock(minuteOfDay: Int): String = BlockRules.formatClock(minuteOfDay)

    /** «Ночной режим с 23:00 до 07:00». */
    fun nightLine(startMin: Int, endMin: Int): String = "Ночной режим с ${clock(startMin)} до ${clock(endMin)}"

    /** Минута суток из часов и минут пикера; вне суток — null (сеттеры Settings такое не примут). */
    fun minuteOfDay(hour: Int, minute: Int): Int? {
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** Пояснение, почему время ночи нельзя сохранить; null — можно. */
    fun nightProblem(startMin: Int, endMin: Int): String? =
        if (startMin == endMin) "Начало и окончание совпадают — выбери другое время" else null

    /** Имя файла копии по умолчанию: «next-level-life-2026-11-12.zip». */
    fun backupFileName(date: LocalDate): String =
        "next-level-life-%04d-%02d-%02d.zip".format(date.year, date.monthValue, date.dayOfMonth)

    /** «12 ноября, 22:40»; год — только если он не текущий. */
    fun dateTime(timeMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate().toEpochDay()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()
        return "${ProgressMath.dateLabel(day, today)}, ${ProgressMath.clock(timeMs, zone)}"
    }

    /** Минуты со знаком для кошельков (кошелёк бывает отрицательным после долга): «−3», «12». */
    private fun signedMinutes(ms: Long): String = if (ms < 0) "−${wholeMinutes(-ms)}" else "${wholeMinutes(ms)}"

    /** «На этом телефоне 25 мин · на втором 10 мин» — под общим балансом связанного телефона. */
    fun walletsLine(myMs: Long, peerMs: Long): String =
        "На этом телефоне ${signedMinutes(myMs)} мин · на втором ${signedMinutes(peerMs)} мин"

    /** «На втором телефоне есть ещё 25 мин — включи его экран рядом»; меньше минуты — null. */
    fun peerResourceLine(peerMs: Long): String? {
        val m = wholeMinutes(peerMs)
        return if (m <= 0) null else "На втором телефоне есть ещё $m мин — включи его экран рядом"
    }

    /** Статус пары: «Нет пары», «Связан с Galaxy Z Fold8». */
    fun pairStatus(peerName: String?): String = if (peerName == null) "Нет пары" else "Связан с $peerName"

    /** «Последний обмен: 12 ноября, 22:40», «Второй телефон не найден», «Обмена ещё не было». */
    fun lastSyncLine(lastSuccessMs: Long?, notFound: Boolean, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = when {
        notFound && lastSuccessMs != null -> "Второй телефон не найден. Последний обмен: ${dateTime(lastSuccessMs, nowMs, zone)}"
        notFound -> "Второй телефон не найден"
        lastSuccessMs != null -> "Последний обмен: ${dateTime(lastSuccessMs, nowMs, zone)}"
        else -> "Обмена ещё не было"
    }

    /** «Последняя копия: 12 ноября, 22:40» или «Последняя копия: ещё не создавалась». */
    fun lastBackupLine(timeMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "Последняя копия: " + (timeMs?.let { dateTime(it, nowMs, zone) } ?: "ещё не создавалась")
}
