package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `docs/kmptoolkit-language/02-getting-started.md` followed literally by a newcomer: each numbered step is run
 * as written (the enum of step 2, the catalog of step 3, the holder with a stored value of step 4, the picker of
 * step 6, the "follow system" resolution of step 7, the direction of step 8) with an in-memory string standing
 * in for the app's storage and `applyGlobally = {}` standing in for the platform. Every sentence the document
 * states about those steps is asserted here, so a sentence that stops being true fails this class.
 */
class GettingStartedWalkthroughTest {

    // Step 2, verbatim.
    private enum class SupportedLanguage(val appLanguage: AppLanguage, val displayName: String) {
        English(AppLanguage("en"), "English"),
        Arabic(AppLanguage("ar"), "Arabic"),
        Portuguese(AppLanguage("pt"), "Portuguese"),
    }

    // Step 3, verbatim, with the device language fixed.
    private fun catalogFor(device: String?): AppLanguageCatalog = createAppLanguageCatalog(
        supported = SupportedLanguage.entries.map { it.appLanguage },
        systemLanguageCode = { device },
    )

    @Test
    fun `step 3 the first entry is the fallback and system is the stored string`() {
        val catalog: AppLanguageCatalog = catalogFor("ja")

        assertEquals(SupportedLanguage.English.appLanguage, catalog.fallback)
        assertEquals("system", catalog.systemId)
        assertEquals("system", catalog.idOf(AppLanguage.System))
        assertEquals(SupportedLanguage.English.appLanguage, catalog.resolveSystemLanguage())
    }

    @Test
    fun `step 2 the system mode is not an entry of the list and arabic reads right to left`() {
        assertTrue(SupportedLanguage.entries.none { it.appLanguage.code == null })
        assertFalse(SupportedLanguage.Arabic.appLanguage.isLtr)
        assertTrue(SupportedLanguage.English.appLanguage.isLtr)
        assertTrue(SupportedLanguage.Portuguese.appLanguage.isLtr)
    }

    @Test
    fun `step 4 first launch with nothing stored follows the system and applies it before the factory returns`() {
        val catalog: AppLanguageCatalog = catalogFor("pt-BR")
        val applied: MutableList<AppLanguage> = mutableListOf()
        var stored: String? = null

        val language: AppLanguageHolder = createAppLanguageHolder(
            initialLanguage = catalog.fromId(stored),
            onLanguageChanged = { picked -> stored = catalog.idOf(picked) },
            applyGlobally = { applied += it },
        )

        assertEquals(AppLanguage.System, language.language)
        assertEquals(listOf(AppLanguage.System), applied)
        assertEquals(null, stored, "the initial language is not reported to onLanguageChanged")
    }

    @Test
    fun `steps 4 and 6 a picked language is stored and survives re-creating the holder`() {
        val catalog: AppLanguageCatalog = catalogFor("ja")
        var stored: String? = null
        fun create(): AppLanguageHolder = createAppLanguageHolder(
            initialLanguage = catalog.fromId(stored),
            onLanguageChanged = { picked -> stored = catalog.idOf(picked) },
            applyGlobally = {},
        )

        val first: AppLanguageHolder = create()
        first.setLanguage(SupportedLanguage.Portuguese.appLanguage)
        assertEquals("pt", stored)

        val second: AppLanguageHolder = create()
        assertEquals(SupportedLanguage.Portuguese.appLanguage, second.language)

        second.setLanguage(AppLanguage.System)
        assertEquals("system", stored)
        assertEquals(AppLanguage.System, create().language)
    }

    @Test
    fun `step 6 set language re-applies every call and notifies only on a change`() {
        val catalog: AppLanguageCatalog = catalogFor("ja")
        val applied: MutableList<AppLanguage> = mutableListOf()
        val changed: MutableList<AppLanguage> = mutableListOf()
        val language: AppLanguageHolder = createAppLanguageHolder(
            initialLanguage = catalog.fromId(null),
            onLanguageChanged = { changed += it },
            applyGlobally = { applied += it },
        )
        applied.clear()

        language.setLanguage(SupportedLanguage.Arabic.appLanguage)
        language.setLanguage(SupportedLanguage.Arabic.appLanguage)

        assertEquals(2, applied.size)
        assertEquals(listOf(SupportedLanguage.Arabic.appLanguage), changed)
        assertEquals(SupportedLanguage.Arabic.appLanguage, language.languageFlow.value)
    }

    @Test
    fun `step 7 follow system truncates one subtag at a time then takes a sibling then the fallback`() {
        // zh-Hans-CN to zh-Hans to zh
        val zh: List<AppLanguage> = listOf(AppLanguage("en"), AppLanguage("zh"))
        assertEquals("zh", createAppLanguageCatalog(zh, systemLanguageCode = { "zh-Hans-CN" }).resolveSystemLanguage().code)
        val zhHans: List<AppLanguage> = listOf(AppLanguage("en"), AppLanguage("zh-Hans"), AppLanguage("zh"))
        assertEquals("zh-Hans", createAppLanguageCatalog(zhHans, systemLanguageCode = { "zh-Hans-CN" }).resolveSystemLanguage().code)
        // pt-BR to pt
        assertEquals("pt", catalogFor("pt-BR").resolveSystemLanguage().code)
        // pt-BR to a same-language same-script sibling pt-PT
        val sibling: List<AppLanguage> = listOf(AppLanguage("en"), AppLanguage("pt-PT"))
        assertEquals("pt-PT", createAppLanguageCatalog(sibling, systemLanguageCode = { "pt-BR" }).resolveSystemLanguage().code)
        // then the fallback
        assertEquals("en", catalogFor("ja").resolveSystemLanguage().code)
    }

    @Test
    fun `steps 7 and 8 the resolved language carries the direction and system itself is a placeholder`() {
        val catalog: AppLanguageCatalog = catalogFor("ar-EG")
        val selected: AppLanguage = AppLanguage.System

        val effective: AppLanguage = catalog.resolve(selected)

        assertEquals("ar", effective.code)
        assertFalse(effective.isLtr)
        assertTrue(selected.isLtr, "System's own isLtr is the documented placeholder")
        assertEquals(SupportedLanguage.English.appLanguage, catalog.resolve(SupportedLanguage.English.appLanguage))
    }
}
