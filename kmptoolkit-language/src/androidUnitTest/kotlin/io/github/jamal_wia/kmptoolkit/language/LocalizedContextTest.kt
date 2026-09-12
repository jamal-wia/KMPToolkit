package io.github.jamal_wia.kmptoolkit.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@RunWith(AndroidJUnit4::class)
class LocalizedContextTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `wraps the context with the requested locale`() {
        val wrapped = localizedContext(base, AppLanguage(code = "es", isLtr = true))

        assertEquals(Locale.forLanguageTag("es"), wrapped.resources.configuration.locales[0])
    }

    @Test
    fun `returns the base context unchanged for System`() {
        val wrapped = localizedContext(base, AppLanguage.System)

        assertSame(base, wrapped)
    }

    @Test
    fun `does not change the base context's own configuration`() {
        val originalLocale = base.resources.configuration.locales[0]

        localizedContext(base, AppLanguage(code = "de", isLtr = true))

        assertEquals(originalLocale, base.resources.configuration.locales[0])
    }
}
