package com.nextlevellife.app.ui

/**
 * Текст предупреждения «служба блокировки может уснуть» под оболочку телефона. Без Android: проверяется тестами.
 * [manufacturer] — Build.MANUFACTURER (регистр любой).
 */
object BatteryHint {
    data class Text(val title: String, val body: String)

    private const val BASE = "Разреши работу без ограничений батареи, иначе служба будет отключаться в фоне."

    fun forManufacturer(manufacturer: String?): Text {
        val m = manufacturer.orEmpty().trim().lowercase()
        return when {
            m == "samsung" -> Text("Samsung может усыпить блокировку", BASE)
            m in TRANSSION -> Text(
                "HiOS может усыпить блокировку",
                "$BASE На Tecno, Infinix и itel ещё включи автозапуск: «Phone Master» → «Автозапуск» → Next Level Life.",
            )
            m in XIAOMI -> Text(
                "MIUI / HyperOS может усыпить блокировку",
                "$BASE Ещё включи автозапуск: «Безопасность» → «Автозапуск» → Next Level Life.",
            )
            else -> Text("Телефон может усыпить блокировку", BASE)
        }
    }

    private val TRANSSION = setOf("tecno", "tecno mobile", "infinix", "infinix mobility", "itel")
    private val XIAOMI = setOf("xiaomi", "redmi", "poco")
}
