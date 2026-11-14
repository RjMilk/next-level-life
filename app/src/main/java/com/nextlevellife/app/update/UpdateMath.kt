package com.nextlevellife.app.update

import android.content.pm.PackageInstaller

// ---------------------------------------------------------------------------------------------
// Обновление из релизов GitHub: чистая логика без Android (покрыта тестами)
// ---------------------------------------------------------------------------------------------

/** Откуда и что скачивать, чем подписано. */
internal object UpdateConfig {
    const val REPO = "RjMilk/next-level-life"

    /** Список, а не /releases/latest: latest пропускает pre-release, а сборки публикуются именно так. */
    const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=5"

    /** APK и контрольные суммы скачиваются только отсюда (дальше GitHub перенаправляет на своё хранилище). */
    const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

    const val PACKAGE = "com.nextlevellife.app"

    /** Без личных данных: GitHub требует User-Agent, но имя телефона ему ни к чему. */
    const val USER_AGENT = "NextLevelLife-updater"

    /**
     * SHA-256 сертификата, которым подписаны сборки (debug-ключ компьютера сборки).
     * Главная проверка — совпадение с сертификатом установленного приложения; эта — дополнительная, в журнал.
     */
    const val KNOWN_CERT_SHA256 = "7F:DA:AD:94:3D:85:01:F5:C3:9C:58:CE:E2:38:61:AB:53:1B:72:11:54:B0:48:B2:EA:BE:6D:88:F4:3F:BE:02"

    /** Больше APK не бывает (сейчас ~80 МБ): защита от бесконечной загрузки. */
    const val MAX_APK_BYTES = 400L * 1024 * 1024

    /** Автопроверка — не чаще раза в сутки: лимит GitHub без токена — 60 запросов в час на IP. */
    const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** После неудачной автопроверки (нет сети) — следующая попытка не раньше чем через час. */
    const val AUTO_RETRY_MS = 60L * 60 * 1000
}

/** Версия «X.Y.Z» (из тега «vX.Y.Z» или versionName); суффиксы «-beta», «+1» не учитываются. */
internal data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(text: String?): AppVersion? {
            if (text == null) return null
            val core = text.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val nums = parts.map { p -> p.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return null }
            return AppVersion(nums[0], nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
        }
    }
}

/** Файл релиза. [digest] — «sha256:…» из API, если GitHub его посчитал. */
internal data class ReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val url: String,
    val digest: String? = null,
)

internal data class Release(
    val id: Long,
    val tag: String,
    val name: String,
    val body: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/** Подходящий релиз: версия из тега, APK и (если опубликован) файл контрольной суммы. */
internal data class UpdateCandidate(
    val release: Release,
    val version: AppVersion,
    val apk: ReleaseAsset,
    val checksum: ReleaseAsset?,
)

/** Что известно о скачанном APK (из PackageManager.getPackageArchiveInfo). Подписи — SHA-256 сертификатов, hex. */
internal data class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    /** Текущие подписанты (apkContentsSigners). */
    val signers: Set<String>,
    /** Цепочка ротации ключа (signingCertificateHistory), включая текущий; без ротации — то же, что [signers]. */
    val lineage: Set<String>,
)

/** Установленное приложение: код версии и текущие подписанты. */
internal data class InstalledFacts(val versionCode: Long, val signers: Set<String>)

/** Итог проверки APK перед установкой. */
internal sealed class VerifyResult {
    /** [knownCert] — подписан тем самым сертификатом [UpdateConfig.KNOWN_CERT_SHA256]. */
    data class Ok(val knownCert: Boolean) : VerifyResult()
    data class Rejected(val reason: String) : VerifyResult()
}

/** Строка файла «.sha256» в формате sha256sum: хеш и (необязательно) имя файла. */
internal data class Sha256Line(val hex: String, val fileName: String?)

internal object UpdateMath {
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val APK_NAME = Regex("^NextLevelLife-[0-9][0-9A-Za-z.+-]*\\.apk$")

    // ---------- Список релизов ----------

    /** Разбор ответа /releases. Битые записи пропускаются; ответ не массив — [IllegalArgumentException]. */
    fun parseReleases(json: String): List<Release> {
        val root = MiniJson.parse(json)
        require(root is List<*>) { "ожидался массив релизов" }
        return root.mapNotNull { item -> (item as? Map<*, *>)?.let(::releaseOf) }
    }

    private fun releaseOf(m: Map<*, *>): Release? {
        val id = (m["id"] as? Number)?.toLong() ?: return null
        val tag = m["tag_name"] as? String ?: return null
        val assets = (m["assets"] as? List<*>).orEmpty().mapNotNull { a ->
            val am = a as? Map<*, *> ?: return@mapNotNull null
            ReleaseAsset(
                id = (am["id"] as? Number)?.toLong() ?: return@mapNotNull null,
                name = am["name"] as? String ?: return@mapNotNull null,
                size = (am["size"] as? Number)?.toLong() ?: return@mapNotNull null,
                url = am["browser_download_url"] as? String ?: return@mapNotNull null,
                digest = am["digest"] as? String,
            )
        }
        return Release(
            id = id,
            tag = tag,
            name = m["name"] as? String ?: tag,
            body = m["body"] as? String ?: "",
            draft = m["draft"] as? Boolean ?: false,
            prerelease = m["prerelease"] as? Boolean ?: false,
            assets = assets,
        )
    }

