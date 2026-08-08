package io.github.jamal_wia.kmptoolkit.settings

import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * What [AppSettings] promises under concurrent writers, tested on real threads
 * (`Dispatchers.Default`) rather than on a test dispatcher, which would serialise the very
 * interleavings in question.
 *
 * The promise, from [AppSettings]'s own documentation, is deliberately not "the last caller wins":
 * persisting and publishing are two steps, so two writers racing on one setting can leave the flow
 * and the store disagreeing. What *is* promised — and asserted here — is that nothing is corrupted
 * or lost in a way that produces a third value, that no write is silently dropped, and that writes
 * to different settings never interfere.
 */
class AppSettingsConcurrencyTest {

    /**
     * A store that survives real concurrent access, which `InMemoryKeyValueStorage` explicitly
     * does not ("not thread-safe, drive it from one thread"). A plain `MutableMap` here would fail
     * inside the fixture and say nothing about the code under test.
     *
     * Held in a `MutableStateFlow` and updated through `update { }` — a documented-atomic
     * compare-and-set loop — rather than in a `kotlin.concurrent.atomics.AtomicReference`, whose
     * own CAS loop segfaults the Kotlin/Native test binary under exactly this contention.
     */
    private class AtomicKeyValueStorage : KeyValueStorage {
        private val entries: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

        override fun get(key: String): StorageResult<String?> =
            StorageResult.Success(entries.value[key])

        override fun put(key: String, value: String): StorageResult<Unit> {
            entries.update { it + (key to value) }
            return StorageResult.Success(Unit)
        }

        override fun remove(key: String): StorageResult<Unit> {
            entries.update { it - key }
            return StorageResult.Success(Unit)
        }

        override fun clear(): StorageResult<Unit> {
            entries.update { emptyMap() }
            return StorageResult.Success(Unit)
        }
    }

    private val config = SettingsConfig(
        supportedLanguages = setOf(LanguageTag("en"), LanguageTag("de"), LanguageTag("pt-BR")),
    )
    private val storage = AtomicKeyValueStorage()
    private val settings: AppSettings = createAppSettings(storage, config).settings

    @Test
    fun `concurrent writes to different settings all land`() = runTest {
        withContext(Dispatchers.Default) {
            // Repeated, because a single round of three coroutines would almost never interleave:
            // one pass proves the code runs, not that the settings are independent.
            repeat(ROUNDS) { round ->
                coroutineScope {
                    val results: List<SettingsResult> = listOf(
                        async { settings.setFontScale(FontScale(1.0f + round * 0.01f)) },
                        async { settings.setThemeMode(ThemeMode.entries[round % 3]) },
                        async { settings.setLanguage(LANGUAGES[round % LANGUAGES.size]) },
                    ).awaitAll()

                    results.forEach { assertEquals(SettingsResult.Success, it) }
                }
            }

            coroutineScope {
                val results: List<SettingsResult> = listOf(
                    async { settings.setFontScale(FontScale(1.3f)) },
                    async { settings.setThemeMode(ThemeMode.DARK) },
                    async { settings.setLanguage(LanguageTag("de")) },
                ).awaitAll()

                results.forEach { assertEquals(SettingsResult.Success, it) }
            }
        }

        assertEquals(FontScale(1.3f), settings.fontScale.value)
        assertEquals(ThemeMode.DARK, settings.themeMode.value)
        assertEquals(LanguageTag("de"), settings.language.value)

        val reloaded: AppSettings = createAppSettings(storage, config).settings
        assertEquals(FontScale(1.3f), reloaded.fontScale.value)
        assertEquals(ThemeMode.DARK, reloaded.themeMode.value)
        assertEquals(LanguageTag("de"), reloaded.language.value)
    }

    @Test
    fun `concurrent writes to one setting all succeed and one of them wins`() = runTest {
        // Starts away from the loaded default: a writer whose value already equals the current one
        // is short-circuited by AppSettings and never reaches the store, which would make "all of
        // them succeeded" vacuous for that writer.
        val written: List<FontScale> = List(WRITER_COUNT) { index ->
            FontScale(1.5f + index * 0.01f)
        }

        withContext(Dispatchers.Default) {
            coroutineScope {
                val results: List<SettingsResult> =
                    written.map { scale -> async { settings.setFontScale(scale) } }.awaitAll()

                results.forEach { assertEquals(SettingsResult.Success, it) }
            }
        }

        assertContains(written, settings.fontScale.value)
        assertContains(
            written.map { it.multiplier.toString() },
            storage.get(config.fontScaleKey).let { (it as StorageResult.Success).value },
        )

        // Whoever won, what they left behind has to be loadable: a race must not be able to leave
        // an entry that the next launch reports as UnreadableValue.
        val reloaded: SettingsLoad = createAppSettings(storage, config)
        assertContains(written, reloaded.settings.fontScale.value)
        assertContentEquals(emptyList(), reloaded.problems)
    }

    @Test
    fun `a setting hammered from many coroutines never ends on a value nobody wrote`() =
        runTest {
            withContext(Dispatchers.Default) {
                coroutineScope {
                    List(WRITER_COUNT) {
                        async {
                            repeat(WRITES_PER_WRITER) {
                                settings.setThemeMode(ThemeMode.DARK)
                                settings.setThemeMode(ThemeMode.LIGHT)
                            }
                        }
                    }.awaitAll()
                }
            }

            // Both values are legal outcomes, and the contract explicitly does not promise which
            // one — nor that the flow and the store agree, since persisting and publishing are two
            // steps. What is asserted is the part that does hold: neither of them can end on a
            // value no writer ever wrote.
            val current: ThemeMode = settings.themeMode.value
            assertContains(listOf(ThemeMode.DARK, ThemeMode.LIGHT), current)
            assertContains(
                listOf("DARK", "LIGHT"),
                storage.get(config.themeModeKey).let { (it as StorageResult.Success).value },
            )
        }

    private companion object {
        const val WRITER_COUNT: Int = 16
        const val WRITES_PER_WRITER: Int = 50
        const val ROUNDS: Int = 50
        val LANGUAGES: List<LanguageTag> =
            listOf(LanguageTag("en"), LanguageTag("de"), LanguageTag("pt-BR"))
    }
}
