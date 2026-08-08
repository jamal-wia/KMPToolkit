package io.github.jamal_wia.kmptoolkit.settings

import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * [AppSettings] under real parallelism (`Dispatchers.Default`, not a test dispatcher, which would
 * serialise the very interleavings in question).
 *
 * The failure being guarded against is not a crash and not a lost write: it is a **split**, where
 * a write persists one value while another publishes a different one, leaving the store and the
 * flow permanently disagreeing. Nothing afterwards reconciles them, so the user sees one theme for
 * the rest of the session and the other after the next launch.
 *
 * Two things are needed for a test here to catch that, and both were established by removing the
 * lock and watching these fail:
 *
 * - **Alternating values, not a repeated one.** A writer that writes the same value twice is
 *   short-circuited on its second iteration and stops contending, so a repeated-value test proves
 *   nothing.
 * - **A window wide enough to cross.** Asserting on the final state only catches a split of the
 *   very *last* pair of operations, because any earlier one is overwritten by the next write —
 *   which is rare enough that the high-iteration tests below pass against an unlocked
 *   implementation more often than not. [SlowKeyValueStorage] is what makes the decisive test
 *   deterministic.
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

    /**
     * An [AtomicKeyValueStorage] whose [put] takes a measurable moment, holding the window between
     * persisting and publishing open.
     *
     * Without it, this whole class passes against a deliberately unlocked implementation: a split
     * that happens mid-run is overwritten by the next write, so only an interleaving of the very
     * *last* operations survives to be asserted on, and that is rare enough not to reproduce. The
     * delay makes the window wide enough that two writers reliably cross inside it — verified by
     * removing the lock and watching [a split between the flow and the store cannot survive]
     * fail.
     *
     * It costs nothing in the locked implementation beyond serialising the writers, because a lock
     * is exactly what stops two of them being inside that window at once.
     */
    private class SlowKeyValueStorage(
        private val delegate: AtomicKeyValueStorage = AtomicKeyValueStorage(),
    ) : KeyValueStorage by delegate {

        override fun put(key: String, value: String): StorageResult<Unit> {
            val result: StorageResult<Unit> = delegate.put(key, value)
            // Busy-waiting rather than sleeping: common Kotlin has no blocking sleep, and this has
            // to block the writing thread rather than suspend the coroutine, since the code under
            // test is not suspending.
            val until: TimeMark = TimeSource.Monotonic.markNow() + WRITE_DURATION
            while (until.hasNotPassedNow()) Unit
            return result
        }
    }

    private val config = SettingsConfig(
        supportedLanguages = setOf(LanguageTag("en"), LanguageTag("de"), LanguageTag("pt-BR")),
    )

    @Test
    fun `a split between the flow and the store cannot survive`() = runTest {
        // Two writers, one write each, over a store slow enough that they are both inside the
        // persist-then-publish window: the case where an unlocked implementation persists one
        // value and publishes the other. Repeated because which of the two wins is a genuine race
        // — what must never happen is the two halves disagreeing about the winner.
        repeat(SPLIT_ATTEMPTS) {
            val storage = SlowKeyValueStorage()
            val settings: AppSettings = createAppSettings(storage, config).settings

            withContext(Dispatchers.Default) {
                listOf(
                    async { settings.setThemeMode(ThemeMode.LIGHT) },
                    async { settings.setThemeMode(ThemeMode.DARK) },
                ).awaitAll()
            }

            assertEquals(
                settings.themeMode.value.name,
                storage.get(config.themeModeKey).valueOrFail(),
                "the published value and the persisted one came from different writers — nothing " +
                    "reconciles that afterwards, so the user sees one theme this session and the " +
                    "other after the next launch",
            )
        }
    }

    @Test
    fun `concurrent alternating writes leave the flow and the store on the same value`() = runTest {
        repeat(REPEATS) {
            val storage = AtomicKeyValueStorage()
            val settings: AppSettings = createAppSettings(storage, config).settings

            withContext(Dispatchers.Default) {
                List(WRITER_COUNT) { writer ->
                    async {
                        repeat(ITERATIONS) { i ->
                            settings.setThemeMode(alternating(writer + i))
                        }
                    }
                }.awaitAll()
            }

            assertEquals(
                settings.themeMode.value.name,
                storage.get(config.themeModeKey).valueOrFail(),
                "a split between the published value and the persisted one never reconciles " +
                    "itself — the user would see one theme this session and the other after the " +
                    "next launch",
            )
        }
    }

    @Test
    fun `concurrent alternating writes to a value type leave the flow and the store agreeing`() =
        runTest {
            repeat(REPEATS) {
                val storage = AtomicKeyValueStorage()
                val settings: AppSettings = createAppSettings(storage, config).settings

                withContext(Dispatchers.Default) {
                    List(WRITER_COUNT) { writer ->
                        async {
                            repeat(ITERATIONS) { i ->
                                settings.setFontScale(alternatingScale(writer + i))
                            }
                        }
                    }.awaitAll()
                }

                assertEquals(
                    settings.fontScale.value.multiplier.toString(),
                    storage.get(config.fontScaleKey).valueOrFail(),
                )
            }
        }

    @Test
    fun `concurrent alternating language writes leave the flow and the store agreeing`() = runTest {
        repeat(REPEATS) {
            val storage = AtomicKeyValueStorage()
            val settings: AppSettings = createAppSettings(storage, config).settings

            withContext(Dispatchers.Default) {
                List(WRITER_COUNT) { writer ->
                    async {
                        repeat(ITERATIONS) { i ->
                            // Includes the follow-the-system sentinel, whose encoding is the one
                            // that is not simply the value's own text.
                            settings.setLanguage(LANGUAGES[(writer + i) % LANGUAGES.size])
                        }
                    }
                }.awaitAll()
            }

            assertEquals(
                settings.language.value?.value ?: SYSTEM_LANGUAGE,
                storage.get(config.languageKey).valueOrFail(),
            )
        }
    }

    @Test
    fun `concurrent writes to different settings all land`() = runTest {
        val storage = AtomicKeyValueStorage()
        val settings: AppSettings = createAppSettings(storage, config).settings

        withContext(Dispatchers.Default) {
            // Alternating rounds first, so the three settings genuinely contend for the lock
            // rather than each finishing before the next one starts.
            repeat(ITERATIONS) { i ->
                listOf(
                    async { settings.setFontScale(alternatingScale(i)) },
                    async { settings.setThemeMode(alternating(i)) },
                    async { settings.setLanguage(LANGUAGES[i % LANGUAGES.size]) },
                ).awaitAll()
            }

            val results: List<SettingsResult> = listOf(
                async { settings.setFontScale(FontScale(1.3f)) },
                async { settings.setThemeMode(ThemeMode.DARK) },
                async { settings.setLanguage(LanguageTag("de")) },
            ).awaitAll()

            results.forEach { assertEquals(SettingsResult.Success, it) }
        }

        assertEquals(FontScale(1.3f), settings.fontScale.value)
        assertEquals(ThemeMode.DARK, settings.themeMode.value)
        assertEquals(LanguageTag("de"), settings.language.value)

        // A setting locked by another setting's writer would be the other way this can fail: each
        // has to have survived the others' traffic all the way into the store.
        val reloaded: SettingsLoad = createAppSettings(storage, config)
        assertEquals(FontScale(1.3f), reloaded.settings.fontScale.value)
        assertEquals(ThemeMode.DARK, reloaded.settings.themeMode.value)
        assertEquals(LanguageTag("de"), reloaded.settings.language.value)
        assertContentEquals(emptyList(), reloaded.problems)
    }

    @Test
    fun `every concurrent write succeeds and none leaves a value nobody wrote`() = runTest {
        val storage = AtomicKeyValueStorage()
        val settings: AppSettings = createAppSettings(storage, config).settings
        // Starts away from the loaded default: a writer whose value already equals the current one
        // is short-circuited and never reaches the store, which would make this vacuous for it.
        val written: List<FontScale> = List(WRITER_COUNT) { FontScale(1.5f + it * 0.01f) }

        withContext(Dispatchers.Default) {
            val results: List<SettingsResult> =
                written.map { scale -> async { settings.setFontScale(scale) } }.awaitAll()

            results.forEach { assertEquals(SettingsResult.Success, it) }
        }

        assertContains(written, settings.fontScale.value)
        assertContains(written.map { it.multiplier.toString() }, storage.get(config.fontScaleKey).valueOrFail())

        // Whatever the winner left behind has to be loadable: a race must not be able to produce
        // an entry that the next launch reports as UnreadableValue.
        val reloaded: SettingsLoad = createAppSettings(storage, config)
        assertEquals(settings.fontScale.value, reloaded.settings.fontScale.value)
        assertContentEquals(emptyList(), reloaded.problems)
    }

    private fun StorageResult<String?>.valueOrFail(): String? =
        (this as StorageResult.Success).value

    private companion object {
        const val WRITER_COUNT: Int = 8
        const val ITERATIONS: Int = 200
        const val REPEATS: Int = 10
        const val SPLIT_ATTEMPTS: Int = 100
        val WRITE_DURATION = 1.milliseconds

        val LANGUAGES: List<LanguageTag?> =
            listOf(LanguageTag("en"), null, LanguageTag("de"), LanguageTag("pt-BR"))

        /** Alternates so that every iteration actually changes the value and re-contends. */
        fun alternating(i: Int): ThemeMode = if (i % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK

        fun alternatingScale(i: Int): FontScale =
            if (i % 2 == 0) FontScale(1.15f) else FontScale(1.3f)
    }
}
