package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The right-to-left scripts the `isRightToLeft` KDoc names, one by one: an explicit script subtag decides on its
 * own, so `en-<script>` (a language that defaults to left-to-right) reads right-to-left for every listed script and
 * stays left-to-right for scripts that are not on the list. One row per script, so a script dropped from the
 * table fails by name.
 */
class RightToLeftScriptTableTest {

    // The KDoc list of isRightToLeft, in its order, plus Aran (Nastaliq), which the code accepts.
    private val documentedScripts: List<String> =
        listOf("Arab", "Hebr", "Thaa", "Syrc", "Nkoo", "Adlm", "Mand", "Samr", "Rohg", "Yezi", "Mend", "Aran")

    @Test
    fun `every documented right-to-left script makes a left-to-right language read right-to-left`() {
        for (script: String in documentedScripts) {
            assertTrue(isRightToLeft("en-$script"), "en-$script")
            assertFalse(AppLanguage("en-$script").isLtr, "AppLanguage en-$script")
            assertTrue(isRightToLeft("en-${script.uppercase()}"), "script subtags are case-insensitive: en-${script.uppercase()}")
        }
    }

    @Test
    fun `scripts that are not on the list keep a right-to-left language left-to-right`() {
        for (script: String in listOf("Latn", "Cyrl", "Grek", "Hans", "Deva", "Thai")) {
            assertFalse(isRightToLeft("ar-$script"), "ar-$script is written left-to-right")
            assertEquals(true, AppLanguage("ar-$script").isLtr, "AppLanguage ar-$script")
        }
    }

    @Test
    fun `a script name in an extension or after private use never decides`() {
        for (script: String in documentedScripts) {
            assertFalse(isRightToLeft("en-u-ca-$script"), "en-u-ca-$script")
            assertFalse(isRightToLeft("en-x-$script"), "en-x-$script")
        }
    }
}
