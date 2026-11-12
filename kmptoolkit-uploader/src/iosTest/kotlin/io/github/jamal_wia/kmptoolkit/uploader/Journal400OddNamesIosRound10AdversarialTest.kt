package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create

/**
 * Round 10 (compatibility with 4.0.0, iOS): ids outside the plain alphabet that 4.0.0 journaled under their own spelling
 * (`dir/<id>`): accents, scripts, emoji, `~`, `%`, `:`, `|`, `*`. docs 05: "Entries a version before 4.1.0 wrote under the id's
 * own spelling stay readable, are replayed by the sweep and are replaced when the id writes again". Each entry must be
 * found by `inspect(id)` (the transport's re-hand reads it that way) and handed to the sweep under that id, never under
 * another one and never dropped.
 */
@OptIn(ExperimentalForeignApi::class)
class Journal400OddNamesIosRound10AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r10o-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"
    private val now = 1_700_000_000_000L

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    private val oddIds: List<String> = listOf(
        "a~b", "~", "x~", "%41", "a:b", "a|b", "a*b", "CON", "фото", "日本語", "😀", "café",
        "a b", "~_a", "~x", "a~~0041", "id+plus", "id,comma", "id=eq", "id@at", "id#hash", "id(1)",
    )

    private fun responseFor(id: String) = UploadResponse(201, mapOf("x-id" to "v"), id.encodeToByteArray(), false)

    private fun writeRaw(name: String, bytes: ByteArray): Boolean {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val data: NSData = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
        return NSFileManager.defaultManager.createFileAtPath("$dir/$name", data, attributes = null)
    }

    private fun seedable(): List<String> = oddIds.filter { isJournalableId(it) }.filter { writeRaw(it, OutcomeCodec.encode(responseFor(it), now, null)) }

    @Test
    fun `inspect finds the entry 4_0_0 wrote under an id outside the plain alphabet`() {
        val seeded: List<String> = seedable()
        assertTrue(seeded.size >= 15, "the volume refused too many names: $seeded")
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        val notFound: List<String> = seeded.filter { journal.inspect(it) !is JournalEntry.Valid }.map { id -> "$id -> ${journal.inspect(id)}" }
        assertTrue(notFound.isEmpty(), "legacy entries not found by their id: $notFound")
    }

    @Test
    fun `the sweep hands every such 4_0_0 entry over under its own id exactly once`() = runBlocking {
        val seeded: List<String> = seedable()
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        val handed: MutableList<Pair<String, String>> = mutableListOf()
        journal.sweep { id, response, _, onSettling ->
            onSettling()
            handed += id to response.body().decodeToString()
            JournalSettleOutcome.LANDED
        }
        // the body of an entry is its own id: the id the sweep reports must be the one the entry was written for
        val wrong: List<Pair<String, String>> = handed.filter { it.first != it.second }
        assertTrue(wrong.isEmpty(), "entries handed under another id (reported id, written for): $wrong")
        assertEquals(seeded.sorted(), handed.map { it.second }.sorted(), "every legacy entry replayed once")
    }
}
