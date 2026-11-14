package io.github.jamal_wia.kmptoolkit.language.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.language.AppLanguage
import java.util.Locale
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a language switch costs the app's own content, counted: one run of the content lambda per switch, no matter
 * how many switches came before, and none when the selection is written but did not change. A switch that ran the
 * content twice (once in the old configuration, once in the new) would double the main-thread cost of every change
 * of language and render a frame of the old language.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class AppLocaleContentBudgetTest {

    private val originalDefault: Locale = Locale.getDefault()

    @After
    fun restore() {
        Locale.setDefault(originalDefault)
    }

    @Test
    fun `fifty switches run the content exactly once each`() = runComposeUiTest {
        val languages: List<AppLanguage> = listOf(AppLanguage("en"), AppLanguage("ar"), AppLanguage("de"))
        var language by mutableStateOf(languages[0])
        var runs = 0
        val seen: MutableList<String?> = mutableListOf()
        setContent {
            AppLocale(language, language) {
                runs++
                seen += Locale.getDefault().language
            }
        }
        waitForIdle()
        val baseline: Int = runs

        repeat(50) { step ->
            language = languages[(step + 1) % languages.size]
            waitForIdle()
        }

        assertEquals(baseline + 50, runs, "content ran ${runs - baseline} times for 50 switches; locales seen: $seen")
    }

    @Test
    fun `writing the language that is already in force runs the content no more often than it would on a recomposition of the caller`() = runComposeUiTest {
        var language by mutableStateOf(AppLanguage("en"))
        var runs = 0
        setContent {
            AppLocale(language, language) { runs++ }
        }
        waitForIdle()
        val baseline: Int = runs

        repeat(50) {
            language = AppLanguage("en") // equal value: a snapshot write that changes nothing
            waitForIdle()
        }

        assertEquals(baseline, runs, "an equal language value re-ran the content")
    }
}
