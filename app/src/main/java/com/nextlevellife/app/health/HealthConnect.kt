package com.nextlevellife.app.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Что Health Connect может дать приложению прямо сейчас. */
enum class HealthAvailability {
    /** На телефоне Health Connect нет и поставить нельзя. */
    UNAVAILABLE,

    /** Health Connect есть, но его нужно обновить. */
    UPDATE_REQUIRED,

    /** Health Connect есть, но разрешения на шаги не выданы. */
    NOT_CONNECTED,

    /** Шаги читаются (тренировки — если выдано и это разрешение). */
    CONNECTED,
}

/** Итог проверки: доступность и выдано ли чтение тренировок и дистанции (без них пробежки не видны). */
data class HealthAccess(val availability: HealthAvailability, val canReadRuns: Boolean = false)

/** Прочитанное за окно дней: шаги по дням (null — данных нет), источники шагов по дням, пробежки. */
data class HealthSnapshot(
    val stepsByDay: Map<Long, Long?>,
    val sourcesByDay: Map<Long, Set<String>>,
    val runs: List<RunSession>,
)

/**
 * Обёртка над Health Connect: доступность, разрешения и чтение. Только чтение и только
 * в активном приложении: фонового чтения и истории старше 30 дней не запрашиваем.
 * Все обращения — в [Dispatchers.IO]. Ошибки чтения пробрасываются (их ловит [ActivitySync]),
 * проверка доступа ошибок не бросает.
 */
object HealthConnect {
    private const val TAG = "HealthConnect"

    /** Пакет Health Connect для Android 13 и старше (на 14+ он встроен в систему). */
    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    val STEPS_PERMISSION: String = HealthPermission.getReadPermission(StepsRecord::class)
    val DISTANCE_PERMISSION: String = HealthPermission.getReadPermission(DistanceRecord::class)
    val EXERCISE_PERMISSION: String = HealthPermission.getReadPermission(ExerciseSessionRecord::class)

    /** Всё, что просим: шаги, дистанция, тренировки. */
    val PERMISSIONS: Set<String> = setOf(STEPS_PERMISSION, DISTANCE_PERMISSION, EXERCISE_PERMISSION)

