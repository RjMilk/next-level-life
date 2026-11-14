package com.nextlevellife.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMathTest {
    private val base = "https://github.com/RjMilk/next-level-life/releases/download"
    private val hex = "a".repeat(64)

    /** Ответ /releases в форме GitHub (лишние поля, вложенные объекты, escape-последовательности). */
    private val json = """
        [
          {
            "url": "https://api.github.com/repos/RjMilk/next-level-life/releases/3",
            "id": 3, "tag_name": "v0.8.0", "name": "Черновик", "draft": true, "prerelease": true,
            "body": "", "assets": [
              {"id": 30, "name": "NextLevelLife-0.8.0.apk", "size": 80000000,
               "browser_download_url": "$base/v0.8.0/NextLevelLife-0.8.0.apk"}
            ]
          },
          {
            "id": 2, "tag_name": "v0.7.2", "name": "Версия 0.7.2", "draft": false, "prerelease": true,
            "author": {"login": "RjMilk", "id": 1, "site_admin": false},
            "body": "## Что нового\r\n- **Режим столика** для Fold\r\n- Обновления «из GitHub»\r\n",
            "created_at": "2026-11-14T10:00:00Z",
            "assets": [
              {"id": 21, "name": "NextLevelLife-0.7.2.apk", "size": 81234567, "content_type": "application/vnd.android.package-archive",
               "digest": "sha256:${hex.uppercase()}", "uploader": null,
               "browser_download_url": "$base/v0.7.2/NextLevelLife-0.7.2.apk"},
              {"id": 22, "name": "NextLevelLife-0.7.2.apk.sha256", "size": 90, "digest": null,
               "browser_download_url": "$base/v0.7.2/NextLevelLife-0.7.2.apk.sha256"}
            ]
          },
          {
            "id": 1, "tag_name": "v0.7.1", "name": "Версия 0.7.1", "draft": false, "prerelease": true,
            "body": null, "reactions": {"+1": 2, "rate": 1.5e0},
            "assets": [
              {"id": 11, "name": "NextLevelLife-0.7.1.apk", "size": 80000000,
               "browser_download_url": "$base/v0.7.1/NextLevelLife-0.7.1.apk"}
            ]
          }
        ]
    """.trimIndent()

    // ---------- Разбор и выбор релиза ----------

    @Test
    fun `разбор списка релизов`() {
        val rs = UpdateMath.parseReleases(json)
        assertEquals(listOf(3L, 2L, 1L), rs.map { it.id })
        val r = rs[1]
        assertEquals("v0.7.2", r.tag)
        assertTrue(r.prerelease)
        assertFalse(r.draft)
        assertEquals(2, r.assets.size)
        assertEquals(81234567L, r.assets[0].size)
        assertEquals("sha256:${hex.uppercase()}", r.assets[0].digest)
        assertNull(r.assets[1].digest)
        assertTrue(r.body.contains("«из GitHub»"))
        assertEquals("", rs[2].body)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `не массив — ошибка разбора`() {
        UpdateMath.parseReleases("""{"message": "Not Found"}""")
    }

    @Test
    fun `черновик пропускается, pre-release берётся, файл суммы находится`() {
        val c = UpdateMath.pickRelease(UpdateMath.parseReleases(json))!!
        assertEquals(AppVersion(0, 7, 2), c.version)
        assertEquals(21L, c.apk.id)
        assertEquals(22L, c.checksum?.id)
    }

    @Test
    fun `выбирается наибольшая версия, а не первая в списке`() {
        val old = release(5, "v0.6.5")
        val new = release(4, "v0.7.0")
        assertEquals(AppVersion(0, 7, 0), UpdateMath.pickRelease(listOf(old, new))?.version)
    }

    @Test
    fun `релиз без APK или с чужой ссылкой не подходит`() {
        assertNull(UpdateMath.candidateOf(release(1, "v0.7.0", assets = emptyList())))
        val foreign = ReleaseAsset(1, "NextLevelLife-0.7.0.apk", 100, "https://evil.example/NextLevelLife-0.7.0.apk")
        assertNull(UpdateMath.candidateOf(release(1, "v0.7.0", assets = listOf(foreign))))
        val otherRepo = ReleaseAsset(1, "NextLevelLife-0.7.0.apk", 100, "https://github.com/other/repo/releases/download/v0.7.0/NextLevelLife-0.7.0.apk")
        assertNull(UpdateMath.candidateOf(release(1, "v0.7.0", assets = listOf(otherRepo))))
        assertNull(UpdateMath.candidateOf(release(1, "nightly")))
    }

    @Test
    fun `имя APK не по тегу — берётся единственный APK приложения`() {
        val a = ReleaseAsset(7, "NextLevelLife-0.7.0-fix.apk", 100, "$base/v0.7.0/NextLevelLife-0.7.0-fix.apk")
        assertEquals(7L, UpdateMath.candidateOf(release(1, "v0.7.0", assets = listOf(a)))?.apk?.id)
        val b = ReleaseAsset(8, "NextLevelLife-other.apk", 100, "$base/v0.7.0/NextLevelLife-other.apk")
        val c = ReleaseAsset(9, "NextLevelLife-0.7.0b.apk", 100, "$base/v0.7.0/NextLevelLife-0.7.0b.apk")
        assertNull(UpdateMath.candidateOf(release(1, "v0.7.0", assets = listOf(c, b.copy(name = "NextLevelLife-1.apk")))))
    }

    @Test
    fun `пустой или огромный файл не подходит`() {
        val empty = ReleaseAsset(1, "NextLevelLife-0.7.0.apk", 0, "$base/v0.7.0/NextLevelLife-0.7.0.apk")
        assertNull(UpdateMath.candidateOf(release(1, "v0.7.0", assets = listOf(empty))))
        val huge = empty.copy(size = UpdateConfig.MAX_APK_BYTES + 1)
        assertNull(UpdateMath.candidateOf(release(1, "v0.7.0", assets = listOf(huge))))
    }

    @Test
    fun `ссылки на скачивание`() {
        assertTrue(UpdateMath.isTrustedDownloadUrl("$base/v0.7.2/NextLevelLife-0.7.2.apk"))
        assertFalse(UpdateMath.isTrustedDownloadUrl("http://github.com/RjMilk/next-level-life/releases/download/v1/a.apk"))
        assertFalse(UpdateMath.isTrustedDownloadUrl("$base/../../x/a.apk"))
        assertFalse(UpdateMath.isTrustedDownloadUrl("$base/"))
        assertFalse(UpdateMath.isTrustedDownloadUrl("https://github.com/RjMilk/next-level-life.evil/releases/download/a.apk"))
    }

    // ---------- Версии ----------

    @Test
    fun `разбор версий`() {
        assertEquals(AppVersion(0, 7, 1), AppVersion.parse("v0.7.1"))
        assertEquals(AppVersion(0, 7, 1), AppVersion.parse("0.7.1"))
        assertEquals(AppVersion(1, 0, 0), AppVersion.parse("V1"))
        assertEquals(AppVersion(0, 8, 0), AppVersion.parse("0.8-beta1"))
        assertEquals(AppVersion(2, 10, 3), AppVersion.parse("2.10.3+build5"))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("1.2.3.4"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse(null))
    }

    @Test
    fun `сравнение семантическое, а не строковое`() {
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.9")!!)
        assertTrue(UpdateMath.isNewer(AppVersion(0, 7, 2), AppVersion(0, 7, 1)))
        assertFalse(UpdateMath.isNewer(AppVersion(0, 7, 1), AppVersion(0, 7, 1)))
        assertFalse(UpdateMath.isNewer(AppVersion(0, 6, 9), AppVersion(0, 7, 1)))
        assertTrue(UpdateMath.isNewer(AppVersion(0, 6, 9), null))
    }

    // ---------- Контрольные суммы ----------

    @Test
    fun `строка sha256sum`() {
        val l = UpdateMath.parseSha256File("${hex.uppercase()}  NextLevelLife-0.7.2.apk\n", "NextLevelLife-0.7.2.apk")
        assertEquals(Sha256Line(hex, "NextLevelLife-0.7.2.apk"), l)
        // Двоичный режим «*имя», без имени, с путём.
        assertEquals(hex, UpdateMath.parseSha256File("$hex *NextLevelLife-0.7.2.apk", "NextLevelLife-0.7.2.apk")?.hex)
        assertEquals(hex, UpdateMath.parseSha256File(hex, "NextLevelLife-0.7.2.apk")?.hex)
        assertEquals(hex, UpdateMath.parseSha256File("$hex  build/NextLevelLife-0.7.2.apk", "NextLevelLife-0.7.2.apk")?.hex)
    }

    @Test
    fun `чужое имя, короткий хеш и мусор не принимаются`() {
        assertNull(UpdateMath.parseSha256File("$hex  Other.apk", "NextLevelLife-0.7.2.apk"))
        assertNull(UpdateMath.parseSha256File("abc  NextLevelLife-0.7.2.apk", "NextLevelLife-0.7.2.apk"))
        assertNull(UpdateMath.parseSha256File("", "NextLevelLife-0.7.2.apk"))
        assertNull(UpdateMath.parseSha256File("<html>Not Found</html>", "NextLevelLife-0.7.2.apk"))
    }

    @Test
    fun `несколько строк — берётся строка нужного файла`() {
        val b = "b".repeat(64)
        val text = "# суммы\n$b  NextLevelLife-0.7.1.apk\r\n$hex  NextLevelLife-0.7.2.apk\r\n"
        assertEquals(hex, UpdateMath.parseSha256File(text, "NextLevelLife-0.7.2.apk")?.hex)
    }

    @Test
    fun `поле digest из API`() {
        assertEquals(hex, UpdateMath.digestHex("sha256:${hex.uppercase()}"))
        assertNull(UpdateMath.digestHex("sha512:$hex"))
        assertNull(UpdateMath.digestHex("sha256:abc"))
        assertNull(UpdateMath.digestHex(null))
    }

    // ---------- Проверка APK ----------

    private val cert = UpdateMath.normalizeHex(UpdateConfig.KNOWN_CERT_SHA256)
    private val installed = InstalledFacts(versionCode = 12, signers = setOf(cert))
    private fun archive(
        pkg: String = UpdateConfig.PACKAGE,
        code: Long = 13,
        signers: Set<String> = setOf(cert),
        lineage: Set<String> = signers,
    ) = ArchiveFacts(pkg, code, "0.7.2", signers, lineage)

    @Test
    fun `тот же пакет, та же подпись, код версии больше — можно`() {
        assertEquals(VerifyResult.Ok(knownCert = true), UpdateMath.verifyArchive(archive(), installed))
        assertEquals(64, cert.length)
    }

    @Test
    fun `отказы с понятной причиной`() {
        assertTrue(UpdateMath.verifyArchive(null, installed) is VerifyResult.Rejected)
        assertTrue((UpdateMath.verifyArchive(archive(pkg = "com.evil"), installed) as VerifyResult.Rejected).reason.contains("com.evil"))
        assertTrue(UpdateMath.verifyArchive(archive(code = 12), installed) is VerifyResult.Rejected)
        assertTrue(UpdateMath.verifyArchive(archive(code = 5), installed) is VerifyResult.Rejected)
        assertTrue(UpdateMath.verifyArchive(archive(signers = emptySet()), installed) is VerifyResult.Rejected)
        val other = UpdateMath.verifyArchive(archive(signers = setOf("c".repeat(64))), installed)
        assertEquals(VerifyResult.Rejected("подпись не совпадает с установленным приложением"), other)
        assertTrue(UpdateMath.verifyArchive(archive(), installed.copy(signers = emptySet())) is VerifyResult.Rejected)
    }

    @Test
    fun `новый ключ с доказанной ротацией от установленного — можно`() {
        val next = "d".repeat(64)
        val r = UpdateMath.verifyArchive(archive(signers = setOf(next), lineage = setOf(cert, next)), installed)
        assertEquals(VerifyResult.Ok(knownCert = false), r)
    }

    @Test
    fun `сборка с другого компьютера — совпадает с установленной, но не с известным сертификатом`() {
        val mine = "e".repeat(64)
        val r = UpdateMath.verifyArchive(archive(signers = setOf(mine)), installed.copy(signers = setOf(mine)))
        assertEquals(VerifyResult.Ok(knownCert = false), r)
    }

    // ---------- Расписание и лимиты ----------

    private val day = UpdateConfig.AUTO_INTERVAL_MS
    private val hour = UpdateConfig.AUTO_RETRY_MS

    @Test
    fun `автопроверка раз в сутки`() {
        val now = 1_000L * day
        assertTrue(UpdateMath.shouldAutoCheck(now, 0L, 0L, 0L))
        assertFalse(UpdateMath.shouldAutoCheck(now, now - day + 1, now - day + 1, 0L))
        assertTrue(UpdateMath.shouldAutoCheck(now, now - day, now - day, 0L))
    }

    @Test
    fun `после неудачи — не раньше чем через час, при лимите — не раньше его конца`() {
        val now = 1_000L * day
        assertFalse(UpdateMath.shouldAutoCheck(now, now - 2 * day, now - hour + 1, 0L))
        assertTrue(UpdateMath.shouldAutoCheck(now, now - 2 * day, now - hour, 0L))
        assertFalse(UpdateMath.shouldAutoCheck(now, 0L, 0L, now + 1))
    }

    @Test
    fun `часы назад не блокируют проверку`() {
        val now = 1_000L * day
        assertTrue(UpdateMath.shouldAutoCheck(now, now + day, now + day, 0L))
    }

    @Test
    fun `ограничение частоты GitHub`() {
        val now = 1_000_000_000_000L
        // Первичный лимит: остаток 0, сброс по X-RateLimit-Reset.
        assertEquals(now + 1_800_000L, UpdateMath.rateLimitUntil(now, 403, "0", "${(now + 1_800_000L) / 1000}", null, false))
        // Retry-After важнее.
        assertEquals(now + 120_000L, UpdateMath.rateLimitUntil(now, 429, "0", "${now / 1000 + 9999}", "120", false))
        // Сброс уже прошёл — всё равно не раньше минуты.
        assertEquals(now + 60_000L, UpdateMath.rateLimitUntil(now, 403, "0", "${now / 1000 - 10}", null, false))
        // Вторичный лимит без заголовков.
        assertEquals(now + 300_000L, UpdateMath.rateLimitUntil(now, 429, null, null, null, false))
        assertEquals(now + 300_000L, UpdateMath.rateLimitUntil(now, 403, "12", null, null, true))
        // Обычный 403 и другие коды — не лимит.
        assertNull(UpdateMath.rateLimitUntil(now, 403, "12", null, null, false))
        assertNull(UpdateMath.rateLimitUntil(now, 500, "0", null, "10", true))
    }

    // ---------- Текст ----------

    @Test
    fun `описание релиза простым текстом`() {
        val body = "## Что нового\r\n\r\n- **Режим столика** для [Fold](https://x.y)\n* `код`\n![img](a.png)\n> цитата\n---\n<b>жирно</b>"
        assertEquals("Что нового\n• Режим столика для Fold\n• код\nцитата\nжирно", UpdateMath.releaseNotes(body))
    }

    @Test
    fun `описание обрезается до 10 строк, код-блоки без разметки`() {
        val body = (1..15).joinToString("\n") { "строка $it" }
        val notes = UpdateMath.releaseNotes(body)
        assertEquals(11, notes.lines().size)
        assertEquals("…", notes.lines().last())
        assertEquals("a **b**", UpdateMath.releaseNotes("```\na **b**\n```"))
        assertEquals("", UpdateMath.releaseNotes(""))
    }

    @Test
    fun `размер в мегабайтах`() {
        assertEquals("77,5 МБ", UpdateMath.sizeText(81234567L))
        assertEquals("0,0 МБ", UpdateMath.sizeText(0L))
        assertEquals("1,0 МБ", UpdateMath.sizeText(1_048_576L))
    }

    @Test
    fun `итоги установки по-русски`() {
        assertEquals("Обновление установлено", UpdateMath.installStatusText(0, null))
        assertEquals("Установка отменена", UpdateMath.installStatusText(3, null))
        assertTrue(UpdateMath.installStatusText(6, null).startsWith("Не хватает места"))
        assertEquals("Установка не удалась: INSTALL_FAILED_X", UpdateMath.installStatusText(1, "INSTALL_FAILED_X"))
        assertEquals("Установка не удалась", UpdateMath.installStatusText(1, " "))
    }

    private fun release(id: Long, tag: String, assets: List<ReleaseAsset>? = null): Release {
        val v = tag.removePrefix("v")
        return Release(
            id = id, tag = tag, name = tag, body = "", draft = false, prerelease = true,
            assets = assets ?: listOf(ReleaseAsset(id * 10, "NextLevelLife-$v.apk", 100, "$base/$tag/NextLevelLife-$v.apk")),
        )
    }
}
