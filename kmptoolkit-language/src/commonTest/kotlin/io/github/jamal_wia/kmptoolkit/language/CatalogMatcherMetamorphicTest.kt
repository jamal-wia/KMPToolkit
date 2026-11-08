package io.github.jamal_wia.kmptoolkit.language

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Metamorphic checks of the documented matching rules over randomly generated tags and supported lists (fixed
 * seeds, bounded iterations): what the KDoc says is irrelevant to the result must be irrelevant for every input,
 * not only for the rows a person thought of.
 */
class CatalogMatcherMetamorphicTest {

    private val languages: List<String> = listOf("en", "pt", "zh", "sr", "he", "iw", "id", "in", "ar", "es", "yi", "ji", "de", "yue")
    private val scripts: List<String> = listOf("Hans", "Hant", "Latn", "Cyrl", "Arab")
    private val regions: List<String> = listOf("CN", "TW", "HK", "MO", "SG", "BR", "PT", "RS", "IL", "EG", "419", "001", "US")
    private val extlangs: List<String> = listOf("cmn", "yue")
    private val variants: List<String> = listOf("valencia", "1901", "POSIX")

    private fun randomTag(random: Random): String {
        val parts: MutableList<String> = mutableListOf(languages.random(random))
        if (random.nextInt(12) == 0) parts += extlangs.random(random)
        if (random.nextInt(3) == 0) parts += scripts.random(random)
        if (random.nextInt(3) != 0) parts += regions.random(random)
        if (random.nextInt(10) == 0) parts += variants.random(random)
        return parts.joinToString("-")
    }

    private fun mutate(tag: String, random: Random): String {
        var out: String = tag
        when (random.nextInt(3)) {
            0 -> out = out.lowercase()
            1 -> out = out.uppercase()
        }
        if (random.nextBoolean()) out = out.replace('-', '_')
        when (random.nextInt(4)) {
            0 -> out += "-u-mu-celsius"
            1 -> out += "-x-private"
            2 -> out += "_u_ca_gregory"
        }
        return out
    }

    private fun supportedList(random: Random): List<AppLanguage> {
        val chosen: MutableList<AppLanguage> = mutableListOf()
        val canon: MutableSet<String> = mutableSetOf()
        repeat(1 + random.nextInt(6)) {
            val tag: String = randomTag(random)
            if (canon.add(canonicalTag(tag))) chosen += AppLanguage(tag, true)
        }
        return chosen
    }

    private fun catalog(supported: List<AppLanguage>, device: String?): AppLanguageCatalog =
        createAppLanguageCatalog(supported = supported, systemLanguageCode = { device })

    @Test
    fun `case, underscore and extension spelling of the device tag never changes the answer`() {
        val random = Random(1)
        repeat(20_000) { index ->
            val supported: List<AppLanguage> = supportedList(random)
            val device: String = randomTag(random)
            val plain: AppLanguage = catalog(supported, device).resolveSystemLanguage()
            repeat(3) {
                val spelled: String = mutate(device, random)
                val again: AppLanguage = catalog(supported, spelled).resolveSystemLanguage()
                assertEquals(plain, again, "#$index device '$device' vs '$spelled' over ${supported.map { it.code }}")
            }
        }
    }

    @Test
    fun `case, underscore and extension spelling of the supported codes never changes the answer`() {
        val random = Random(2)
        repeat(20_000) { index ->
            val supported: List<AppLanguage> = supportedList(random)
            val device: String = randomTag(random)
            val respelled: List<AppLanguage> = supported.map { AppLanguage(mutate(it.code.orEmpty(), random), it.isLtr) }
            val a: Int = supported.indexOf(catalog(supported, device).resolveSystemLanguage())
            val b: Int = respelled.indexOf(catalog(respelled, device).resolveSystemLanguage())
            assertEquals(a, b, "#$index device '$device' over ${supported.map { it.code }} vs ${respelled.map { it.code }}")
        }
    }

