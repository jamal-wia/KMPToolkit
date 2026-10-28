package io.github.jamal_wia.kmptoolkit.language

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [AppLanguageHolderTest] (`commonTest`) covers the holder's own logic against a stubbed
 * `applyGlobally`. This is the one integration test proving the *default* `applyGlobally` argument
 * really is [applyLanguageGlobally] — i.e. that a holder built the ordinary way, with no override,
 * actually reaches the real platform locale on Android.
 */
@RunWith(AndroidJUnit4::class)
class DefaultAppLanguageHolderAndroidTest {

    private val originalDefault: Locale = Locale.getDefault()

    @AfterTest
    fun restoreDefaultLocale() {
        Locale.setDefault(originalDefault)
    }

    @Test
    fun `a holder built without overriding applyGlobally sets the JVM default locale`() {
        createAppLanguageHolder(AppLanguage(code = "it", isLtr = true))

        assertEquals(Locale.forLanguageTag("it"), Locale.getDefault())
    }

    @Test
    fun `setLanguage on a default-wired holder reaches the platform locale too`() {
        val holder = createAppLanguageHolder(AppLanguage(code = "en", isLtr = true))

        holder.setLanguage(AppLanguage(code = "nl", isLtr = true))

        assertEquals(Locale.forLanguageTag("nl"), Locale.getDefault())
    }
}
