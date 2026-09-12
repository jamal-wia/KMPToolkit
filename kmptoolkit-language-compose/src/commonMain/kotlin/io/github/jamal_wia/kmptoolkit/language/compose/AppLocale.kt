package io.github.jamal_wia.kmptoolkit.language.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.github.jamal_wia.kmptoolkit.language.AppLanguage

/**
 * Makes [content] render in [language]: its reading direction as [LocalLayoutDirection], plus
 * whatever else the platform needs for a string resource to resolve under it.
 *
 * Wrap the whole Compose tree with this once, near the root — above anything that reads
 * [LocalLayoutDirection] or displays localized text:
 *
 * ```kotlin
 * val selected: AppLanguage by languageHolder.languageFlow.collectAsState()
 * AppLocale(language = selected, resolvedLanguage = catalog.resolve(selected)) {
 *     App()
 * }
 * ```
 *
 * @param language the selection itself, including [AppLanguage.System]. This is what the platform
 *   locale is pinned to, and passing `System` is meaningful: it pins the *device's* language, which
 *   is not the same as pinning the language that device happens to be set to today. Pin the latter
 *   and a device language change stops reaching the app.
 * @param resolvedLanguage the same selection with `System` already resolved against your own
 *   supported-language list — `catalog.resolve(language)`. Only [AppLanguage.isLtr] is read from it,
 *   because `System`'s own direction is a placeholder rather than an answer. It deliberately has no
 *   default: defaulting it to [language] would make `AppLocale(selected) { … }` compile and then
 *   read `System.isLtr`, laying out an Arabic device on "follow system" left-to-right with nothing
 *   to indicate anything went wrong. Pass [language] explicitly when you already hold a resolved one.
 */
@Composable
public fun AppLocale(
    language: AppLanguage,
    resolvedLanguage: AppLanguage,
    content: @Composable () -> Unit,
) {
    val layoutDirection: LayoutDirection =
        if (resolvedLanguage.isLtr) LayoutDirection.Ltr else LayoutDirection.Rtl
    PlatformAppLocale(language) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection, content = content)
    }
}

/**
 * The part of making a language take effect that differs per platform.
 *
 * Android has a process-global default locale that the OS itself rewrites, and composition locals
 * carrying a configuration; iOS has neither, and needs the composition torn down instead. See each
 * actual, and `docs/kmptoolkit-language-compose/05-platform-notes.md`.
 */
@Composable
internal expect fun PlatformAppLocale(language: AppLanguage, content: @Composable () -> Unit)
