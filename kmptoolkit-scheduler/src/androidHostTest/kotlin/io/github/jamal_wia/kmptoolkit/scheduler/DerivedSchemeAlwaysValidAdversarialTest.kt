package io.github.jamal_wia.kmptoolkit.scheduler

import android.net.Uri
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "`null` derives `<applicationId>.alarm`, with characters a URI scheme cannot hold (`_`, non-ASCII)
 * replaced by `-`", and a configured scheme "must be a valid URI scheme": so whatever an application
 * id looks like, the derived scheme has to satisfy the very rule a configured one is held to, has to
 * be accepted as a configured one (so a consumer can pin it), and has to survive `Uri` unchanged.
 * Random ids from an alphabet made of everything that could go wrong: separators, underscores,
 * digits first, accents, a space, NUL, supplementary characters and lone surrogates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DerivedSchemeAlwaysValidAdversarialTest {

    private val alphabet: List<String> = listOf(
        "a", "Z", "0", "9", ".", "-", "+", "_", " ", "é", "ǅ", "日", "\u0000", "😀", "\uD800", "\uDC00", "/", ":", "%", "́",
    )

    @Test
    fun `a scheme derived from any application id is a valid scheme, pinnable, and unchanged by Uri`() {
        val random = Random(20261111)
        val seen: MutableSet<String> = mutableSetOf()
        repeat(5_000) {
            val applicationId: String = (0 until random.nextInt(0, 14)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
            val derived: String = AlarmSchedulerConfig().resolveIntentScheme(applicationId)
            assertTrue(Regex("[a-zA-Z][a-zA-Z0-9+.\\-]*").matches(derived), "'$derived' from ${applicationId.map { it.code }}")
            assertTrue(derived.endsWith(".alarm"), derived)
            // A consumer can pin the derived value (the documented way to keep it stable).
            assertEquals(derived, AlarmSchedulerConfig(alarmIntentScheme = derived).resolveIntentScheme("anything else"))
            assertEquals(derived, Uri.Builder().scheme(derived).appendPath("x").build().scheme)
            seen += derived
        }
        assertTrue(seen.size > 100, "the generator must reach many different ids")
    }
}
