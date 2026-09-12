package io.github.jamal_wia.kmptoolkit.language

import android.content.Context

/**
 * Wraps [base] with a configuration override that carries only [language]'s locale.
 *
 * Useful from `Activity.attachBaseContext`: an activity started or recreated before
 * [applyLanguageGlobally] has had a chance to run (the very first frame after a process start, or a
 * configuration change delivered before `onCreate`) would otherwise briefly resolve string resources
 * against the device's language instead of the chosen one.
 *
 * ```kotlin
 * override fun attachBaseContext(newBase: Context) {
 *     super.attachBaseContext(localizedContext(newBase, currentLanguage))
 * }
 * ```
 *
 * Returns [base] unchanged when [language] is [AppLanguage.System] — there is nothing to override.
 *
 * The override configuration deliberately carries **only** the locale, with `fontScale` left at `0`
 * — the sentinel `Configuration.updateFrom` treats as "not overriding this field". The context this
 * returns has its override merged onto every later system configuration for as long as it is used,
 * so a real `fontScale` (or any other field) here would freeze dark mode, orientation, or font size
 * at whatever they happened to be when this was called, for the rest of that context's life.
 */
public fun localizedContext(base: Context, language: AppLanguage): Context {
    if (language.code == null) return base
    return localizedContext(base, language.resolveLocale())
}
