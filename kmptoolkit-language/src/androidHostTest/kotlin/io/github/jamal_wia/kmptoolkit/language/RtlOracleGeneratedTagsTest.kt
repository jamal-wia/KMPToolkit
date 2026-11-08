package io.github.jamal_wia.kmptoolkit.language

import android.text.TextUtils
import android.view.View
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `isRightToLeft` against the platform's layout direction over generated tags that mix everything a device or a
 * consumer may hand over: a language with and without extlangs, scripts, regions, variants, extensions, private
 * use, any case, `_` for `-`. The code is applied as `Locale.forLanguageTag(code.replace('_', '-'))`, so that is
 * the locale whose direction the layout must follow.
 */
@RunWith(RobolectricTestRunner::class)
class RtlOracleGeneratedTagsTest {

    private val languages: List<String> = listOf("ar", "he", "iw", "fa", "ur", "ps", "sd", "ug", "yi", "ji", "dv", "ckb", "ku", "en", "zh", "pa", "az", "uz", "tg", "ks", "kk", "ms", "yue", "arz", "pt", "de")
    private val scripts: List<String> = listOf("Arab", "Hebr", "Latn", "Cyrl", "Thaa", "Syrc", "Nkoo", "Adlm", "Hans", "Deva", "Guru")
    // Regions that imply no other script than the language's own (a Kazakh device in Iran or China is written in
    // Arabic script, a fact of the platform's likely-subtags data the documented default-script rule does not claim).
    private val neutralRegions: List<String> = listOf("US", "419", "001", "DE", "BR")
    private val rtlLanguages: Set<String> = setOf("ar", "he", "iw", "fa", "ur", "ps", "sd", "ug", "yi", "ji", "dv", "ckb", "arz")
    private val variants: List<String> = listOf("valencia", "1901", "POSIX")
    private val extensions: List<String> = listOf("u-nu-latn", "u-ca-gregory", "u-nu-arab", "x-private", "x-arab", "t-ar", "u-mu-celsius")

    private fun platformRtl(tag: String): Boolean =
        TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(tag.replace('_', '-'))) == View.LAYOUT_DIRECTION_RTL

    private fun generate(random: Random): String {
        val parts: MutableList<String> = mutableListOf(languages.random(random))
        if (random.nextInt(2) == 0) parts += scripts.random(random)
        if (random.nextInt(2) == 0) {
            parts += if (parts[0] in rtlLanguages) listOf("EG", "IL", "IR", "PK", "SA", "AF").random(random) else neutralRegions.random(random)
        }
        if (random.nextInt(8) == 0) parts += variants.random(random)
        if (random.nextInt(4) == 0) parts += extensions.random(random)
        var tag: String = parts.joinToString("-")
        when (random.nextInt(3)) {
            0 -> tag = tag.lowercase()
            1 -> tag = tag.uppercase()
        }
        if (random.nextBoolean()) tag = tag.replace('-', '_')
        return tag
    }

    @Test
    fun `generated tags are laid out as the platform lays them out`() {
        val random = Random(11)
        val seen: MutableSet<String> = mutableSetOf()
        val mismatches: MutableList<String> = mutableListOf()
        repeat(40_000) {
            val tag: String = generate(random)
            if (!seen.add(tag)) return@repeat
            val platform: Boolean = platformRtl(tag)
            val ours: Boolean = isRightToLeft(tag)
            if (platform != ours) mismatches += "$tag platform=$platform ours=$ours"
        }
        assertEquals(emptyList(), mismatches.take(40), "${mismatches.size} mismatches of ${seen.size} tags")
    }
}