    /** Ожидаемое имя APK: «NextLevelLife-0.6.6.apk». */
    fun apkNameFor(tag: String): String = "NextLevelLife-${tag.trim().removePrefix("v").removePrefix("V")}.apk"

    /** Ссылка на скачивание ведёт в релизы именно этого репозитория, по https, без обходов пути. */
    fun isTrustedDownloadUrl(url: String): Boolean {
        if (!url.startsWith(UpdateConfig.DOWNLOAD_PREFIX)) return false
        val rest = url.removePrefix(UpdateConfig.DOWNLOAD_PREFIX)
        return rest.isNotEmpty() && ".." !in rest && '?' !in rest && '#' !in rest && '\\' !in rest
    }

    /**
     * Релиз как кандидат на обновление: не черновик, версия из тега, APK «NextLevelLife-<версия>.apk»
     * (если такого нет — единственный другой APK с этим префиксом), разумный размер, ссылка из этого репозитория.
     * Файл контрольной суммы — «<имя APK>.sha256» рядом.
     */
    fun candidateOf(r: Release): UpdateCandidate? {
        if (r.draft) return null
        val version = AppVersion.parse(r.tag) ?: return null
        val usable = r.assets.filter { it.size in 1..UpdateConfig.MAX_APK_BYTES && isTrustedDownloadUrl(it.url) }
        val exact = usable.firstOrNull { it.name == apkNameFor(r.tag) }
        val apk = exact ?: usable.filter { APK_NAME.matches(it.name) }.singleOrNull() ?: return null
        val checksum = r.assets.firstOrNull {
            it.name == apk.name + ".sha256" && it.size in 1..4096 && isTrustedDownloadUrl(it.url)
        }
        return UpdateCandidate(r, version, apk, checksum)
    }

    /**
     * Выбор релиза из списка (новые сверху): из подходящих — с наибольшей версией, при равенстве — первый.
     * pre-release подходят: отладочные сборки публикуются именно так.
     */
    fun pickRelease(releases: List<Release>): UpdateCandidate? {
        var best: UpdateCandidate? = null
        for (c in releases.mapNotNull(::candidateOf)) {
            if (best == null || c.version > best.version) best = c
        }
        return best
    }

    /** Версия релиза новее установленной (не разобрать установленную — считаем, что новее). */
    fun isNewer(remote: AppVersion, installed: AppVersion?): Boolean = installed == null || remote > installed

    // ---------- Контрольные суммы ----------

    /**
     * Файл «.sha256» как у sha256sum: «<64 hex> [*]<имя>». Пустые строки и комментарии (#) пропускаются;
     * если строк несколько — берётся строка с именем [apkName]. Не найдено — null.
     */
    fun parseSha256File(text: String, apkName: String): Sha256Line? {
        val lines = text.lineSequence()
            .map { it.trim().removePrefix("﻿") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val hex = line.takeWhile { !it.isWhitespace() }
                if (!HEX64.matches(hex)) return@mapNotNull null
                val name = line.drop(hex.length).trim().removePrefix("*").trim().ifEmpty { null }
                Sha256Line(hex.lowercase(), name)
            }
            .toList()
        lines.firstOrNull { it.fileName == apkName }?.let { return it }
        // Одна строка без имени или с путём, оканчивающимся на имя APK, — тоже годится.
        return lines.singleOrNull()?.takeIf { it.fileName == null || it.fileName.endsWith("/$apkName") || it.fileName == apkName }
    }

    /** Поле digest ассета из API: «sha256:<hex>» → hex в нижнем регистре; другое — null. */
    fun digestHex(digest: String?): String? {
        val d = digest?.trim() ?: return null
        if (!d.startsWith("sha256:", ignoreCase = true)) return null
        return d.substringAfter(':').takeIf { HEX64.matches(it) }?.lowercase()
    }

    /** Сертификат как hex без двоеточий, в нижнем регистре: «7F:DA:…» → «7fda…». */
    fun normalizeHex(s: String): String = s.replace(":", "").replace(" ", "").lowercase()

    // ---------- Проверка APK ----------

