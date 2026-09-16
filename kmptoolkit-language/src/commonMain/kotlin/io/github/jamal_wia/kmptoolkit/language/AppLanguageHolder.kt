package io.github.jamal_wia.kmptoolkit.language

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive holder for the app's currently selected [AppLanguage].
 *
 * Create one, hold it for as long as your app lives, and pass it to whoever needs to read or change
 * the language. Nothing here is global: two holders in one process would each push their own value
 * onto the platform's shared default locale, and the last write would win.
 *
 * This module has no storage of its own — see [createAppLanguageHolder] — and no Compose dependency;
 * for the layout-direction wiring a language choice implies, see `kmptoolkit-language-compose`.
 */
public interface AppLanguageHolder {

    /** The currently selected language. Distinct values only — see [setLanguage]. */
    public val languageFlow: StateFlow<AppLanguage>

    /** The current language. Shorthand for `languageFlow.value`. */
    public val language: AppLanguage get() = languageFlow.value

    /**
     * Selects [language]: applies it to the platform's default locale via [applyLanguageGlobally],
     * updates [languageFlow], and invokes the `onLanguageChanged` callback given to
     * [createAppLanguageHolder].
     *
     * The platform side effect runs on **every** call, including one passing the language already in
     * effect. It is idempotent, and re-asserting it is the point: the process-wide default locale is
     * shared mutable state that the platform itself rewrites — Android rebuilds it on every
     * configuration delivery — so "the value has not changed here" says nothing about whether it is
     * still in force out there.
     *
     * [languageFlow] and `onLanguageChanged` are change-only: re-selecting the current language
     * emits nothing and persists nothing.
     */
    public fun setLanguage(language: AppLanguage)
}

/**
 * Creates an [AppLanguageHolder].
 *
 * @param initialLanguage the language to start from. This module has no storage of its own, so load
 *   whatever was last persisted — with your own storage, `kmptoolkit-storage` or otherwise — before
 *   calling this, and pass it here. [applyGlobally] is called with this value immediately, before
 *   this function returns.
 * @param onLanguageChanged called with the new language every time [AppLanguageHolder.setLanguage]
 *   actually changes it — your hook to persist the choice for next launch. Not called for
 *   [initialLanguage] itself, and not called when `setLanguage` is passed the language already in
 *   effect.
 * @param applyGlobally the platform side effect to run on [initialLanguage] and on every language
 *   change — [applyLanguageGlobally] by default. Overriding it is only for a test that wants a
 *   holder without touching the real platform locale; production code should leave the default.
 */
public fun createAppLanguageHolder(
    initialLanguage: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit = {},
    applyGlobally: (AppLanguage) -> Unit = ::applyLanguageGlobally,
): AppLanguageHolder = DefaultAppLanguageHolder(initialLanguage, onLanguageChanged, applyGlobally)

private class DefaultAppLanguageHolder(
    initialLanguage: AppLanguage,
    private val onLanguageChanged: (AppLanguage) -> Unit,
    private val applyGlobally: (AppLanguage) -> Unit,
) : AppLanguageHolder {

    private val state: MutableStateFlow<AppLanguage> = MutableStateFlow(initialLanguage)
    override val languageFlow: StateFlow<AppLanguage> = state.asStateFlow()

    init {
        applyGlobally(initialLanguage)
    }

    override fun setLanguage(language: AppLanguage) {
        applyGlobally(language)
        if (state.value == language) return
        state.value = language
        onLanguageChanged(language)
    }
}
