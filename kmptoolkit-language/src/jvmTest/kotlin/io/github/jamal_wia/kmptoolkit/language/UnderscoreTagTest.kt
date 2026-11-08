package io.github.jamal_wia.kmptoolkit.language

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `isRightToLeft` (and so the `AppLanguage(code)` factory) deliberately reads `ar_EG` the way it reads
 * `ar-EG` - `Locale.toString()` style tags are what Android developers have at hand. A tag the direction logic
 * accepts must also be applied: `ar_EG` marked right-to-left while `applyLanguageGlobally` quietly installs the
 * root locale (`Locale.forLanguageTag("ar_EG")` is the empty locale) gives a mirrored layout with the wrong
 * language.
 */
class UnderscoreTagTest {

    @Test
    fun `an underscore tag that is read as right-to-left is also applied as its language`() {
        val saved: Locale = Locale.getDefault()
        try {
            val language = AppLanguage("ar_EG")
            assertTrue(!language.isLtr, "control: the direction logic accepts the underscore tag")

            applyLanguageGlobally(language)

            assertEquals("ar", Locale.getDefault().language, "the direction says Arabic, the platform locale says '${Locale.getDefault()}'")
            assertEquals("EG", Locale.getDefault().country, "the region is applied too")
        } finally {
            Locale.setDefault(saved)
        }
    }
}
