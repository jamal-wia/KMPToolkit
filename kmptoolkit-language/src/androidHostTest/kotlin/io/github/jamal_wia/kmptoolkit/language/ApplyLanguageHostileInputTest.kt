package io.github.jamal_wia.kmptoolkit.language

import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Hostile input to the one platform side effect of the module: [applyLanguageGlobally] with codes no catalog ever
 * produced (empty, blank, punctuation, a lone subtag, private use, control characters, a 100 000 character tag,
 * the `_` spelling) must return without throwing on each Android level the module supports and must leave the two
 * platform defaults it writes in agreement (`Locale.getDefault()` and the head of `LocaleList.getDefault()` name the
 * same locale), because Compose Multiplatform reads one and the JVM APIs read the other. `System` afterwards
 * undoes the override, whatever came before it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 28, 35])
class ApplyLanguageHostileInputTest {

    private val originalDefault: Locale = Locale.getDefault()
    private val originalLocaleList: LocaleList = LocaleList.getDefault()

    @AfterTest
    fun restoreDefaultLocale() {
        Locale.setDefault(originalDefault)
        LocaleList.setDefault(originalLocaleList)
    }

    private val hostile: List<String> = listOf(
        "", " ", "!!!", "x", "und", "zz", "ar_EG", "en_", "_", "-", "--", "en-US-u-ca-gregory", "x-private", "a-b-c-d-e-f-g-h-i",
        "\u0000", "en\u0000", "\n", "‮ru", "😀", "én", "İ", "a".repeat(100_000), "en-" + "x".repeat(10_000),
    )

    @Test
    fun `an unreadable code never throws and leaves both platform defaults in agreement`() {
        for (code in hostile) {
            val label: String = "code of ${code.length} chars starting ${code.take(8).map { it.code }}"
            applyLanguageGlobally(AppLanguage(code = code, isLtr = true))
            assertEquals(Locale.getDefault(), LocaleList.getDefault()[0], "Locale default and LocaleList head disagree for $label")
        }
    }

    @Test
    fun `applying System after any hostile code restores the device locale`() {
        val device: String? = getSystemLanguageCode()
        for (code in hostile) {
            applyLanguageGlobally(AppLanguage(code = code, isLtr = true))
            applyLanguageGlobally(AppLanguage.System)
            assertEquals(device, Locale.getDefault().toLanguageTag(), "System after a hostile code of ${code.length} chars")
            assertEquals(Locale.getDefault(), LocaleList.getDefault()[0], "System after a hostile code of ${code.length} chars: the two defaults")
        }
    }

    @Test
    fun `the code of a hostile language is kept as given`() {
        for (code in hostile) {
            assertEquals(code, AppLanguage(code = code, isLtr = true).code)
        }
    }
}