    /** Какие тренировки считаются бегом. */
    val RUNNING_TYPES: Set<Int> = setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    )

    /** Контракт системного окна разрешений Health Connect (запускать из активного экрана). */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    fun sdkStatus(context: Context): Int = try {
        HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)
    } catch (e: Exception) {
        Log.e(TAG, "getSdkStatus не удался", e)
        HealthConnectClient.SDK_UNAVAILABLE
    }

    /** Доступность и выданные разрешения. Не бросает: при сбое — «не подключено». */
    suspend fun access(context: Context): HealthAccess = withContext(Dispatchers.IO) {
        when (sdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> try {
                val granted = HealthConnectClient.getOrCreate(context, PROVIDER_PACKAGE)
                    .permissionController.getGrantedPermissions()
                HealthAccess(
                    availability = if (STEPS_PERMISSION in granted) HealthAvailability.CONNECTED else HealthAvailability.NOT_CONNECTED,
                    canReadRuns = EXERCISE_PERMISSION in granted && DISTANCE_PERMISSION in granted,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось узнать разрешения Health Connect", e)
                HealthAccess(HealthAvailability.NOT_CONNECTED)
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAccess(HealthAvailability.UPDATE_REQUIRED)
            else -> HealthAccess(HealthAvailability.UNAVAILABLE)
        }
    }

    /**
     * Читает окно дней [days] (по возрастанию, подряд) в поясе [zone] до момента [nowMs].
     * [zone] — учётный пояс (TrustedClock), [nowMs] — доверенное время.
     * Шаги по дням — только агрегатом Health Connect (он сам убирает двойной счёт часов и телефона).
     * Пробежки — сессии бега; дистанция — агрегат за интервал сессии от её же источника,
     * шаги — агрегат за интервал (по суткам), чтобы не оплачивать их второй раз как ходьбу.
     */
    suspend fun read(
        context: Context,
        days: List<Long>,
        zone: ZoneId,
        nowMs: Long,
        readRuns: Boolean,
    ): HealthSnapshot = withContext(Dispatchers.IO) {
        require(days.isNotEmpty())
        val client = HealthConnectClient.getOrCreate(context, PROVIDER_PACKAGE)
        val first = days.first()
        val last = days.last()

        // 1. Шаги по учётным суткам пояса [zone]: по запросу на сутки, границы — точные моменты (Instant).
        //    Группировка по LocalDateTime считала бы сутки в поясе каждой записи, то есть в поясе телефона.
        val stepsByDay = HashMap<Long, Long?>()
        val sourcesByDay = HashMap<Long, Set<String>>()
        for (day in first..last) {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.ofEpochMilli(ActivityMath.dayStartMs(day, zone)),
                        Instant.ofEpochMilli(ActivityMath.dayStartMs(day + 1, zone)),
                    ),
                )
            )
            val steps = result[StepsRecord.COUNT_TOTAL]
            stepsByDay[day] = steps
            if (steps != null) sourcesByDay[day] = result.dataOrigins.mapTo(HashSet()) { it.packageName }
        }

        // 2. Пробежки, задевающие окно. Читаем и сутки до окна: пробежка через полночь, начатая накануне
        //    первого дня, своими шагами в окне — бег, а не ходьба (её дистанция относится к дню начала, вне окна).
        val runs = if (!readRuns) emptyList() else {
            val windowStart = Instant.ofEpochMilli(ActivityMath.dayStartMs(first - ActivityMath.RUNS_LOOKBACK_DAYS, zone))
            val windowEnd = Instant.ofEpochMilli(maxOf(nowMs, ActivityMath.dayStartMs(first, zone) + 1))
            val sessions = ArrayList<ExerciseSessionRecord>()
            var token: String? = null
            do {
                val page = client.readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(windowStart, windowEnd),
                        pageSize = 200,
                        pageToken = token,
                    )
                )
                sessions += page.records
                token = page.pageToken?.takeIf { it.isNotEmpty() }
            } while (token != null && sessions.size < 2_000)

            val candidates = sessions
                .filter { it.exerciseType in RUNNING_TYPES && it.endTime.isAfter(it.startTime) }
                .filter { ActivityMath.runTouchesWindow(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), first, last, zone) }
                .map { s ->
                    val origin = s.metadata.dataOrigin.packageName
                    val distance = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(s.startTime, s.endTime),
                            dataOriginFilter = setOf(DataOrigin(origin)),
                        )
                    )[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
                    RunSession(
                        id = s.metadata.id.ifEmpty { "$origin:${s.startTime.toEpochMilli()}" },
                        startMs = s.startTime.toEpochMilli(),
                        endMs = s.endTime.toEpochMilli(),
                        source = origin,
                        exerciseType = s.exerciseType,
                        distanceM = Math.round(distance.coerceAtLeast(0.0)),
                    )
                }

            // Шаги считаем только для оставшихся после сведения дублей.
            ActivityMath.mergeOverlapping(candidates).map { r ->
                val byDay = LinkedHashMap<Long, Long>()
                for (piece in ActivityMath.dayPieces(r.startMs, r.endMs, zone)) {
                    byDay[piece.day] = stepsBetween(client, piece.startMs, piece.endMs)
                }
                r.copy(steps = byDay.values.sum(), stepsByDay = byDay)
            }
        }

        HealthSnapshot(stepsByDay, sourcesByDay, runs)
    }

    private suspend fun stepsBetween(client: HealthConnectClient, startMs: Long, endMs: Long): Long =
        client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs)),
            )
        )[StepsRecord.COUNT_TOTAL] ?: 0L

    // ---------- Переходы в Health Connect и магазин ----------

    /** Открыть Health Connect (на Android 14+ — системный раздел). Не нашлось — false. */
    fun openHealthConnect(context: Context): Boolean {
        val intents = listOf(
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
            HealthConnectClient.getHealthConnectManageDataIntent(context, PROVIDER_PACKAGE),
        )
        return intents.any { startSafely(context, it) }
    }

    /** Страница Health Connect в Google Play — поставить или обновить. */
    fun openStore(context: Context): Boolean = startSafely(
        context,
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"))
            .setPackage("com.android.vending")
            .putExtra("overlay", true)
            .putExtra("callerId", context.packageName),
    ) || startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PROVIDER_PACKAGE")))

    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
