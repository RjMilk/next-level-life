package com.nextlevellife.app.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.nextlevellife.app.data.Ledger
import com.nextlevellife.app.data.Settings
import com.nextlevellife.app.data.SharedSettings
import com.nextlevellife.app.data.Store
import com.nextlevellife.app.data.TrustedClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

data class HealthDiagnosticOrigin(
    val packageName: String,
    val label: String,
    val aggregateSteps: Long?,
    val rawSteps: Long?,
    val rawRecords: Int?,
    val error: String? = null,
)

data class HealthDiagnosticDay(
    val day: Long,
    val aggregateSteps: Long?,
    val aggregateOrigins: Set<String>,
    val origins: List<HealthDiagnosticOrigin>,
    val errors: List<String>,
)

data class HealthDiagnosticsReport(
    val reportText: String,
    val days: List<HealthDiagnosticDay>,
    val totalSteps: Long?,
    val canRequestHistory: Boolean,
)

/** Чтение для сверки. Оно не меняет activity_* и журнал начислений и работает на любом телефоне пары. */
object HealthDiagnostics {
    private const val LAST_ATTEMPT = "last_attempt_ms"
    private const val LAST_ERROR = "last_error"

    internal fun recordAttempt(context: Context, timeMs: Long) {
        context.getSharedPreferences(HealthConnect.DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
            .edit().putLong(LAST_ATTEMPT, timeMs).apply()
    }

    internal fun recordResult(context: Context, error: String?) {
        context.getSharedPreferences(HealthConnect.DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
            .edit().putString(LAST_ERROR, error).apply()
    }

    suspend fun read(context: Context): HealthDiagnosticsReport = withContext(Dispatchers.IO) {
        Store.init(context)
        val time = TrustedClock.snapshot()
        val me = Ledger.deviceId()
        val peer = Ledger.peerId()
        val status = HealthConnect.sdkStatus(context)
        val errors = ArrayList<String>()
        val client = if (status == HealthConnectClient.SDK_AVAILABLE) attempt(errors, "Клиент") {
            HealthConnectClient.getOrCreate(context, HealthConnect.PROVIDER_PACKAGE)
        } else null
        val granted = client?.let { attempt(errors, "Разрешения") { it.permissionController.getGrantedPermissions() } }
        val historyAvailable = client?.let {
            attempt(errors, "Поддержка истории") {
                it.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                    HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            }
        } ?: false
        val backgroundAvailable = client?.let {
            attempt(errors, "Поддержка фонового чтения") {
                it.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                    HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            }
        } ?: false
        val days = (time.day - 6..time.day).map { day ->
            if (client == null || granted == null || HealthConnect.STEPS_PERMISSION !in granted) {
                HealthDiagnosticDay(day, null, emptySet(), emptyList(), listOf("Чтение шагов недоступно"))
            } else readDay(context, client, day, time.zone)
        }
        val prefs = context.getSharedPreferences(HealthConnect.DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
        val report = buildString {
            appendLine("Проверка данных Health Connect")
            appendLine("Проверено ${Instant.ofEpochMilli(time.utcMs).atZone(time.zone)}")
            appendLine("Телефон ${me.take(4)}; второй ${peer?.take(4) ?: "нет"}")
            appendLine("Выбранный источник ${SharedSettings.activitySource()?.take(4) ?: "не выбран"}")
            appendLine("Этот телефон сейчас источник: ${if (SharedSettings.isActivitySource()) "да" else "нет"}")
            appendLine("Выбор ожидает подтверждения обменом: ${yesNo(peer != null && SharedSettings.activitySourcePending())}")
            appendLine("Учётный пояс ${time.zone.id}; пояс телефона ${java.time.ZoneId.systemDefault().id}")
            appendLine("Сутки от 00:00 до 00:00 в учётном поясе; сегодня и 6 предыдущих суток.")
            appendLine("Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}; connect-client 1.1.0")
            appendLine("Доступность провайдера ${statusText(status)} ($status)")
            appendLine(providerVersion(context))
            appendLine("Последнее успешное чтение приложением ${timestamp(Settings.activityLastSyncMs, time.zone)}")
            appendLine("Последняя попытка чтения ${timestamp(prefs.getLong(LAST_ATTEMPT, 0L).takeIf { it > 0L }, time.zone)}")
            appendLine("Ошибка последней попытки ${prefs.getString(LAST_ERROR, null) ?: ActivitySync.state.value.error ?: "нет"}")
            appendLine()
            appendLine("Разрешения")
            listOf(
                "Шаги" to HealthConnect.STEPS_PERMISSION,
                "Тренировки" to HealthConnect.EXERCISE_PERMISSION,
                "Дистанция" to HealthConnect.DISTANCE_PERMISSION,
                "История" to HealthConnect.HISTORY_PERMISSION,
                "Фоновое чтение" to HealthConnect.BACKGROUND_PERMISSION,
            ).forEach { (label, permission) ->
                val state = when {
                    granted == null -> "не удалось проверить"
                    permission in granted -> "выдано"
                    else -> "не выдано"
                }
                appendLine("$label — $state ($permission)")
            }
            appendLine("Расширенная история поддерживается: ${yesNo(historyAvailable)}; фоновое чтение: ${yesNo(backgroundAvailable)}")
            appendLine("Без разрешения истории Health Connect даёт данные за 30 дней до первой выдачи разрешений и последующие дни. История нужна для более ранних данных. Окно приложения — 14 прошлых суток и сегодня.")
            appendLine("Разрешение истории необязательно. Оно расширяет доступ Health Connect, окно синхронизации приложения остаётся 14 суток. Фоновое чтение приложение не запрашивает.")
            appendLine()
            days.forEach { day ->
                appendLine("${LocalDate.ofEpochDay(day.day)} — агрегат ${value(day.aggregateSteps)}")
                val labels = day.aggregateOrigins.sorted().map { "${appLabel(context, it)} ($it)" }
                appendLine("dataOrigins агрегата — ${labels.joinToString().ifEmpty { "нет" }}")
                day.origins.forEach { origin ->
                    appendLine("  ${origin.label} (${origin.packageName}) — агрегат ${value(origin.aggregateSteps)}, сырые записи ${value(origin.rawSteps)}, записей ${origin.rawRecords ?: "неизвестно"}")
                    origin.error?.let { appendLine("  Ошибка $it") }
                }
                day.errors.forEach { appendLine("Ошибка $it") }
                appendLine()
            }
            errors.forEach { appendLine("Ошибка $it") }
            appendLine("Сырые записи — сумма count всех возвращённых страниц за сутки, без устранения пересечений и приоритетов источников. Записи на границе суток могут отличаться от агрегата, который распределяет интервалы и устраняет дубли. Источники перечислены из агрегата и сырых записей.")
            appendLine("Для проверки прошлых дней: приложение читает 14 прошлых суток и сегодня в учётном поясе. День включения оплаты ${Settings.stepsRewardSinceDay?.let { LocalDate.ofEpochDay(it) } ?: "не задан"} ограничивает начисление минут. Прочитанные шаги сохраняются и до этого дня.")
            appendLine("Если агрегат мал, а сырые записи часов велики, проверь приоритеты приложений в Health Connect. Если записи часов отсутствуют здесь, проверь передачу данных приложением часов именно на этот телефон. Диагностика читает этот телефон независимо от выбора источника пары.")
        }
        // Любое сообщение провайдера тоже проходит маскирование известных идентификаторов пары.
        val masked = HealthDiagnosticMath.maskDeviceIds(report, listOfNotNull(me, peer))
        HealthDiagnosticsReport(
            reportText = masked,
            days = days,
            totalSteps = if (days.any { it.errors.isNotEmpty() }) null else days.sumOf { it.aggregateSteps ?: 0L },
            canRequestHistory = historyAvailable && granted != null && HealthConnect.HISTORY_PERMISSION !in granted,
        )
    }

    private suspend fun readDay(
        context: Context,
        client: HealthConnectClient,
        day: Long,
        zone: java.time.ZoneId,
    ): HealthDiagnosticDay {
        val filter = TimeRangeFilter.between(
            Instant.ofEpochMilli(ActivityMath.dayStartMs(day, zone)),
            Instant.ofEpochMilli(ActivityMath.dayStartMs(day + 1, zone)),
        )
        val errors = ArrayList<String>()
        val aggregate = attempt(errors, "Общий агрегат") {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), filter))
        }
        val raw = LinkedHashMap<String, DiagnosticRawTotal>()
        val rawComplete = attempt(errors, "Сырые записи") {
            HealthDiagnosticMath.readRawPages(raw) { token ->
                val page = client.readRecords(ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = filter,
                    pageSize = 1000,
                    pageToken = token,
                ))
                DiagnosticRawPage(
                    page.records.map { DiagnosticRawStep(it.metadata.dataOrigin.packageName, it.count) },
                    page.pageToken,
                )
            }
            true
        } == true
        val aggregateOrigins = aggregate?.dataOrigins?.mapTo(sortedSetOf()) { it.packageName }.orEmpty()
        val origins = (aggregateOrigins + raw.keys).sorted().map { name ->
            val originErrors = ArrayList<String>()
            val total = attempt(originErrors, "Агрегат источника") {
                client.aggregate(AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = filter,
                    dataOriginFilter = setOf(DataOrigin(name)),
                ))[StepsRecord.COUNT_TOTAL]
            }
            HealthDiagnosticOrigin(
                packageName = name,
                label = appLabel(context, name),
                aggregateSteps = total,
                rawSteps = if (rawComplete) raw[name]?.steps ?: 0L else null,
                rawRecords = if (rawComplete) raw[name]?.records ?: 0 else null,
                error = originErrors.takeIf { it.isNotEmpty() }?.joinToString(),
            )
        }
        return HealthDiagnosticDay(day, aggregate?.get(StepsRecord.COUNT_TOTAL), aggregateOrigins, origins, errors)
    }

    private suspend fun <T> attempt(errors: MutableList<String>, label: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errors += "$label — ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        null
    }

    @Suppress("DEPRECATION")
    private fun appLabel(context: Context, packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
    } catch (e: Exception) {
        packageName
    }

    @Suppress("DEPRECATION")
    private fun providerVersion(context: Context): String {
        val names = linkedSetOf(HealthConnect.PROVIDER_PACKAGE, "com.google.android.healthconnect.controller", "com.android.healthconnect.controller")
        runCatching {
            HealthConnectClient.getHealthConnectManageDataIntent(context, HealthConnect.PROVIDER_PACKAGE)
                .resolveActivity(context.packageManager)?.packageName?.let { names.add(it) }
        }
        val installed = names.mapNotNull { name ->
            runCatching {
                val info = context.packageManager.getPackageInfo(name, 0)
                "$name ${info.versionName ?: "без имени"} (${info.longVersionCode})"
            }.getOrNull()
        }
        return "Версия провайдера ${installed.joinToString().ifEmpty { "пакет недоступен PackageManager; см. Android/SDK выше" }}"
    }

    private fun timestamp(ms: Long?, zone: java.time.ZoneId): String =
        ms?.let { Instant.ofEpochMilli(it).atZone(zone).toString() } ?: "ещё не было"

    private fun value(steps: Long?): String = steps?.toString() ?: "нет данных"
    private fun yesNo(value: Boolean): String = if (value) "да" else "нет"
    private fun statusText(status: Int): String = when (status) {
        HealthConnectClient.SDK_AVAILABLE -> "доступен"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "нужно обновление"
        else -> "недоступен"
    }
}
