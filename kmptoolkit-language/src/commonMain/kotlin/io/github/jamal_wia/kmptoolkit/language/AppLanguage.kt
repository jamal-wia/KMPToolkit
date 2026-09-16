package io.github.jamal_wia.kmptoolkit.language

/**
 * One language selection for a consumer app.
 *
 * This carries only what every locale-switching decision actually needs — a language tag and its
 * reading direction — and nothing about how to present it to a user. Which languages an app offers,
 * their display names, and any flag or icon are entirely the consumer's choice: baking a fixed
 * catalog of display strings into this module would put user-facing text into a library, which this
 * suite's modules never do. Build your own list of the [AppLanguage] values your app supports,
 * alongside whatever label you want to show for each.
 *
 * @property code BCP-47 / ISO language tag passed to platform locale APIs (`"en"`, `"ar"`,
 *   `"pt-BR"`, …), or `null` to mean [System] — follow the device's own language rather than any one
 *   fixed language.
 * @property isLtr `false` only for a language that reads right-to-left (Arabic, Hebrew, Persian,
 *   Urdu, …). Meaningless when [code] is `null`: a "follow system" selection has no fixed direction
 *   of its own. [getSystemLanguageCode] tells you what the device is actually set to when you need
 *   to resolve one.
 */
public data class AppLanguage(
    val code: String?,
    val isLtr: Boolean,
) {

    public companion object {

        /**
         * Follows the device's own language rather than any one fixed language.
         *
         * [isLtr] here is a placeholder, never meant to be read: resolve [getSystemLanguageCode]
         * against your own supported-language list first if you need the actual direction.
         */
        public val System: AppLanguage = AppLanguage(code = null, isLtr = true)
    }
}
