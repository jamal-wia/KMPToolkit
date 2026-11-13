package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Resource budget of the holder: a thousand switches cost one platform call, one flow write and at most one
 * callback each, nothing accumulates between them, and the flow keeps replaying exactly the latest value (a late
 * observer sees only the last selection, the earlier ones are conflated away).
 */
class HolderResourceBudgetTest {

    private val languages: List<AppLanguage> = listOf("en", "ar", "pt-BR", "zh-Hans", "he").map { AppLanguage(it) }

    @Test
    fun `a thousand switches call the platform once each and notify once per actual change`() {
        var applied = 0
        val changes: MutableList<AppLanguage> = mutableListOf()
        val holder: AppLanguageHolder = createAppLanguageHolder(
            initialLanguage = AppLanguage.System,
            onLanguageChanged = { changes += it },
            applyGlobally = { applied++ },
        )
        val before: Int = applied
        var expectedChanges = 0
        var current: AppLanguage = AppLanguage.System

        repeat(1000) { index ->
            val next: AppLanguage = languages[(index * 3) % languages.size]
            if (next != current) expectedChanges++
            current = next
            holder.setLanguage(next)
        }

        assertEquals(1000, applied - before)
        assertEquals(expectedChanges, changes.size)
        assertEquals(current, holder.language)
        assertEquals(listOf(current), holder.languageFlow.replayCache)
    }

    @Test
    fun `the flow instance never changes and replays only the latest selection`() {
        val holder: AppLanguageHolder = createAppLanguageHolder(AppLanguage.System, applyGlobally = {})
        val flow = holder.languageFlow
        repeat(500) { holder.setLanguage(languages[it % languages.size]) }

        assertSame(flow, holder.languageFlow)
        assertEquals(1, flow.replayCache.size)
        assertEquals(languages[499 % languages.size], flow.replayCache.single())
    }

    @Test
    fun `a holder released without being closed leaves no state behind for the next one`() {
        val firstApplied: MutableList<AppLanguage> = mutableListOf()
        val first: AppLanguageHolder = createAppLanguageHolder(AppLanguage("ar"), applyGlobally = { firstApplied += it })
        first.setLanguage(AppLanguage("en"))

        val secondApplied: MutableList<AppLanguage> = mutableListOf()
        val second: AppLanguageHolder = createAppLanguageHolder(AppLanguage("pt-BR"), applyGlobally = { secondApplied += it })

        assertEquals(AppLanguage("pt-BR"), second.language)
        assertEquals(listOf(AppLanguage("pt-BR")), secondApplied)
        first.setLanguage(AppLanguage("he"))
        assertEquals(AppLanguage("pt-BR"), second.language, "the first holder's switch must not reach the second")
        assertTrue(secondApplied.size == 1)
    }
}
