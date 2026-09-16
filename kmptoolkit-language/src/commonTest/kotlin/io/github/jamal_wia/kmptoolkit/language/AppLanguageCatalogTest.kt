package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val English = AppLanguage(code = "en", isLtr = true)
private val Arabic = AppLanguage(code = "ar", isLtr = false)
private val Portuguese = AppLanguage(code = "pt", isLtr = true)
private val Indonesian = AppLanguage(code = "id", isLtr = true)

private fun catalog(
    supported: List<AppLanguage> = listOf(English, Arabic, Portuguese, Indonesian),
    fallback: AppLanguage = English,
    systemId: String = "system",
    deviceLanguage: () -> String? = { null },
): AppLanguageCatalog = createAppLanguageCatalog(supported, fallback, systemId, deviceLanguage)

class AppLanguageCatalogTest {

    @Test
    fun `byCode finds a supported language`() {
        assertEquals(Arabic, catalog().byCode("ar"))
    }

    @Test
    fun `byCode ignores case`() {
        assertEquals(Arabic, catalog().byCode("AR"))
    }

    @Test
    fun `byCode returns null for a language the app does not offer`() {
        assertNull(catalog().byCode("de"))
    }

    @Test
    fun `byCode matches the legacy Indonesian code`() {
        assertEquals(Indonesian, catalog().byCode("in"))
    }

    @Test
    fun `byCode matches the legacy Hebrew and Yiddish codes`() {
        val hebrew = AppLanguage(code = "he", isLtr = false)
        val subject: AppLanguageCatalog = catalog(supported = listOf(English, hebrew))

        assertEquals(hebrew, subject.byCode("iw"))
    }

    @Test
    fun `an exact tag match wins over the primary subtag`() {
        val brazilian = AppLanguage(code = "pt-BR", isLtr = true)
        val subject: AppLanguageCatalog = catalog(
            supported = listOf(English, Portuguese, brazilian),
            deviceLanguage = { "pt-BR" },
        )

        assertEquals(brazilian, subject.resolveSystemLanguage())
    }

    @Test
    fun `a regional device language falls back to the primary subtag`() {
        assertEquals(Portuguese, catalog(deviceLanguage = { "pt-BR" }).resolveSystemLanguage())
    }

    @Test
    fun `a script-tagged device language falls back to the primary subtag`() {
        val chinese = AppLanguage(code = "zh", isLtr = true)
        val subject: AppLanguageCatalog = catalog(
            supported = listOf(English, chinese),
            deviceLanguage = { "zh-Hans-CN" },
        )

        assertEquals(chinese, subject.resolveSystemLanguage())
    }

    @Test
    fun `an unsupported device language resolves to the fallback`() {
        assertEquals(English, catalog(deviceLanguage = { "de-DE" }).resolveSystemLanguage())
    }

    @Test
    fun `an unavailable device language resolves to the fallback`() {
        assertEquals(English, catalog(deviceLanguage = { null }).resolveSystemLanguage())
    }

    @Test
    fun `an Indonesian device resolves to Indonesian despite the legacy code`() {
        assertEquals(Indonesian, catalog(deviceLanguage = { "in-ID" }).resolveSystemLanguage())
        assertEquals(Indonesian, catalog(deviceLanguage = { "in" }).resolveSystemLanguage())
    }

    @Test
    fun `the device language is re-read on every call`() {
        var device: String? = "ar"
        val subject: AppLanguageCatalog = catalog(deviceLanguage = { device })

        assertEquals(Arabic, subject.resolveSystemLanguage())
        device = "pt"
        assertEquals(Portuguese, subject.resolveSystemLanguage())
    }

    @Test
    fun `idOf returns the code for a real language`() {
        assertEquals("ar", catalog().idOf(Arabic))
    }

    @Test
    fun `idOf returns the system id for System`() {
        assertEquals("system", catalog().idOf(AppLanguage.System))
    }

    @Test
    fun `a custom system id is honoured in both directions`() {
        val subject: AppLanguageCatalog = catalog(systemId = "follow_device")

        assertEquals("follow_device", subject.idOf(AppLanguage.System))
        assertEquals(AppLanguage.System, subject.fromId("follow_device"))
        assertEquals(AppLanguage.System, subject.fromId("system"))
    }

    @Test
    fun `fromId round-trips every supported language and System`() {
        val subject: AppLanguageCatalog = catalog()

        for (language in subject.supported + AppLanguage.System) {
            assertEquals(language, subject.fromId(subject.idOf(language)))
        }
    }

    @Test
    fun `fromId resolves an unknown id to System rather than failing`() {
        assertEquals(AppLanguage.System, catalog().fromId("zz"))
    }

    @Test
    fun `fromId resolves a missing id to System`() {
        assertEquals(AppLanguage.System, catalog().fromId(null))
    }

    @Test
    fun `resolve returns an explicit language unchanged`() {
        assertEquals(Arabic, catalog(deviceLanguage = { "en" }).resolve(Arabic))
    }

    @Test
    fun `resolve returns a language outside the catalog unchanged`() {
        val german = AppLanguage(code = "de", isLtr = true)

        assertEquals(german, catalog().resolve(german))
    }

    @Test
    fun `resolve turns System into the device language`() {
        assertEquals(Arabic, catalog(deviceLanguage = { "ar" }).resolve(AppLanguage.System))
    }

    @Test
    fun `an empty supported list is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            createAppLanguageCatalog(supported = emptyList(), fallback = English)
        }
    }

    @Test
    fun `System among the supported languages is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            catalog(supported = listOf(English, AppLanguage.System))
        }
    }

    @Test
    fun `a repeated code is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            catalog(supported = listOf(English, AppLanguage(code = "EN", isLtr = true)))
        }
    }

    @Test
    fun `a code repeated through its legacy form is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            catalog(supported = listOf(English, Indonesian, AppLanguage(code = "in", isLtr = true)))
        }
    }

    @Test
    fun `a fallback outside the supported languages is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            catalog(supported = listOf(English, Arabic), fallback = Portuguese)
        }
    }

    @Test
    fun `a system id colliding with a supported code is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            catalog(systemId = "en")
        }
    }

    @Test
    fun `the supported list keeps the order it was given`() {
        assertEquals(listOf(English, Arabic, Portuguese, Indonesian), catalog().supported)
    }
}
