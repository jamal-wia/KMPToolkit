package io.github.jamal_wia.kmptoolkit.settings

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import platform.Foundation.NSUserDefaults

/**
 * The iOS applier writes one key in one domain, and both halves matter: the wrong domain is
 * invisible to `NSBundle`, and writing the current system language instead of removing the key
 * would freeze the app at today's system language forever.
 *
 * Driven against a private suite rather than `standardUserDefaults` so the test never touches the
 * simulator-wide preference the rest of the suite runs under.
 */
class IosLanguageApplierTest {

    private val suiteName = "io.github.jamal_wia.kmptoolkit.settings.test"
    private val defaults: NSUserDefaults = NSUserDefaults(suiteName = suiteName)
    private val applier: LanguageApplier = IosLanguageApplier(defaults)

    @AfterTest
    fun clearSuite() {
        defaults.removePersistentDomainForName(suiteName)
    }

    @Test
    fun `a language is written to the top of AppleLanguages`() {
        applier.apply(LanguageTag("pt-BR"))

        assertEquals(listOf("pt-BR"), defaults.arrayForKey("AppleLanguages"))
    }

    @Test
    fun `a later language replaces the earlier one rather than being appended`() {
        applier.apply(LanguageTag("pt-BR"))

        applier.apply(LanguageTag("de"))

        assertEquals(listOf("de"), defaults.arrayForKey("AppleLanguages"))
    }

    @Test
    fun `following the system removes the key instead of pinning the current language`() {
        applier.apply(LanguageTag("pt-BR"))

        applier.apply(null)

        // Asserted against this domain's own contents rather than against arrayForKey: a read
        // falls through to NSGlobalDomain, where the device's own AppleLanguages lives — and that
        // fall-through is precisely the mechanism that makes the app follow the system again once
        // the app-level override is gone.
        assertNull(
            ownAppleLanguages(),
            "the key's absence is what keeps the app following the system when the user changes " +
                "it in Settings",
        )
    }

    @Test
    fun `following the system twice is a no-op rather than an error`() {
        applier.apply(null)
        applier.apply(null)

        assertNull(ownAppleLanguages())
    }

    /** What this suite itself stores under `AppleLanguages`, ignoring the domains it inherits. */
    private fun ownAppleLanguages(): Any? =
        defaults.persistentDomainForName(suiteName)?.get("AppleLanguages")
}