    /**
     * APK можно ставить: тот же пакет, versionCode больше установленного, подписан тем же сертификатом,
     * что и установленное приложение (или новым ключом с доказанной ротацией от него).
     */
    fun verifyArchive(archive: ArchiveFacts?, installed: InstalledFacts): VerifyResult {
        if (archive == null) return VerifyResult.Rejected("файл не читается как APK")
        if (archive.packageName != UpdateConfig.PACKAGE) {
            return VerifyResult.Rejected("в файле другое приложение (${archive.packageName})")
        }
        if (archive.versionCode <= installed.versionCode) {
            return VerifyResult.Rejected(
                "сборка не новее установленной (код версии ${archive.versionCode}, установлен ${installed.versionCode})",
            )
        }
        if (archive.signers.isEmpty()) return VerifyResult.Rejected("APK не подписан")
        if (installed.signers.isEmpty()) return VerifyResult.Rejected("не удалось прочитать подпись установленного приложения")
        val same = archive.signers == installed.signers
        val rotated = archive.signers.size == 1 && installed.signers.size == 1 && installed.signers.first() in archive.lineage
        if (!same && !rotated) return VerifyResult.Rejected("подпись не совпадает с установленным приложением")
        return VerifyResult.Ok(knownCert = normalizeHex(UpdateConfig.KNOWN_CERT_SHA256) in archive.signers)
    }

    // ---------- Расписание и лимиты ----------

    /**
     * Пора ли автопроверке: последняя удачная проверка старше суток, последняя попытка — старше часа,
     * ограничение GitHub закончилось. Часы, ушедшие назад, не блокируют проверку навсегда.
     */
    fun shouldAutoCheck(nowMs: Long, lastSuccessMs: Long, lastAttemptMs: Long, limitedUntilMs: Long): Boolean {
        if (nowMs < limitedUntilMs) return false
        val due = lastSuccessMs <= 0L || nowMs < lastSuccessMs || nowMs - lastSuccessMs >= UpdateConfig.AUTO_INTERVAL_MS
        val retryOk = lastAttemptMs <= 0L || nowMs < lastAttemptMs || nowMs - lastAttemptMs >= UpdateConfig.AUTO_RETRY_MS
        return due && retryOk
    }

    /**
     * Ответ GitHub — ограничение частоты? Тогда до какого момента не спрашивать (мс), иначе null.
     * Порядок, как советует GitHub: Retry-After; при X-RateLimit-Remaining = 0 — X-RateLimit-Reset (секунды эпохи);
     * вторичный лимит без заголовков — подождать (не меньше минуты, берём 5 минут).
     */
    fun rateLimitUntil(
        nowMs: Long,
        status: Int,
        remaining: String?,
        resetEpochSec: String?,
        retryAfterSec: String?,
        bodyMentionsRateLimit: Boolean,
    ): Long? {
        if (status != 403 && status != 429) return null
        retryAfterSec?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { return nowMs + it * 1000 }
        if (remaining?.trim() == "0") {
            val reset = resetEpochSec?.trim()?.toLongOrNull()?.times(1000)
            return maxOf(nowMs + 60_000L, reset ?: (nowMs + 60 * 60_000L))
        }
        if (status == 429 || bodyMentionsRateLimit) return nowMs + 5 * 60_000L
        return null
    }

    // ---------- Текст для экрана ----------

    /**
     * Описание релиза (markdown) простым текстом: заголовки и выделение без разметки, пункты — «•»,
     * ссылки — их текст, картинки и HTML-теги убраны. Не больше [maxLines] непустых строк, дальше «…».
     */
    fun releaseNotes(body: String, maxLines: Int = 10): String {
        val out = ArrayList<String>()
        var more = false
        var inCode = false
        for (raw in body.replace("\r\n", "\n").split('\n')) {
            if (raw.trim().startsWith("```")) {
                inCode = !inCode
                continue
            }
            var line = raw.trimEnd()
            if (!inCode) {
                line = line.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
                    .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("**", "").replace("__", "").replace("`", "")
                val t = line.trimStart()
                line = when {
                    t.startsWith("#") -> t.trimStart('#').trim()
                    t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> "• " + t.substring(2).trim()
                    t.startsWith(">") -> t.trimStart('>').trim()
                    Regex("^-{3,}$|^\\*{3,}$|^_{3,}$").matches(t) -> ""
                    else -> t
                }
            }
            if (line.isBlank()) continue
            if (out.size >= maxLines) {
                more = true
                break
            }
            out += line
        }
        return out.joinToString("\n") + if (more) "\n…" else ""
    }

    /** «80,1 МБ». */
    fun sizeText(bytes: Long): String {
        val tenths = (bytes.coerceAtLeast(0L) * 10 + 524_288L) / 1_048_576L
        return "${tenths / 10},${tenths % 10} МБ"
    }

    /** Итог сессии PackageInstaller по-русски ([systemMessage] — пояснение системы, если оно есть). */
    fun installStatusText(status: Int, systemMessage: String?): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "Обновление установлено"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места для установки — освободи память и повтори"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Установка заблокирована системой"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфликт с установленным приложением (подпись или версия)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Сборка несовместима с этим телефоном"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Файл обновления повреждён"
        STATUS_FAILURE_TIMEOUT -> "Установщик не ответил вовремя — попробуй ещё раз"
        else -> "Установка не удалась" + (systemMessage?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    }

    /** PackageInstaller.STATUS_FAILURE_TIMEOUT (API 34): на старых системах не приходит, но число то же. */
    private const val STATUS_FAILURE_TIMEOUT = 8
}
