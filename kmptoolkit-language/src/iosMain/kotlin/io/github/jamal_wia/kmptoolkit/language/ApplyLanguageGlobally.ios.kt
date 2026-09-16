package io.github.jamal_wia.kmptoolkit.language

import platform.Foundation.NSGlobalDomain
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

public actual fun applyLanguageGlobally(language: AppLanguage) {
    val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
    if (language.code != null) {
        defaults.setObject(listOf(language.code), forKey = APPLE_LANGUAGES_KEY)
    } else {
        defaults.removeObjectForKey(APPLE_LANGUAGES_KEY)
    }
    // synchronize() is deprecated since iOS 12, but is called intentionally to make NSBundle pick
    // up the new language before the next automatic sync point, so a getString() call right after
    // this returns the new locale's strings rather than the previous one.
    @Suppress("DEPRECATION")
    defaults.synchronize()
}

public actual fun getSystemLanguageCode(): String? {
    // NSLocale.preferredLanguages reflects this app's own AppleLanguages override once
    // applyLanguageGlobally has written one, so it would report the chosen UI language rather than
    // the device's. Read AppleLanguages from the global defaults domain instead, which the per-app
    // override does not touch, and fall back to preferredLanguages only when that is unavailable.
    val globalAppleLanguages: List<*>? = NSUserDefaults.standardUserDefaults
        .persistentDomainForName(NSGlobalDomain)
        ?.get(APPLE_LANGUAGES_KEY) as? List<*>
    return globalAppleLanguages?.firstOrNull() as? String
        ?: NSLocale.preferredLanguages.firstOrNull() as? String
}

private const val APPLE_LANGUAGES_KEY = "AppleLanguages"
