package io.github.jamal_wia.kmptoolkit.language

import android.text.TextUtils
import android.view.View
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Languages whose default script is right-to-left according to the platform's own likely-subtags data
 * (checked against `TextUtils.getLayoutDirectionFromLocale` in the same test, so the expectation is not
 * invented here) must be reported right-to-left by `isRightToLeft`: an app listing them would otherwise
 * get a silently wrong, left-to-right layout.
 */
@RunWith(RobolectricTestRunner::class)
class RtlDefaultScriptLanguagesTest {

    private val codes = listOf("azb", "pnb", "skr", "mzn", "glk", "lrc", "bgn", "arz", "ary", "apc", "rhg",
        "bal", "bqi", "luz", "sdh", "brh", "khw", "hno", "bft", "trw", "aeb", "arq", "acm", "afb", "apd", "ars",
        "acq", "ajp", "ayl", "shu", "ssh", "ayp", "abv", "ayh", "adf")

    @Test
    fun `a language the platform lays out right-to-left is right-to-left here too`() {
        val platformSaysRtl = codes.filter { TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(it)) == View.LAYOUT_DIRECTION_RTL }
        assertEquals(codes, platformSaysRtl, "control: the platform's own answer")
        assertEquals(emptyList(), codes.filterNot { isRightToLeft(it) }, "reported left-to-right")
    }
}
