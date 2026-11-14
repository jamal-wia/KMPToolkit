package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 9 (A2), environment hostility, locale and time zone: the device's default locale (Turkish dotless i, Arabic and Thai
 * digits, a German decimal comma) and time zone (UTC+14, UTC-3:30, UTC+5:45) must change nothing the library computes:
 * header lookup, the journal's entries and expiry, backoff values, the wall clock it reads.
 */
class LocaleAndZoneRound9AdversarialTest {

    private val savedLocale: Locale = Locale.getDefault()
    private val savedZone: TimeZone = TimeZone.getDefault()
    private val root: File = Files.createTempDirectory("r9-locale").toFile()

    @AfterTest
    fun restore() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
        root.deleteRecursively()
    }

    private val locales: List<Locale> = listOf(
        Locale.forLanguageTag("tr-TR"),
        Locale.forLanguageTag("ar-SA-u-nu-arab"),
        Locale.forLanguageTag("th-TH-u-ca-buddhist-nu-thai"),
        Locale.forLanguageTag("de-DE"),
        Locale.forLanguageTag("hi-IN-u-nu-deva"),
        Locale.ROOT,
    )
    private val zones: List<String> = listOf("Pacific/Kiritimati", "America/St_Johns", "Asia/Kathmandu", "Pacific/Apia", "UTC", "Europe/London")

    private fun <T> underEveryEnvironment(block: (Locale, TimeZone) -> T): List<T> =
        locales.flatMap { locale -> zones.map { zone ->
            Locale.setDefault(locale)
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            block(locale, TimeZone.getDefault())
        } }

    @Test
    fun `header lookup ignores case the same way in every locale`() {
        underEveryEnvironment { locale, _ ->
            val response = UploadResponse(200, mapOf("Title" to "t", "X-ID" to "1", "Content-Length" to "5", "ACCEPT-RANGES" to "bytes"), ByteArray(0), false)
            for (name in listOf("title", "TITLE", "Title", "x-id", "X-Id", "content-length", "CONTENT-LENGTH", "accept-ranges", "Accept-Ranges")) {
                assertTrue(response.header(name) != null, "header '$name' not found under $locale")
            }
            assertEquals("t", response.header("TITLE"), "under $locale")
        }
    }

    @Test
    fun `a journal entry round-trips and expires at the same instants in every locale and zone`() {
        val results: List<List<Any?>> = underEveryEnvironment { locale, zone ->
            val clock = TestClock(1_700_000_000_000L)
            val storage = AndroidOutcomeStorage(File(root, "o-${locale.toLanguageTag()}-${zone.id.replace('/', '_')}"), File(root, "p-${locale.toLanguageTag()}-${zone.id.replace('/', '_')}"))
            val journal = OutcomeJournal(storage, clock)
            val response = UploadResponse(201, mapOf("Location" to "/f/1", "TITLE" to "ı İ i I"), "тело 1234567890".encodeToByteArray(), false)
            assertTrue(journal.record("item-1", response), "under $locale $zone")
            val read = journal.inspect("item-1")
            assertIs<JournalEntry.Valid>(read, "under $locale $zone")
            assertEquals(response, read.response, "under $locale $zone")
            clock.millis += 7 * 86_400_000L
            val atSeven: JournalEntry = journal.inspect("item-1")
            clock.millis += 1
            val afterSeven: JournalEntry = journal.inspect("item-1")
            listOf(atSeven is JournalEntry.Valid, afterSeven == JournalEntry.Discarded, read.writtenAtMillis)
        }
        assertTrue(results.all { it == listOf(true, true, 1_700_000_000_000L) }, "differing results: ${results.distinct()}")
    }

    @Test
    fun `backoff values and the wall clock are the same in every locale and zone`() {
        val seeds: List<List<Long>> = underEveryEnvironment { _, _ ->
            val policy = ExponentialBackoffRetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 300_000L, jitterRatio = 0.2, random = Random(5))
            val values: List<Long> = (1..12).map { policy.backoffMillis(it) }
            val now: Long = UploaderClock.System.nowEpochMillis()
            assertTrue(kotlin.math.abs(now - System.currentTimeMillis()) < 5_000L, "the wall clock read differs from the system's under a zone")
            values
        }
        assertEquals(1, seeds.distinct().size, "backoff values depend on the locale or zone")
    }

    @Test
    fun `request and response text forms are the same in every locale`() {
        val texts: List<String> = underEveryEnvironment { _, _ ->
            val request = UploadRequest("https://example.invalid/u", headers = mapOf("Authorization" to "Bearer secret"), fields = listOf(UploadField.Text("a", "1234567")))
            val response = UploadResponse(503, mapOf("Retry-After" to "120"), ByteArray(1234), true)
            request.toString() + "|" + response.toString() + "|" + UploaderConfig().toString()
        }
        assertEquals(1, texts.distinct().size, "text forms depend on the locale: ${texts.distinct()}")
        assertContentEquals(listOf(true), listOf(texts.none { it.contains("secret") }))
    }
}
