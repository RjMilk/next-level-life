package com.nextlevellife.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Обновление приложения из релизов GitHub: проверка, загрузка во внутренний кеш, проверка контрольной суммы
 * и подписи, установка через сессию [PackageInstaller]. Работает в собственной области корутин: переключение
 * вкладки или поворот не обрывают загрузку. Состояние для экрана — [state].
 *
 * Локальное состояние (не синхронизируется) — в SharedPreferences «update_state»: время проверок, ETag,
 * ограничение GitHub, ожидаемый код версии установки. Ответ со списком релизов лежит в cacheDir/updates
 * (для ответа 304 на запрос с ETag), туда же скачивается APK.
 */
object UpdateManager {
    private const val TAG = "Updater"
    private const val PREFS = "update_state"
    private const val K_LAST_SUCCESS = "last_success_ms"
    private const val K_LAST_ATTEMPT = "last_attempt_ms"
    private const val K_ETAG = "etag"
    private const val K_LIMITED_UNTIL = "limited_until_ms"
    private const val K_PENDING_CODE = "pending_version_code"
    private const val K_PENDING_NAME = "pending_version_name"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_LIST_BYTES = 2 * 1024 * 1024
    private const val ACTION_INSTALL_STATUS = "com.nextlevellife.app.update.INSTALL_STATUS"

    enum class Phase { IDLE, CHECKING, DOWNLOADING, VERIFYING, READY, INSTALLING }

    /** Что показывает раздел «Обновление» в настройках. */
    data class Ui(
        val phase: Phase = Phase.IDLE,
        /** Найдена версия новее установленной. */
        val available: Available? = null,
        /** Последняя удачная проверка (мс эпохи), 0 — ещё не было. */
        val lastCheckMs: Long = 0L,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        /** Итог последнего действия; [isError] — красным, иначе зелёным. */
        val message: String? = null,
        val isError: Boolean = false,
        /** Предупреждение у готового к установке файла («Контрольная сумма не опубликована»). */
        val warning: String? = null,
        /** Система ждёт подтверждения установки, а окно открыть не удалось: кнопка «Подтвердить установку». */
        val confirmIntent: Intent? = null,
    ) {
        val busy: Boolean get() = phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING || phase == Phase.INSTALLING
    }

