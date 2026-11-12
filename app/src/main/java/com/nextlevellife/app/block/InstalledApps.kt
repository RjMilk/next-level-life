package com.nextlevellife.app.block

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystem: Boolean,
)

object InstalledApps {

    /**
     * Известные «залипалки»: соцсети, видео и браузеры (через браузер легко обойти блокировку
     * приложения). Блокируются по умолчанию; всё остальное пользователь добавляет сам.
     * Чёрный список, а не белый: иначе блокируются будильник, банки, навигатор и т. п.
     */
    val defaultBlockedPackages = setOf(
        // Видео
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "ru.rutube.app",
        "com.zhiliaoapp.musically", // TikTok
        "com.ss.android.ugc.trill", // TikTok
        "com.yappy.app",
        "video.like", // Likee
        "tv.twitch.android.app",
        // Соцсети
        "com.instagram.android",
        "com.vkontakte.android",
        "com.facebook.katana",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.pinterest",
        "com.snapchat.android",
        "ru.ok.android",
        "com.threads.android",
        // Браузеры
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.yandex.browser",
        "com.opera.browser",
        "com.microsoft.emmx",
        "com.brave.browser",
        "net.quetta.browser",
    )

    fun launchable(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map {
                AppEntry(
                    packageName = it.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm),
                    isSystem = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Стартовый набор: только установленные приложения из [defaultBlockedPackages]. */
    fun defaultBlocked(apps: List<AppEntry>): Set<String> = apps
        .map { it.packageName }
        .filter { it in defaultBlockedPackages }
        .toSet()
}
