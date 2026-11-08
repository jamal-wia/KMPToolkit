package io.github.jamal_wia.kmptoolkit.language

import android.text.TextUtils
import android.view.View
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Sweep of every language the JDK knows against the platform's own layout direction: `isRightToLeft` must
 * agree with `TextUtils.getLayoutDirectionFromLocale` for the bare language code, and for every
 * script-qualified tag the JDK lists.
 */
@RunWith(RobolectricTestRunner::class)
class RtlOracleSweepTest {

    private fun platformRtl(tag: String): Boolean =
        TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(tag)) == View.LAYOUT_DIRECTION_RTL

    @Test
    fun `every language code the JDK lists is laid out as the platform lays it out`() {
        val languages: List<String> = Locale.getAvailableLocales().map { it.language }.filter { it.length in 2..3 }.distinct().sorted()
        val mismatches = languages.filter { platformRtl(it) != isRightToLeft(it) }
            .map { "$it platform=${platformRtl(it)} ours=${isRightToLeft(it)}" }
        assertEquals(emptyList(), mismatches, "bare language codes")
    }

    @Test
    fun `every script-qualified tag the JDK lists is laid out as the platform lays it out`() {
        val tags: List<String> = Locale.getAvailableLocales().filter { it.script.isNotEmpty() }.map { it.toLanguageTag() }.distinct().sorted()
        val mismatches = tags.filter { platformRtl(it) != isRightToLeft(it) }.map { "$it platform=${platformRtl(it)} ours=${isRightToLeft(it)}" }
        assertEquals(emptyList(), mismatches, "script tags")
    }
}
