package io.github.jamal_wia.kmptoolkit.language.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import io.github.jamal_wia.kmptoolkit.language.AppLanguage
import io.github.jamal_wia.kmptoolkit.language.applyLanguageGlobally
import io.github.jamal_wia.kmptoolkit.language.getSystemLanguageCode
import java.util.Locale

@Composable
internal actual fun PlatformAppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    // The desktop JVM sits between the other two platforms and borrows one mechanism from each.
    //
    // Like Android it has a process-global default locale that string resources resolve through, so
    // the default is re-pinned synchronously, before content composes, whenever something else in the
    // process has moved it. Nothing in the JVM rewrites it the way Android's configuration delivery
    // does — but any library in a desktop process is free to call Locale.setDefault, and the check is
    // a single comparison.
    //
    // Like iOS it has no configuration to provide and no reactive invalidation: a string resource is
    // resolved when its call composes. So the composition itself is the invalidation — keying on the
    // code rebuilds the subtree under the new language, at the cost of its remembered state.
    val targetTag: String? = language.code ?: getSystemLanguageCode()
    if (targetTag != null && Locale.getDefault() != Locale.forLanguageTag(targetTag)) {
        applyLanguageGlobally(language)
    }
    key(language.code) { content() }
}
