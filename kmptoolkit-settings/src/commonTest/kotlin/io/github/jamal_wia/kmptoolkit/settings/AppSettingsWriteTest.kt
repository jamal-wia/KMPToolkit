package io.github.jamal_wia.kmptoolkit.settings

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsWriteTest {

    private val config = SettingsConfig(
        supportedLanguages = setOf(LanguageTag("en"), LanguageTag("pt-BR")),
        defaultLanguage = LanguageTag("en"),
    )
    private val storage = ScriptedKeyValueStorage()
    private val settings: AppSettings = createAppSettings(storage, config).settings

    @Test
    fun `setting the font scale publishes it and persists the multiplier`() {
        val result: SettingsResult = settings.setFontScale(FontScale(1.15f))

        assertEquals(SettingsResult.Success, result)
        assertEquals(FontScale(1.15f), settings.fontScale.value)
        assertEquals(
            "1.15",
            storage.stored(config.fontScaleKey),
            "the wire format is the decimal string itself — comparing parsed floats would let a " +
                "format regression through",
        )
    }

    @Test
    fun `setting the theme mode publishes it and persists the enum name`() {
        val result: SettingsResult = settings.setThemeMode(ThemeMode.DARK)

        assertEquals(SettingsResult.Success, result)
        assertEquals(ThemeMode.DARK, settings.themeMode.value)
        assertEquals("DARK", storage.stored(config.themeModeKey))
    }

    @Test
    fun `setting the language publishes the canonical tag and persists it`() {
        val result: SettingsResult = settings.setLanguage(LanguageTag("PT-br"))

        assertEquals(SettingsResult.Success, result)
        assertEquals(LanguageTag("pt-BR"), settings.language.value)
        assertEquals("pt-BR", storage.stored(config.languageKey))
    }

    @Test
    fun `choosing to follow the system persists the empty entry rather than removing the key`() {
        settings.setLanguage(LanguageTag("pt-BR"))

        val result: SettingsResult = settings.setLanguage(null)

        assertEquals(SettingsResult.Success, result)
        assertNull(settings.language.value)
        assertEquals(SYSTEM_LANGUAGE, storage.stored(config.languageKey))
    }

    @Test
    fun `a language outside the supported set is refused and nothing is written`() {
        val result: SettingsResult = settings.setLanguage(LanguageTag("de"))

        assertEquals(
            SettingsResult.Failure(SettingsError.UnsupportedLanguage(LanguageTag("de"))),
            result,
        )
        assertEquals(LanguageTag("en"), settings.language.value)
        assertContentEquals(emptyList(), storage.attemptedWrites)
    }

    @Test
    fun `a language outside the supported set is refused even when it differs only in case`() {
        val result: SettingsResult = settings.setLanguage(LanguageTag("PT"))

        assertEquals(
            SettingsResult.Failure(SettingsError.UnsupportedLanguage(LanguageTag("pt"))),
            result,
            "pt and pt-BR are different tags — a supported set is matched exactly",
        )
        assertEquals(LanguageTag("en"), settings.language.value)
    }

    @Test
    fun `any well-formed language is accepted when no supported set is configured`() {
        val open: AppSettings = createAppSettings(ScriptedKeyValueStorage(), SettingsConfig()).settings

        assertEquals(SettingsResult.Success, open.setLanguage(LanguageTag("de-AT")))
        assertEquals(LanguageTag("de-AT"), open.language.value)
    }

    @Test
    fun `following the system is accepted whatever the supported set is`() {
        assertEquals(SettingsResult.Success, settings.setLanguage(null))
        assertNull(settings.language.value)
    }

    @Test
    fun `a failed write of the follow-the-system sentinel is reported like any other`() {
        settings.setLanguage(LanguageTag("pt-BR"))
        val error = StorageError.OperationFailed(StorageOperation.PUT, config.languageKey)
        storage.failWritesOf(config.languageKey, error)

        val result: SettingsResult = settings.setLanguage(null)

        assertEquals(
            SettingsResult.Failure(SettingsError.WriteFailed(config.languageKey, error)),
            result,
        )
        assertEquals(LanguageTag("pt-BR"), settings.language.value)
        assertEquals("pt-BR", storage.stored(config.languageKey))
    }

    @Test
    fun `a chosen language survives a new instance over the same store`() {
        settings.setLanguage(LanguageTag("pt-BR"))

        val reloaded: SettingsLoad = createAppSettings(storage, config)

        assertEquals(LanguageTag("pt-BR"), reloaded.settings.language.value)
        assertContentEquals(emptyList(), reloaded.problems)
    }

    @Test
    fun `choosing to follow the system survives a new instance over the same store`() {
        settings.setLanguage(null)

        val reloaded: SettingsLoad = createAppSettings(storage, config)

        assertNull(
            reloaded.settings.language.value,
            "the sentinel must not decay back into defaultLanguage at the next launch",
        )
        assertContentEquals(emptyList(), reloaded.problems)
    }

    @Test
    fun `a second instance over the same store does not see this one's writes`() {
        val other: AppSettings = createAppSettings(storage, config).settings

        settings.setThemeMode(ThemeMode.DARK)

        assertEquals(
            ThemeMode.SYSTEM,
            other.themeMode.value,
            "the flows are populated once at construction — an instance is not a live view",
        )
        assertEquals(ThemeMode.DARK, createAppSettings(storage, config).settings.themeMode.value)
    }

    @Test
    fun `setting the value that is already current writes nothing`() {
        settings.setThemeMode(ThemeMode.DARK)
        val writesAfterFirst: Int = storage.attemptedWrites.size

        val result: SettingsResult = settings.setThemeMode(ThemeMode.DARK)

        assertEquals(SettingsResult.Success, result)
        assertEquals(writesAfterFirst, storage.attemptedWrites.size)
    }

    @Test
    fun `a failed write leaves the published value untouched and reports the cause`() {
        val error = StorageError.OperationFailed(StorageOperation.PUT, config.themeModeKey)
        storage.failWritesOf(config.themeModeKey, error)

        val result: SettingsResult = settings.setThemeMode(ThemeMode.DARK)

        assertEquals(SettingsResult.Failure(SettingsError.WriteFailed(config.themeModeKey, error)), result)
        assertEquals(
            ThemeMode.SYSTEM,
            settings.themeMode.value,
            "publishing a value that failed to persist would show a choice that reverts on relaunch",
        )
        assertNull(storage.stored(config.themeModeKey))
    }

    @Test
    fun `a failed write of one setting does not affect another`() {
        val error = StorageError.Unavailable()
        storage.failWritesOf(config.fontScaleKey, error)

        assertEquals(
            SettingsResult.Failure(SettingsError.WriteFailed(config.fontScaleKey, error)),
            settings.setFontScale(FontScale(1.3f)),
        )
        assertEquals(SettingsResult.Success, settings.setThemeMode(ThemeMode.LIGHT))
        assertEquals(FontScale.DEFAULT, settings.fontScale.value)
        assertEquals(ThemeMode.LIGHT, settings.themeMode.value)
    }

    @Test
    fun `a write that failed once succeeds after the store recovers`() {
        val recovering = ScriptedKeyValueStorage()
        recovering.failWritesOf(config.fontScaleKey, StorageError.Unavailable())
        val recovered: AppSettings = createAppSettings(recovering, config).settings
        recovered.setFontScale(FontScale(1.3f))

        recovering.stopFailingWritesOf(config.fontScaleKey)
        val result: SettingsResult = recovered.setFontScale(FontScale(1.3f))

        assertEquals(SettingsResult.Success, result)
        assertEquals(FontScale(1.3f), recovered.fontScale.value)
    }

    @Test
    fun `errorOrNull and isSuccess describe the outcome`() {
        assertNull(settings.setThemeMode(ThemeMode.DARK).errorOrNull())
        assertTrue(settings.setThemeMode(ThemeMode.LIGHT).isSuccess)

        val failed: SettingsResult = settings.setLanguage(LanguageTag("de"))
        assertEquals(SettingsError.UnsupportedLanguage(LanguageTag("de")), failed.errorOrNull())
        assertFalse(failed.isSuccess)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a collector is notified of a successful write and never of a failed one`() = runTest {
        val seen: MutableList<ThemeMode> = mutableListOf()
        val collector: Job = launch(UnconfinedTestDispatcher(testScheduler)) {
            settings.themeMode.collect { seen += it }
        }

        settings.setThemeMode(ThemeMode.DARK)
        storage.failWritesOf(config.themeModeKey, StorageError.Unavailable())
        settings.setThemeMode(ThemeMode.LIGHT)
        collector.cancel()

        assertContentEquals(listOf(ThemeMode.SYSTEM, ThemeMode.DARK), seen)
    }
}
