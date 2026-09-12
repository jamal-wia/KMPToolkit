package io.github.jamal_wia.kmptoolkit.language.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.github.jamal_wia.kmptoolkit.language.AppLanguage

/**
 * Provides [language]'s reading direction as [LocalLayoutDirection] for [content], and forces a
 * fresh composition of [content] whenever [language]'s code changes.
 *
 * Wrap the whole Compose tree with this once, near the root — above anything that reads
 * [LocalLayoutDirection] or displays localized text:
 *
 * ```kotlin
 * val language by languageHolder.languageFlow.collectAsState()
 * AppLocale(language = resolve(language)) {
 *     App()
 * }
 * ```
 *
 * Pass an already-resolved [language] — one whose [AppLanguage.code] is never `null`.
 * [AppLanguage.System]'s [AppLanguage.isLtr] is a placeholder, not a real direction; resolve it
 * against your own supported-language list first, using
 * [io.github.jamal_wia.kmptoolkit.language.getSystemLanguageCode] when you need the device's actual
 * language.
 *
 * The [key] on [AppLanguage.code] exists because Compose Multiplatform's string-resource lookup
 * reads the active language at the point a `stringResource` call composes, not reactively: without
 * forcing every descendant out of composition and back in on a language change, an already-composed
 * screen would keep showing its previous language's strings until something else happened to
 * recompose it.
 */
@Composable
public fun AppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    val layoutDirection: LayoutDirection =
        if (language.isLtr) LayoutDirection.Ltr else LayoutDirection.Rtl
    key(language.code) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection, content = content)
    }
}
