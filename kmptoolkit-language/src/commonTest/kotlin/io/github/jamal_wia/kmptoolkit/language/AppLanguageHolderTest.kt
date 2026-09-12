package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every holder built here passes `applyGlobally = {}` so these tests exercise only the holder's own
 * logic — never the real platform locale. [applyLanguageGlobally]'s own behavior has its platform
 * tests: `ApplyLanguageGloballyAndroidTest` (Robolectric) and `ApplyLanguageGloballyIosTest`.
 */
class AppLanguageHolderTest {

    private fun holder(
        initial: AppLanguage,
        onLanguageChanged: (AppLanguage) -> Unit = {},
    ): AppLanguageHolder = createAppLanguageHolder(initial, onLanguageChanged, applyGlobally = {})

    @Test
    fun `starts from the given initial language`() {
        val holder = holder(AppLanguage(code = "en", isLtr = true))

        assertEquals(AppLanguage(code = "en", isLtr = true), holder.language)
    }

    @Test
    fun `setLanguage updates the flow`() {
        val holder = holder(AppLanguage.System)

        holder.setLanguage(AppLanguage(code = "ar", isLtr = false))

        assertEquals(AppLanguage(code = "ar", isLtr = false), holder.language)
    }

    @Test
    fun `setLanguage notifies onLanguageChanged with the new language`() {
        val changes = mutableListOf<AppLanguage>()
        val holder = holder(AppLanguage.System, onLanguageChanged = { changes.add(it) })

        holder.setLanguage(AppLanguage(code = "fr", isLtr = true))

        assertEquals(listOf(AppLanguage(code = "fr", isLtr = true)), changes)
    }

    @Test
    fun `onLanguageChanged is not called for the initial language`() {
        val changes = mutableListOf<AppLanguage>()

        holder(AppLanguage(code = "en", isLtr = true), onLanguageChanged = { changes.add(it) })

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `setLanguage with the language already in effect notifies nothing`() {
        val changes = mutableListOf<AppLanguage>()
        val holder = holder(AppLanguage(code = "en", isLtr = true), onLanguageChanged = { changes.add(it) })

        holder.setLanguage(AppLanguage(code = "en", isLtr = true))

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `switching back to System after an explicit language is recorded`() {
        val changes = mutableListOf<AppLanguage>()
        val holder = holder(AppLanguage(code = "de", isLtr = true), onLanguageChanged = { changes.add(it) })

        holder.setLanguage(AppLanguage.System)

        assertEquals(AppLanguage.System, holder.language)
        assertEquals(listOf(AppLanguage.System), changes)
    }

    @Test
    fun `applyGlobally runs on the initial language before the factory returns`() {
        val applied = mutableListOf<AppLanguage>()

        createAppLanguageHolder(AppLanguage(code = "ja", isLtr = true), applyGlobally = { applied.add(it) })

        assertEquals(listOf(AppLanguage(code = "ja", isLtr = true)), applied)
    }

    @Test
    fun `applyGlobally runs on every setLanguage including one that changes nothing`() {
        // The platform default is shared mutable state the OS itself rewrites, so re-asserting it
        // is the point — see AppLanguageHolder.setLanguage.
        val applied = mutableListOf<AppLanguage>()
        val holder = createAppLanguageHolder(AppLanguage.System, applyGlobally = { applied.add(it) })
        applied.clear() // drop the initial application recorded above
        val korean = AppLanguage(code = "ko", isLtr = true)

        holder.setLanguage(korean)
        holder.setLanguage(korean)

        assertEquals(listOf(korean, korean), applied)
    }
}
