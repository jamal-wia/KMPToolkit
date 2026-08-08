package io.github.jamal_wia.kmptoolkit.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * A [LanguageApplier] backed by the platform's own per-app language support.
 *
 * @param context any context; only the application context is retained, so this never holds an
 *   activity.
 */
public fun createLanguageApplier(context: Context): LanguageApplier =
    AndroidLanguageApplier(context.applicationContext)

/**
 * Per-app language through the framework on Android 13+, and through the JVM/`LocaleList` defaults
 * below it.
 *
 * The split is the whole reason this class exists rather than a one-liner. `LocaleManager`
 * (API 33+) is the real thing: the system stores the choice, shows it in Settings → Apps →
 * Language, recreates the app's activities, and reapplies it on every later launch before any of
 * this library's code runs. Below API 33 the framework has nothing of the sort — the choice lives
 * in this library's own store, and all this can do is set the process defaults, which is why the
 * consumer has to call [LanguageApplier.apply] itself at start-up on those versions.
 *
 * Deliberately not `AppCompatDelegate.setApplicationLocales`, which would give the pre-33 path
 * persistence and activity recreation for free: it would put `androidx.appcompat` — a UI toolkit,
 * with its own resources and its own activity base classes — on the compile classpath of every
 * consumer of a settings library, including the Compose-only apps that have spent effort getting
 * rid of it. A consumer who already uses AppCompat and wants that behaviour can pass a two-line
 * `LanguageApplier { AppCompatDelegate.setApplicationLocales(...) }` lambda instead of this
 * factory; the interface exists precisely so that substitution costs nothing.
 */
internal class AndroidLanguageApplier(private val context: Context) : LanguageApplier {

    override fun apply(language: LanguageTag?) {
        // The API-33 branch is written out here rather than delegated to a private method: lint's
        // NewApi check only follows an SDK_INT guard within one method body, so extracting it
        // would need an androidx.annotation @RequiresApi — a dependency this module otherwise
        // does not have — or a suppression.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager: LocaleManager =
                context.getSystemService(LocaleManager::class.java) ?: return
            manager.applicationLocales = when (language) {
                // An empty list is the framework's own way of spelling "no app-specific language"
                // — it clears the override rather than pinning the current system locale, so the
                // app keeps following the system when the user changes it later.
                null -> LocaleList.getEmptyLocaleList()
                else -> LocaleList.forLanguageTags(language.value)
            }
        } else {
            applyThroughDefaults(language)
        }
    }

    private fun applyThroughDefaults(language: LanguageTag?) {
        val locale: Locale = language
            ?.let { Locale.forLanguageTag(it.value) }
            ?: Resources.getSystem().configuration.locales[0]
        Locale.setDefault(locale)
        // Both, not just Locale.setDefault: resource resolution goes through
        // LocaleList.getDefault() on API 24+, and leaving that at the system locale lets the
        // framework's periodic reapplication of the base Configuration silently win.
        LocaleList.setDefault(LocaleList(locale))
    }
}
