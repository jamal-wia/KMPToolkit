package io.github.jamal_wia.kmptoolkit.language.compose

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import io.github.jamal_wia.kmptoolkit.language.AppLanguage
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a consumer does to `AppLocale` that its own tests do not: a thousand switches in one composition, a
 * language written by the content that is itself under `AppLocale`, a configuration change (font scale) delivered
 * above it, and codes no catalog produced. None of it may throw, lose the subtree's remembered state, or leave the
 * two process defaults disagreeing with the language last asked for.
 */
@Suppress("NonObservableLocale", "LocalContextConfigurationRead")
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class AppLocaleStressUiTest {

    private val originalDefault: Locale = Locale.getDefault()
    private val originalLocaleList: LocaleList = LocaleList.getDefault()

    @AfterTest
    fun restoreDefaults() {
        Locale.setDefault(originalDefault)
        LocaleList.setDefault(originalLocaleList)
    }

    @Test
    fun `a thousand switches keep the remembered state and end on the last language`() = runComposeUiTest {
        val codes: List<String> = listOf("en", "ar", "pt-BR", "zh-Hans", "he", "ru")
        var language by mutableStateOf(AppLanguage("en"))
        var nextId = 0
        var rememberedId = -1
        var seenDefault: String? = null
        var seenHead: String? = null
        setContent {
            AppLocale(language, language) {
                rememberedId = remember { nextId++ }
                seenDefault = Locale.getDefault().toLanguageTag()
                seenHead = LocaleList.getDefault()[0].toLanguageTag()
            }
        }
        waitForIdle()

        repeat(1000) { index ->
            language = AppLanguage(codes[index % codes.size])
            waitForIdle()
        }

        val last: String = codes[999 % codes.size]
        assertEquals(0, rememberedId, "the subtree was rebuilt")
        assertEquals(Locale.forLanguageTag(last).toLanguageTag(), seenDefault)
        assertEquals(seenDefault, seenHead)
    }

    @Test
    fun `a language written by the content under AppLocale settles on the written language`() = runComposeUiTest {
        var language by mutableStateOf(AppLanguage("en"))
        var direction: LayoutDirection? = null
        var seenDefault: String? = null
        setContent {
            AppLocale(language, language) {
                direction = LocalLayoutDirection.current
                seenDefault = Locale.getDefault().language
                SideEffect { if (language.code == "en") language = AppLanguage("ar") }
            }
        }
        waitForIdle()

        assertEquals("ar", language.code)
        assertEquals(LayoutDirection.Rtl, direction)
        assertEquals("ar", seenDefault)
    }

    @Test
    fun `a configuration change above AppLocale reaches content with the chosen language kept`() = runComposeUiTest {
        var base by mutableStateOf(Configuration().apply { fontScale = 1f; setLocale(Locale.forLanguageTag("en")) })
        var seenScale = 0f
        var seenLanguage: String? = null
        setContent {
            CompositionLocalProvider(LocalConfiguration provides base) {
                AppLocale(AppLanguage("ar"), AppLanguage("ar")) {
                    seenScale = LocalConfiguration.current.fontScale
                    seenLanguage = LocalConfiguration.current.locales[0].language
                }
            }
        }
        waitForIdle()
        assertEquals(1f, seenScale)
        assertEquals("ar", seenLanguage)

        base = Configuration(base).apply { fontScale = 1.5f; setLocale(Locale.forLanguageTag("en")) }
        waitForIdle()

        assertEquals(1.5f, seenScale)
        assertEquals("ar", seenLanguage)
        assertEquals("ar", Locale.getDefault().language)
    }

    @Test
    fun `codes no catalog produced compose without throwing and leave both defaults in agreement`() = runComposeUiTest {
        val hostile: List<String> = listOf(
            "", " ", "!!!", "x", "und", "zz", "ar_EG", "en_", "_", "-", "x-private", "\u0000", "\n", "‮ru", "😀",
            "a".repeat(100_000), "en-" + "x".repeat(10_000),
        )
        var language by mutableStateOf(AppLanguage("en"))
        var composedWith: String? = null
        var defaultInContent: Locale? = null
        var headInContent: Locale? = null
        setContent {
            AppLocale(language, language) {
                composedWith = language.code
                defaultInContent = Locale.getDefault()
                headInContent = LocaleList.getDefault()[0]
            }
        }
        waitForIdle()

        for (code in hostile) {
            language = AppLanguage(code = code, isLtr = true)
            waitForIdle()
            assertEquals(code, composedWith, "content did not compose for a code of ${code.length} chars")
            assertEquals(defaultInContent, headInContent, "defaults disagree for a code of ${code.length} chars")
        }
    }
}
