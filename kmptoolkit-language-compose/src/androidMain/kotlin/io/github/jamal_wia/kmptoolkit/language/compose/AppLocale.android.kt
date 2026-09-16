package io.github.jamal_wia.kmptoolkit.language.compose

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.jamal_wia.kmptoolkit.language.AppLanguage
import io.github.jamal_wia.kmptoolkit.language.applyLanguageGlobally
import java.util.Locale

// NonObservableLocale fires on the Locale.getDefault() read below. The lint check is about reading
// the locale to *render* with, where a non-observable read leaves the UI stale; this read exists to
// detect that the platform has overwritten the default behind us, which an observable API cannot
// report — it would report the value this function is about to set. Reading it non-observably is the
// check, not a bug in it.
//
// AppBundleLocaleChanges fires on the LocaleList write and is aimed at an app shipping a
// language-split App Bundle. Whether a consumer does that, and whether they call Play Core to fetch
// a language split first, is theirs to decide; a library cannot answer it for them.
@Suppress("NonObservableLocale", "AppBundleLocaleChanges")
@Composable
internal actual fun PlatformAppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    val baseContext: Context = LocalContext.current
    val baseConfiguration: Configuration = LocalConfiguration.current

    val targetLocale: Locale = remember(language.code, baseConfiguration) {
        // The same resolution applyLanguageGlobally performs; needed here as a value, to compare
        // against what is currently in force.
        language.code?.let(Locale::forLanguageTag) ?: deviceLocale()
    }

    // Android resets the process-global default locale to the device's on every configuration
    // delivery: ResourcesManager applies the new Configuration and calls LocaleList.setDefault
    // itself. Compose Multiplatform resolves every string resource through that default, and caches
    // the resolved environment per composable.
    //
    // This re-pin MUST run synchronously in the composable body, before content() composes. A
    // SideEffect would fire after children had already cached the wrong environment, and nothing
    // would invalidate it until the next unrelated recomposition.
    //
    // Both defaults are checked because they can disagree: the framework's rebuild can leave
    // Locale.getDefault() on the chosen locale while LocaleList.getDefault() — the one a string
    // resource actually reads — still starts with the device's, when the device list carries the
    // chosen locale at a later index.
    if (Locale.getDefault() != targetLocale || LocaleList.getDefault()[0] != targetLocale) {
        applyLanguageGlobally(language)
    }

    // A full copy of the base configuration, not the locale-only override used for a long-lived
    // context: LocalConfiguration is read as a complete configuration, and this one is scoped to a
    // composition inside an activity that is recreated on every configuration change anyway, so
    // nothing here outlives the values it copied.
    val localizedConfiguration: Configuration = remember(targetLocale, baseConfiguration) {
        Configuration(baseConfiguration).apply {
            setLocale(targetLocale)
            setLocales(LocaleList(targetLocale))
        }
    }

    val localizedContext: Context = remember(localizedConfiguration, baseContext) {
        LocalizedResourcesContext(
            base = baseContext,
            localizedResources = baseContext.createConfigurationContext(localizedConfiguration).resources,
        )
    }

    CompositionLocalProvider(
        LocalConfiguration provides localizedConfiguration,
        LocalContext provides localizedContext,
        content = content,
    )
}

/**
 * [base] — normally the host activity — with only its resources swapped for localized ones.
 *
 * `createConfigurationContext` alone is not a substitute. On an activity it delegates to the
 * underlying `ContextImpl`, so what it returns is neither the activity nor a wrapper around it, and it
 * carries the device-default theme rather than the activity's. Provided as `LocalContext`, that would
 * make `LocalActivity` and every find-the-activity walk return null, and inflate any Android View
 * hosted in Compose — a video player, a map — under the wrong theme. Wrapping keeps the activity at
 * the end of the chain and its theme in effect, and only strings and other resources resolve under
 * the chosen language.
 */
private class LocalizedResourcesContext(
    base: Context,
    private val localizedResources: Resources,
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResources
}

private fun deviceLocale(): Locale {
    val locales: LocaleList = Resources.getSystem().configuration.locales
    return if (locales.isEmpty) Locale.getDefault() else locales[0]
}