    @Test
    fun `a device tag equal to a supported code finds exactly that entry`() {
        val random = Random(3)
        repeat(20_000) { index ->
            val supported: List<AppLanguage> = supportedList(random)
            val target: AppLanguage = supported.random(random)
            val device: String = mutate(target.code.orEmpty(), random)
            assertEquals(target, catalog(supported, device).resolveSystemLanguage(), "#$index device '$device' over ${supported.map { it.code }}")
            assertEquals(target, catalog(supported, null).byCode(device), "byCode '$device'")
            assertEquals(target, catalog(supported, null).fromId(device), "fromId '$device'")
        }
    }

    @Test
    fun `the answer is a supported entry of the device language, or the fallback, and a script is never crossed`() {
        val random = Random(4)
        repeat(30_000) { index ->
            val supported: List<AppLanguage> = supportedList(random)
            val device: String = randomTag(random)
            val answer: AppLanguage = catalog(supported, device).resolveSystemLanguage()
            assertTrue(answer in supported, "#$index answer outside supported")
            if (answer == supported.first()) return@repeat // may be the fallback
            val d: LanguageTag = parseLanguageTag(device)
            val a: LanguageTag = parseLanguageTag(answer.code.orEmpty())
            assertEquals(d.language, a.language, "#$index '$device' answered '${answer.code}' over ${supported.map { it.code }}")
            // A bare language is the documented catch-all; an entry that names a region or script is not.
            val onTruncationPath: Boolean = d.subtags.take(a.subtags.size) == a.subtags
            if (a.subtags.size > 1 && !onTruncationPath) {
                assertEquals(d.effectiveScript, a.effectiveScript, "#$index '$device' answered '${answer.code}' over ${supported.map { it.code }}")
            }
        }
    }

    @Test
    fun `an entry of a different language never changes the answer`() {
        val random = Random(5)
        repeat(20_000) { index ->
            val supported: List<AppLanguage> = supportedList(random)
            val device: String = randomTag(random)
            val base: AppLanguage = catalog(supported, device).resolveSystemLanguage()
            val deviceLanguage: String = parseLanguageTag(device).language
            val foreign: AppLanguage = AppLanguage(
                (languages.filter { name -> parseLanguageTag(name).language.let { it != deviceLanguage && it !in supported.map { s -> parseLanguageTag(s.code.orEmpty()).language } } }).let { pool ->
                    if (pool.isEmpty()) return@repeat
                    pool.random(random) + "-" + regions.random(random)
                },
                true,
            )
            val extended: List<AppLanguage> = supported + foreign
            assertEquals(base, catalog(extended, device).resolveSystemLanguage(), "#$index '$device' + '${foreign.code}' over ${supported.map { it.code }}")
        }
    }

    @Test
    fun `an exact region entry beats a sibling listed before it`() {
        val random = Random(6)
        repeat(10_000) { index ->
            val language: String = listOf("pt", "es", "en", "de").random(random)
            val regionsPicked: List<String> = regions.shuffled(random).take(3)
            val supported: List<AppLanguage> = regionsPicked.map { AppLanguage("$language-$it", true) }
            val wanted: AppLanguage = supported.random(random)
            val device: String = wanted.code.orEmpty() + (if (random.nextBoolean()) "-u-ca-gregory" else "")
            assertEquals(wanted, catalog(supported, device).resolveSystemLanguage(), "#$index $device over ${supported.map { it.code }}")
        }
    }

    @Test
    fun `byCode and fromId of an unsupported code are null and System`() {
        val random = Random(7)
        repeat(10_000) { index ->
            val supported: List<AppLanguage> = supportedList(random)
            val cat: AppLanguageCatalog = catalog(supported, null)
            val probe: String = randomTag(random)
            val expected: AppLanguage? = supported.firstOrNull { canonicalTag(it.code.orEmpty()) == canonicalTag(probe) }
            assertEquals(expected, cat.byCode(probe), "#$index byCode($probe)")
            if (expected == null) {
                assertNull(cat.byCode(probe))
                assertEquals(AppLanguage.System, cat.fromId(probe))
            } else {
                assertNotNull(cat.byCode(probe))
            }
        }
    }
}
