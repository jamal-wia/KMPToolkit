package io.github.jamal_wia.kmptoolkit.language

import android.content.Context
import android.content.res.Resources
import java.util.Locale

/**
 * The `Application` context's [Resources], resolved under the chosen in-app language.
 *
 * Install it by overriding `Application.getResources()`. Without it, an in-app language silently
 * reverts to the device's on certain configuration changes, and the app looks like it forgot the
 * setting while the settings screen still shows it.
 *
 * ```kotlin
 * class MyApplication : Application() {
 *     private val localizedResources = LocalizedApplicationResources()
 *
 *     override fun getResources(): Resources = localizedResources.resourcesOf(baseContext)
 *
 *     override fun onCreate() {
 *         super.onCreate()
 *         val holder: AppLanguageHolder = // …however your app builds it
 *         localizedResources.readLanguageFrom { holder.language }
 *     }
 * }
 * ```
 *
 * ### Why the Application, and not only the Activity
 *
 * Compose Multiplatform reads the language for every string resource from the process-global
 * `LocaleList.getDefault()`, and Android rebuilds that default on **every** configuration delivery
 * to the process (`ActivityThread` / `ConfigurationController.updateLocaleListFromAppContext`). The
 * rebuild takes the first locale of the **application** context's resources and looks it up in the
 * device's own locale list: found, and that entry is promoted; not found, and it is pushed to the
 * front. Either way the winner is decided by what `Application.getResources()` returns.
 *
 * With plain application resources that first locale is the device's, so each delivery quietly
 * flips the process back to the system language. Deliveries that recreate the activity are repaired
 * by an `attachBaseContext` override — but the ones that do **not** recreate it leave nothing to
 * repair them: a 180° rotation, a window resize that stays inside the same size bucket, an external
 * display appearing. The next screen to compose then comes up in the wrong language.
 *
 * Serving application resources that already carry the chosen locale turns that same rebuild into a
 * re-assertion of it. It is the reason this class exists, and the reason it hooks the `Application`
 * rather than each activity.
 */
public class LocalizedApplicationResources {

    private class Localized(val locale: Locale, val resources: Resources)

    @Volatile
    private var languageSource: (() -> AppLanguage)? = null

    @Volatile
    private var localized: Localized? = null

    /**
     * Tells this object where to read the current language from.
     *
     * Not a subscription: nothing is pushed here later. [source] is asked again on every
     * [resourcesOf] call, so a language change takes effect on the next lookup with no further
     * wiring, and [AppLanguage.System] keeps following the device as it changes.
     *
     * It is a callback rather than a constructor parameter because of when each is available: the
     * `Application` field has to exist before `getResources()` can be called at all, while the
     * persisted language is typically not readable until `onCreate` has built whatever storage
     * holds it. Until this runs, [resourcesOf] serves the base resources untouched.
     */
    public fun readLanguageFrom(source: () -> AppLanguage) {
        languageSource = source
    }

    /**
     * The resources to serve right now for [base] — your `Application`'s own base context.
     *
     * Safe to call from any thread: a race at worst builds the same localized resources twice, and
     * both results are equivalent.
     */
    public fun resourcesOf(base: Context): Resources {
        val language: AppLanguage = languageSource?.invoke() ?: return base.resources
        val locale: Locale = language.resolveLocale()
        val current: Localized? = localized
        if (current != null && current.locale == locale) return current.resources
        return Localized(locale, localizedContext(base, locale).resources)
            .also { localized = it }
            .resources
    }
}
