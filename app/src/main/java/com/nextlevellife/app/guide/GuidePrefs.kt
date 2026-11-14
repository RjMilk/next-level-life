package com.nextlevellife.app.guide

import com.nextlevellife.app.data.Store

/**
 * Галочка «Больше не показывать»: отдельно для каждого упражнения, в SharedPreferences ([Store.prefs])
 * под ключом `guide_skip:<id>`. [Store.version] не трогается: это настройка показа, не данные.
 * Кнопка ▶ на экране упражнения показывает гид и при включённой галочке.
 */
object GuidePrefs {
    private const val PREFIX = "guide_skip:"

    fun key(exerciseId: String): String = PREFIX + exerciseId

    /** Показ перед упражнением отключён. Store не готов (предпросмотр) — не отключён. */
    fun isSkipped(exerciseId: String): Boolean =
        runCatching { Store.prefs().getBoolean(key(exerciseId), false) }.getOrDefault(false)

    fun setSkipped(exerciseId: String, skip: Boolean) {
        runCatching { Store.prefs().edit().putBoolean(key(exerciseId), skip).apply() }
    }

    /** Показывать ли гид перед упражнением: гид есть и галочка не стоит. */
    fun showBeforeStart(exerciseId: String): Boolean = Guides.of(exerciseId) != null && !isSkipped(exerciseId)
}
