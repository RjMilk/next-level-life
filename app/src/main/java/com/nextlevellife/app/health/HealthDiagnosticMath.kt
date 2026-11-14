package com.nextlevellife.app.health

/** Сумма сырых записей нужна только для сверки с агрегатом, не для начисления минут. */
data class DiagnosticRawStep(val origin: String, val count: Long)
data class DiagnosticRawPage(val records: List<DiagnosticRawStep>, val nextToken: String?)
data class DiagnosticRawTotal(val steps: Long, val records: Int)

object HealthDiagnosticMath {
    /** Все страницы без ограничения числа записей. Повтор токена завершает чтение явной ошибкой. */
    suspend fun readRawPages(
        totals: MutableMap<String, DiagnosticRawTotal>,
        readPage: suspend (String?) -> DiagnosticRawPage,
    ) {
        var token: String? = null
        val seenTokens = HashSet<String>()
        do {
            val page = readPage(token)
            page.records.forEach { record ->
                val previous = totals[record.origin] ?: DiagnosticRawTotal(0L, 0)
                totals[record.origin] = DiagnosticRawTotal(previous.steps + record.count, previous.records + 1)
            }
            token = page.nextToken?.takeIf { it.isNotEmpty() }
            check(token == null || seenTokens.add(token)) { "Провайдер повторил токен страницы" }
        } while (token != null)
    }

    fun maskDeviceIds(text: String, ids: List<String>): String =
        ids.fold(text) { masked, id -> if (id.isEmpty()) masked else masked.replace(id, id.take(4)) }
}
