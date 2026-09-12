package io.github.jamal_wia.kmptoolkit.language.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import io.github.jamal_wia.kmptoolkit.language.AppLanguage

@Composable
internal actual fun PlatformAppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    // iOS has no process-global default locale to re-pin and no configuration to provide: the app
    // language lives in NSUserDefaults, which NSBundle consults when a string is looked up. What it
    // does not do is tell an already-composed screen to look again — Compose Multiplatform resolves
    // a string resource at the point the call composes, not reactively.
    //
    // So the composition itself is the invalidation: keying on the code tears every descendant down
    // and builds it again under the new language. It costs the subtree's remembered state, which is
    // the price of a language switch here; Android instead invalidates through LocalConfiguration
    // and keeps that state.
    key(language.code) { content() }
}