    /** Найденный релиз: версия, размер, описание (простым текстом, до 10 строк). */
    data class Available(val version: String, val sizeBytes: Long, val notes: String, val title: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state.asStateFlow()

    private var job: Job? = null
    private var candidate: UpdateCandidate? = null
    private var readyFile: File? = null
    private var initialized = false

    // ---------- Установленное приложение ----------

    /** versionName установленного приложения («0.7.1»). */
    fun installedVersionName(context: Context): String = installedInfo(context, 0)?.versionName ?: "—"

    private fun installedInfo(context: Context, flags: Int): PackageInfo? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }
    }.getOrNull()

    /** Можно ли этому приложению ставить пакеты (настройка «Установка неизвестных приложений»). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    // ---------- Проверка ----------

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun updatesDir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Подтянуть сохранённое при первом показе: время проверки, итог прошлой установки, найденный релиз из кеша. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        val p = prefs(app)
        val installedCode = installedInfo(app, 0)?.longVersionCode ?: 0L
        val pendingCode = p.getLong(K_PENDING_CODE, 0L)
        var message: String? = null
        if (pendingCode in 1..installedCode) {
            // Прошлая установка удалась: процесс при обновлении перезапускается, итог сессии сюда не дошёл.
            message = "Обновлено до версии ${p.getString(K_PENDING_NAME, null) ?: installedVersionName(app)}"
            p.edit().remove(K_PENDING_CODE).remove(K_PENDING_NAME).apply()
            clearDownloads(app)
        }
        val cached = runCatching {
            File(updatesDir(app), "releases.json").takeIf { it.exists() }?.readText()?.let { UpdateMath.pickRelease(UpdateMath.parseReleases(it)) }
        }.getOrNull()
        val installed = AppVersion.parse(installedVersionName(app))
        candidate = cached?.takeIf { UpdateMath.isNewer(it.version, installed) }
        _state.update {
            it.copy(
                lastCheckMs = p.getLong(K_LAST_SUCCESS, 0L),
                available = candidate?.let(::availableOf),
                message = message ?: it.message,
                isError = if (message != null) false else it.isError,
            )
        }
    }

    /** Автопроверка при открытии: не чаще раза в сутки (и не раньше часа после неудачной попытки). */
    fun autoCheckIfDue(context: Context) {
        init(context)
        val p = prefs(context)
        val due = UpdateMath.shouldAutoCheck(
            nowMs = System.currentTimeMillis(),
            lastSuccessMs = p.getLong(K_LAST_SUCCESS, 0L),
            lastAttemptMs = p.getLong(K_LAST_ATTEMPT, 0L),
            limitedUntilMs = p.getLong(K_LIMITED_UNTIL, 0L),
        )
        if (due) check(context, manual = false)
    }

    /** Проверка по кнопке ([manual]) или автоматическая (тогда ошибки сети не показываются). */
    fun check(context: Context, manual: Boolean) {
        init(context)
        if (_state.value.busy) return
        val app = context.applicationContext
        val p = prefs(app)
        val now = System.currentTimeMillis()
        val limitedUntil = p.getLong(K_LIMITED_UNTIL, 0L)
        if (now < limitedUntil) {
            if (manual) setMessage("GitHub ограничил запросы с этой сети. Попробуй после ${clock(limitedUntil)}.", error = true)
            return
        }
        _state.update { it.copy(phase = Phase.CHECKING, message = null, isError = false, confirmIntent = null) }
        p.edit().putLong(K_LAST_ATTEMPT, now).apply()
        job = scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { fetchReleases(app) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Нет связи с GitHub", e)
                Fetch.NoNetwork
            } catch (e: Exception) {
                Log.w(TAG, "Ответ GitHub не разобран", e)
                Fetch.Bad
            }
            onFetched(app, outcome, manual)
        }
    }

    private sealed class Fetch {
        data class Body(val json: String) : Fetch()
        data class Limited(val untilMs: Long) : Fetch()
        data class Http(val code: Int) : Fetch()
        data object NoNetwork : Fetch()
        data object Bad : Fetch()
    }

    private fun onFetched(app: Context, outcome: Fetch, manual: Boolean) {
        val p = prefs(app)
        val now = System.currentTimeMillis()
        fun fail(text: String) {
            _state.update { it.copy(phase = Phase.IDLE, message = if (manual) text else it.message, isError = manual || it.isError) }
        }
        when (outcome) {
            is Fetch.NoNetwork -> fail("Нет связи с GitHub. Проверь интернет и попробуй ещё раз.")
            is Fetch.Bad -> fail("Не удалось разобрать ответ GitHub.")
            is Fetch.Http -> fail("GitHub ответил ошибкой ${outcome.code}. Попробуй позже.")
            is Fetch.Limited -> {
                p.edit().putLong(K_LIMITED_UNTIL, outcome.untilMs).apply()
                fail("GitHub ограничил запросы с этой сети (60 в час). Попробуй после ${clock(outcome.untilMs)}.")
            }
            is Fetch.Body -> {
                val releases = try {
                    UpdateMath.parseReleases(outcome.json)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Список релизов не разобран", e)
                    fail("Не удалось разобрать ответ GitHub.")
                    return
                }
                p.edit().putLong(K_LAST_SUCCESS, now).apply()
                val best = UpdateMath.pickRelease(releases)
                val installed = AppVersion.parse(installedVersionName(app))
                candidate = best?.takeIf { UpdateMath.isNewer(it.version, installed) }
                // Скачанный раньше файл годится, только если это APK того же найденного релиза.
                if (readyFile?.name != candidate?.apk?.name) readyFile = null
                val text = when {
                    best == null -> "В последних релизах нет сборки NextLevelLife-….apk."
                    candidate == null -> "Установлена последняя версия."
                    else -> "Доступна версия ${best.version}."
                }
                _state.update {
                    it.copy(
                        phase = if (readyFile != null) Phase.READY else Phase.IDLE,
                        lastCheckMs = now,
                        available = candidate?.let(::availableOf),
                        message = if (manual || candidate != null) text else it.message,
                        isError = best == null && manual,
                        warning = if (readyFile != null) it.warning else null,
                    )
                }
            }
        }
    }

    private fun availableOf(c: UpdateCandidate) = Available(
        version = c.version.toString(),
        sizeBytes = c.apk.size,
        notes = UpdateMath.releaseNotes(c.release.body),
        title = c.release.name,
    )

    /** GET списка релизов с ETag; 304 — берём сохранённый ответ. Вызывать в фоне. */
    private fun fetchReleases(app: Context): Fetch {
        val p = prefs(app)
        val cache = File(updatesDir(app), "releases.json")
        val conn = open(UpdateConfig.RELEASES_URL, accept = "application/vnd.github+json")
        val etag = p.getString(K_ETAG, null)
        if (etag != null && cache.exists()) conn.setRequestProperty("If-None-Match", etag)
        try {
            val code = conn.responseCode
            return when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.use { readBounded(it, MAX_LIST_BYTES) }
                    cache.writeText(body)
                    p.edit().putString(K_ETAG, conn.getHeaderField("ETag")).apply()
                    Fetch.Body(body)
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> Fetch.Body(cache.readText())
                403, 429 -> {
                    val err = runCatching { conn.errorStream?.use { readBounded(it, 64 * 1024) } }.getOrNull().orEmpty()
                    val until = UpdateMath.rateLimitUntil(
                        nowMs = System.currentTimeMillis(),
                        status = code,
                        remaining = conn.getHeaderField("X-RateLimit-Remaining"),
                        resetEpochSec = conn.getHeaderField("X-RateLimit-Reset"),
                        retryAfterSec = conn.getHeaderField("Retry-After"),
                        bodyMentionsRateLimit = err.contains("rate limit", ignoreCase = true),
                    )
                    if (until != null) Fetch.Limited(until) else Fetch.Http(code)
                }
                else -> Fetch.Http(code)
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Загрузка, проверка, установка ----------

    /** «Скачать и установить»: загрузка → контрольная сумма → подпись → установщик (или кнопка «Установить»). */
    fun downloadAndInstall(context: Context) {
        val app = context.applicationContext
        val c = candidate ?: return
        if (_state.value.busy) return
        readyFile?.takeIf { it.exists() }?.let {
            install(app)
            return
        }
        _state.update {
            it.copy(phase = Phase.DOWNLOADING, downloadedBytes = 0L, totalBytes = c.apk.size, message = null, isError = false, warning = null, confirmIntent = null)
        }
        job = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { downloadAndVerify(app, c) }
            } catch (e: CancellationException) {
                clearDownloads(app)
                _state.update { it.copy(phase = Phase.IDLE, message = "Загрузка отменена", isError = false) }
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Загрузка прервалась", e)
                Prepared.Failed("Загрузка прервалась: нет связи или мало места. Попробуй ещё раз.")
            } catch (e: Exception) {
                Log.e(TAG, "Обновление не подготовлено", e)
                Prepared.Failed("Не удалось подготовить обновление: ${e.message ?: e.javaClass.simpleName}")
            }
            when (result) {
                is Prepared.Failed -> {
                    clearDownloads(app)
                    _state.update { it.copy(phase = Phase.IDLE, message = result.reason, isError = true) }
                }
                is Prepared.Ready -> {
                    readyFile = result.file
                    prefs(app).edit()
                        .putLong(K_PENDING_CODE, result.versionCode)
                        .putString(K_PENDING_NAME, c.version.toString())
                        .apply()
                    val warning = if (result.checksumVerified) null else "Контрольная сумма не опубликована: целостность проверена только подписью."
                    _state.update { it.copy(phase = Phase.READY, warning = warning) }
                    // С контрольной суммой и разрешением — сразу в установщик; иначе — кнопкой.
                    if (warning == null && canInstall(app)) install(app)
                }
            }
        }
    }

    /** Отменить загрузку. */
    fun cancel() {
        if (_state.value.phase == Phase.DOWNLOADING) job?.cancel()
    }

    private sealed class Prepared {
        data class Ready(val file: File, val versionCode: Long, val checksumVerified: Boolean) : Prepared()
        data class Failed(val reason: String) : Prepared()
    }

    private suspend fun downloadAndVerify(app: Context, c: UpdateCandidate): Prepared {
        clearDownloads(app)
        val dir = updatesDir(app)

        // 1. Ожидаемая контрольная сумма: файл .sha256 из релиза, иначе поле digest из API.
        val fromFile = c.checksum?.let { asset ->
            val text = open(asset.url, accept = "application/octet-stream").let { conn ->
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        return Prepared.Failed("Не удалось скачать контрольную сумму (код ${conn.responseCode})")
                    }
                    conn.inputStream.use { readBounded(it, 4096) }
                } finally {
                    conn.disconnect()
                }
            }
            UpdateMath.parseSha256File(text, c.apk.name)?.hex
                ?: return Prepared.Failed("Файл контрольной суммы не читается")
        }
        val fromApi = UpdateMath.digestHex(c.apk.digest)
        if (fromFile != null && fromApi != null && fromFile != fromApi) {
            return Prepared.Failed("Контрольные суммы в релизе не совпадают — установка отменена")
        }
        val expected = fromFile ?: fromApi

        // 2. APK во внутренний кеш с подсчётом SHA-256.
        val part = File(dir, c.apk.name + ".part")
        val sha = MessageDigest.getInstance("SHA-256")
        var got = 0L
        val conn = open(c.apk.url, accept = "application/octet-stream")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return Prepared.Failed("GitHub не отдал файл (код ${conn.responseCode})")
            }
            val declared = conn.contentLengthLong
            if (declared >= 0 && declared != c.apk.size) return Prepared.Failed("Размер файла не совпал с объявленным в релизе")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastShown = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        got += n
                        if (got > c.apk.size) return Prepared.Failed("Файл больше объявленного в релизе")
                        sha.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (got - lastShown >= 512 * 1024) {
                            lastShown = got
                            val shown = got
                            _state.update { it.copy(downloadedBytes = shown) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (got != c.apk.size) return Prepared.Failed("Размер файла не совпал с объявленным в релизе")
        _state.update { it.copy(downloadedBytes = got, phase = Phase.VERIFYING) }
        val actual = sha.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && actual != expected) {
            return Prepared.Failed("Контрольная сумма не совпала — файл повреждён или подменён")
        }
        val apk = File(dir, c.apk.name)
        if (!part.renameTo(apk)) return Prepared.Failed("Не удалось сохранить файл обновления")

        // 3. Подпись, пакет, версия.
        val archive = archiveFacts(app, apk)
        val installed = InstalledFacts(
            versionCode = installedInfo(app, 0)?.longVersionCode ?: Long.MAX_VALUE,
            signers = installedSigners(app),
        )
        return when (val v = UpdateMath.verifyArchive(archive, installed)) {
            is VerifyResult.Rejected -> Prepared.Failed("Проверка не прошла: ${v.reason}")
            is VerifyResult.Ok -> {
                if (!v.knownCert) Log.w(TAG, "Сертификат совпадает с установленным, но не с известным ${UpdateConfig.KNOWN_CERT_SHA256}")
                Log.i(TAG, "APK ${archive?.versionName} (${archive?.versionCode}) проверен, sha256=$actual, сумма ${if (expected != null) "сверена" else "не опубликована"}")
                Prepared.Ready(apk, archive!!.versionCode, checksumVerified = expected != null)
            }
        }
    }

    private fun certHex(s: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun installedSigners(app: Context): Set<String> {
        val si = installedInfo(app, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo ?: return emptySet()
        return si.apkContentsSigners.orEmpty().map(::certHex).toSet()
    }

    private fun archiveFacts(app: Context, apk: File): ArchiveFacts? {
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, flags)
        } ?: return null
        val si = info.signingInfo
        val signers = si?.apkContentsSigners.orEmpty().map(::certHex).toSet()
        val lineage = (si?.signingCertificateHistory?.map(::certHex)?.toSet() ?: emptySet()) + signers
        return ArchiveFacts(info.packageName, info.longVersionCode, info.versionName, signers, lineage)
    }

    /** Передать проверенный файл системному установщику (сессия PackageInstaller, итог — в [UpdateInstallReceiver]). */
    fun install(context: Context) {
        val app = context.applicationContext
        val file = readyFile?.takeIf { it.exists() } ?: run {
            _state.update { it.copy(phase = Phase.IDLE, message = "Файл обновления пропал — скачай ещё раз", isError = true) }
            return
        }
        if (!canInstall(app)) {
            _state.update { it.copy(phase = Phase.READY, message = "Разреши установку обновлений из этого приложения", isError = true) }
            return
        }
        _state.update { it.copy(phase = Phase.INSTALLING, message = null, isError = false, confirmIntent = null) }
        job = scope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    commitSession(app, file)
                    null
                } catch (e: IOException) {
                    Log.e(TAG, "Сессия установки не записана", e)
                    if (e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("space", ignoreCase = true)) {
                        "Не хватает места для установки — освободи память и повтори"
                    } else {
                        "Не удалось передать файл установщику: ${e.message ?: "ошибка записи"}"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Установщик недоступен", e)
                    "Установщик недоступен: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (error != null) _state.update { it.copy(phase = Phase.READY, message = error, isError = true) }
        }
    }

    private fun commitSession(app: Context, file: File) {
        val installer = app.packageManager.packageInstaller
        // Брошенные прошлые сессии этого приложения — убрать, чтобы не занимали место.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(UpdateConfig.PACKAGE)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
                // MUTABLE: установщик дописывает в намерение статус; намерение явное (свой приёмник).
                val pending = PendingIntent.getBroadcast(
                    app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Система просит подтверждения: открыть её окно; не вышло (приложение в фоне) — кнопка в настройках. */
    internal fun onUserActionNeeded(context: Context, confirm: Intent?) {
        if (confirm == null) {
            onInstallResult(context, PackageInstaller.STATUS_FAILURE, "нет окна подтверждения")
            return
        }
        val started = runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        _state.update {
            it.copy(
                phase = Phase.READY,
                confirmIntent = if (started) null else confirm,
                message = if (started) "Подтверди установку в окне системы" else "Нажми «Подтвердить установку»",
                isError = false,
            )
        }
    }

    /** Итог сессии установки. Успех обычно не доходит: процесс перезапускается новой версией. */
    internal fun onInstallResult(context: Context, status: Int, systemMessage: String?) {
        Log.i(TAG, "Итог установки: $status $systemMessage")
        val ok = status == PackageInstaller.STATUS_SUCCESS
        if (ok) {
            clearDownloads(context.applicationContext)
            readyFile = null
        }
        _state.update {
            it.copy(
                phase = if (ok || readyFile == null) Phase.IDLE else Phase.READY,
                message = UpdateMath.installStatusText(status, systemMessage),
                isError = !ok,
                confirmIntent = null,
                available = if (ok) null else it.available,
            )
        }
    }

    /** Подтверждение установки из экрана (если система не открыла окно сама). */
    fun confirmFrom(context: Context) {
        val intent = _state.value.confirmIntent ?: return
        _state.update { it.copy(confirmIntent = null) }
        runCatching { context.startActivity(intent) }.onFailure {
            _state.update { s -> s.copy(message = "Не удалось открыть подтверждение — нажми «Установить» ещё раз", isError = true) }
        }
    }

    // ---------- Служебное ----------

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", UpdateConfig.USER_AGENT)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    private fun readBounded(input: InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) throw IOException("ответ больше $max байт")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Удалить скачанные APK и недокачанные части (ответ со списком релизов остаётся). */
    private fun clearDownloads(app: Context) {
        updatesDir(app).listFiles()?.forEach { f ->
            if (f.name.endsWith(".apk") || f.name.endsWith(".part")) f.delete()
        }
        readyFile = null
    }

    private fun setMessage(text: String, error: Boolean) {
        _state.update { it.copy(message = text, isError = error) }
    }

    private fun clock(ms: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}
