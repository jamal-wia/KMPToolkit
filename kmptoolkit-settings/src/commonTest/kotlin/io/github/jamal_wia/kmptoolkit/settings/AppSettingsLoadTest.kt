package io.github.jamal_wia.kmptoolkit.settings

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsLoadTest {

    private val config = SettingsConfig(
        supportedLanguages = setOf(LanguageTag("en"), LanguageTag("pt-BR")),
        defaultLanguage = LanguageTag("en"),
    )

    @Test
    fun `an empty store loads every configured default without reporting a problem`() {
        val load: SettingsLoad = createAppSettings(ScriptedKeyValueStorage(), config)

        assertEquals(FontScale.DEFAULT, load.settings.fontScale.value)
        assertEquals(ThemeMode.SYSTEM, load.settings.themeMode.value)
        assertEquals(LanguageTag("en"), load.settings.language.value)
        assertContentEquals(emptyList(), load.problems)
    }

    @Test
    fun `an empty store loads overridden defaults`() {
        val load: SettingsLoad = createAppSettings(
            storage = ScriptedKeyValueStorage(),
            config = SettingsConfig(
                defaultFontScale = FontScale(1.3f),
                defaultThemeMode = ThemeMode.DARK,
                defaultLanguage = null,
            ),
        )

        assertEquals(FontScale(1.3f), load.settings.fontScale.value)
        assertEquals(ThemeMode.DARK, load.settings.themeMode.value)
        assertNull(load.settings.language.value)
        assertContentEquals(emptyList(), load.problems)
    }

    @Test
    fun `values written by a previous instance are read back exactly`() {
        val storage = ScriptedKeyValueStorage()
        val first: AppSettings = createAppSettings(storage, config).settings
        first.setFontScale(FontScale(1.15f))
        first.setThemeMode(ThemeMode.DARK)
        first.setLanguage(LanguageTag("pt-BR"))

        val second: SettingsLoad = createAppSettings(storage, config)

        assertEquals(FontScale(1.15f), second.settings.fontScale.value)
        assertEquals(ThemeMode.DARK, second.settings.themeMode.value)
        assertEquals(LanguageTag("pt-BR"), second.settings.language.value)
        assertContentEquals(emptyList(), second.problems)
    }

    @Test
    fun `a font scale that is not a number falls back to the default and is reported`() {
        val storage = ScriptedKeyValueStorage()
        storage.put(config.fontScaleKey, "LARGE")

        val load: SettingsLoad = createAppSettings(storage, config)

        assertEquals(FontScale.DEFAULT, load.settings.fontScale.value)
        assertContentEquals(
            listOf(SettingsError.UnreadableValue(config.fontScaleKey, "LARGE")),
            load.problems,
        )
    }

    @Test
    fun `a font scale outside the allowed range falls back to the default and is reported`() {
        val storage = ScriptedKeyValueStorage()
        storage.put(config.fontScaleKey, "9.0")

        val load: SettingsLoad = createAppSettings(storage, config)

        assertEquals(FontScale.DEFAULT, load.settings.fontScale.value)
        assertContentEquals(
            listOf(SettingsError.UnreadableValue(config.fontScaleKey, "9.0")),
            load.problems,
        )
    }

    @Test
    fun `a font scale at either end of the range loads cleanly`() {
        listOf("0.5", "3.0").forEach { raw ->
            val storage = ScriptedKeyValueStorage()
            storage.put(config.fontScaleKey, raw)

            val load: SettingsLoad = createAppSettings(storage, config)

            assertEquals(FontScale(raw.toFloat()), load.settings.fontScale.value)
            assertContentEquals(emptyList(), load.problems)
        }
    }

    @Test
    fun `a font scale just outside either end falls back and is reported`() {
        listOf("0.49", "3.01").forEach { raw ->
            val storage = ScriptedKeyValueStorage()
            storage.put(config.fontScaleKey, raw)

            val load: SettingsLoad = createAppSettings(storage, config)

            assertEquals(FontScale.DEFAULT, load.settings.fontScale.value)
            assertContentEquals(
                listOf(SettingsError.UnreadableValue(config.fontScaleKey, raw)),
                load.problems,
            )
        }
    }

    @Test
    fun `every font scale this platform writes is readable again`() {
        listOf(0.5f, 1.0f, 1.15f, 1.3f, 2.375f, 3.0f).forEach { multiplier ->
            val storage = ScriptedKeyValueStorage()
            createAppSettings(storage, config).settings.setFontScale(FontScale(multiplier))

            val load: SettingsLoad = createAppSettings(storage, config)

            assertEquals(
                FontScale(multiplier),
                load.settings.fontScale.value,
                "the store holds the multiplier as text, so the encode/decode pair has to be exact",
            )
            assertContentEquals(emptyList(), load.problems)
        }
    }

    @Test
    fun `an empty font scale entry falls back to the default and is reported`() {
        val storage = ScriptedKeyValueStorage()
        storage.put(config.fontScaleKey, "")

        val load: SettingsLoad = createAppSettings(storage, config)

        assertEquals(FontScale.DEFAULT, load.settings.fontScale.value)
        assertContentEquals(
            listOf(SettingsError.UnreadableValue(config.fontScaleKey, "")),
            load.problems,
        )
    }

    @Test
    fun `a theme mode from a version that had another one falls back and is reported`() {
        val storage = ScriptedKeyValueStorage()
        storage.put(config.themeModeKey, "MIDNIGHT")

        val load: SettingsLoad = createAppSettings(storage, config)

        assertEquals(ThemeMode.SYSTEM, load.settings.themeMode.value)
        assertContentEquals(
            listOf(SettingsError.UnreadableValue(config.themeModeKey, "MIDNIGHT")),
            load.problems,
        )
    }

    @Test
    fun `a theme mode is matched case-sensitively against the enum it was written from`() {
        val storage = ScriptedKeyValueStorage()
        storage.put(config.themeModeKey, "dark")

        val load: SettingsLoad = createAppSettings(storage, config)

        assertEquals(ThemeMode.SYSTEM, load.settings.themeMode.value)
        assertContentEquals(
            listOf(SettingsError.UnreadableValue(config.themeModeKey, "dark")),
            load.problems,
        )
    }

    @Test
    fun `a malformed language tag falls back to the default and is reported`() {
        val storage = ScriptedKeyValueStorage()
        storage.put(config.languageKey, "en_US")

        val load: SettingsLoad = createAppSettings(storage, config)

        assertEquals(LanguageTag("en"), load.settings.language.value)
        assertContentEquals(
            listOf(SettingsError.UnreadableValue(config.languageKey, "en_US")),
            load.problems,
        )
    }

    @Test
    fun `a language the app no longer supports falls back to the default and is reported`() {
        val storage = ScriptedKeyValueStorage()
        storage.put(config.languageKey, "de")

        val load: SettingsLoad = createAppSettings(storage, config)

        assertEquals(LanguageTag("en"), load.settings.language.value)
        assertContentEquals(
            listOf(SettingsError.UnsupportedLanguage(LanguageTag("de"))),
            load.problems,
        )
    }

    @Test
    fun `any well-formed language is accepted when no supported set is configured`() {
        val storage = ScriptedKeyValueStorage()
        val open = SettingsConfig()
        storage.put(open.languageKey, "de-AT")

        val load: SettingsLoad = createAppSettings(storage, open)

        assertEquals(LanguageTag("de-AT"), load.settings.language.value)
        assertContentEquals(emptyList(), load.problems)
    }

    @Test
    fun `a stored language is canonicalised on the way in`() {
        val storage = ScriptedKeyValueStorage()
        storage.put(config.languageKey, "PT-br")

        val load: SettingsLoad = createAppSettings(storage, config)

        assertEquals(LanguageTag("pt-BR"), load.settings.language.value)
        assertContentEquals(emptyList(), load.problems)
    }

    @Test
    fun `the empty language entry means follow the system rather than fall back to the default`() {
        val storage = ScriptedKeyValueStorage()
        storage.put(config.languageKey, SYSTEM_LANGUAGE)

        val load: SettingsLoad = createAppSettings(storage, config)

        assertNull(
            load.settings.language.value,
            "a user who deliberately chose 'system' must not get defaultLanguage back",
        )
        assertContentEquals(emptyList(), load.problems)
    }

    @Test
    fun `a read failure surfaces as a problem instead of silently defaulting`() {
        val storage = ScriptedKeyValueStorage()
        val error = StorageError.Unavailable()
        storage.failReadsOf(config.themeModeKey, error)

        val load: SettingsLoad = createAppSettings(storage, config)

        assertEquals(ThemeMode.SYSTEM, load.settings.themeMode.value)
        assertContentEquals(
            listOf(SettingsError.ReadFailed(config.themeModeKey, error)),
            load.problems,
        )
    }

    @Test
    fun `a read failure on one setting does not stop the others from loading`() {
        val storage = ScriptedKeyValueStorage()
        val error = StorageError.Unavailable()
        storage.put(config.fontScaleKey, "1.15")
        storage.put(config.languageKey, "pt-BR")
        storage.failReadsOf(config.themeModeKey, error)

        val load: SettingsLoad = createAppSettings(storage, config)

        assertEquals(FontScale(1.15f), load.settings.fontScale.value)
        assertEquals(LanguageTag("pt-BR"), load.settings.language.value)
        assertContentEquals(
            listOf(SettingsError.ReadFailed(config.themeModeKey, error)),
            load.problems,
        )
    }

    @Test
    fun `every setting failing to read is reported once each in read order`() {
        val storage = ScriptedKeyValueStorage()
        val error = StorageError.OperationFailed(StorageOperation.GET)
        storage.failReadsOf(config.fontScaleKey, error)
        storage.failReadsOf(config.themeModeKey, error)
        storage.failReadsOf(config.languageKey, error)

        val load: SettingsLoad = createAppSettings(storage, config)

        assertContentEquals(
            listOf(
                SettingsError.ReadFailed(config.fontScaleKey, error),
                SettingsError.ReadFailed(config.themeModeKey, error),
                SettingsError.ReadFailed(config.languageKey, error),
            ),
            load.problems,
        )
    }

    @Test
    fun `loading never writes to the store`() {
        val storage = ScriptedKeyValueStorage()
        storage.put(config.fontScaleKey, "not a number")
        val writesBefore: Int = storage.attemptedWrites.size

        createAppSettings(storage, config)

        assertEquals(
            writesBefore,
            storage.attemptedWrites.size,
            "a corrupted entry is left for the next write to overwrite, not repaired behind the " +
                "user's back",
        )
        assertEquals("not a number", storage.stored(config.fontScaleKey))
    }

    @Test
    fun `two settings over different key prefixes do not see each other`() {
        val storage = ScriptedKeyValueStorage()
        val feature = SettingsConfig(keyPrefix = "com.example.feature")
        createAppSettings(storage, feature).settings.setThemeMode(ThemeMode.DARK)

        val other: SettingsLoad = createAppSettings(storage, SettingsConfig())

        assertEquals(ThemeMode.SYSTEM, other.settings.themeMode.value)
        assertTrue(other.problems.isEmpty())
    }
}
