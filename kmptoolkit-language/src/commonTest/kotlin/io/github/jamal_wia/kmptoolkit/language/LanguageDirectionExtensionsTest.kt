package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `isRightToLeft` documents "an explicit script subtag decides". A BCP-47 script subtag is the one that
 * follows the language; the same four letters inside a `-u-` extension (a numbering system, `-u-nu-latn`)
 * or after the private-use singleton `-x-` are not scripts and must not decide the direction.
 */
class LanguageDirectionExtensionsTest {

    @Test
    fun `Arabic with a Latin numbering system extension stays right to left`() {
        assertTrue(isRightToLeft("ar-u-nu-latn"), "ar-u-nu-latn")
        assertTrue(isRightToLeft("ar-EG-u-nu-latn"), "ar-EG-u-nu-latn")
        assertFalse(AppLanguage("ar-EG-u-nu-latn").isLtr)
    }

    @Test
    fun `Persian with a calendar and a Latin numbering system extension stays right to left`() {
        assertTrue(isRightToLeft("fa-IR-u-ca-persian-nu-latn"))
    }

    @Test
    fun `English with an Arabic numbering system extension stays left to right`() {
        assertFalse(isRightToLeft("en-u-nu-arab"), "en-u-nu-arab")
        assertEquals(true, AppLanguage("en-US-u-nu-arab").isLtr)
    }

    @Test
    fun `a four letter private use subtag is not a script`() {
        assertTrue(isRightToLeft("ar-x-test"))
    }
}
