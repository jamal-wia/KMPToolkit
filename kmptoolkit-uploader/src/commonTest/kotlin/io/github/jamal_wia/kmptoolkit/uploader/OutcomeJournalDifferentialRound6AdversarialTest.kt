package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * The real storage of the platform under test and how to remove its files. [keepsCaseApart]: whether ids that differ only
 * by letter case must stay two entries here (always on iOS, whose volume folds case; on Android only where the host volume
 * is case-sensitive, as a device's is: the Android storage names its files after the id as they are).
 */
internal class StorageUnderTest(val storage: OutcomeStorage, val keepsCaseApart: Boolean, val cleanUp: () -> Unit)

/** A fresh [OutcomeStorage] on the real file system of this platform (Android files or the iOS volume). */
internal expect fun newStorageUnderTest(): StorageUnderTest

/**
 * Round 6 (differential): the same random script of journal operations, over ids of every alphabet, runs in the Android
 * host source set and in the iOS one against the real file storage of each platform and is compared with one pure model of
 * the contract, so both platforms are equal to the model and therefore to each other: what was recorded reads back, what
 * was deleted is gone, the sweep lists exactly the recorded ids and hands each its own response.
 */
class OutcomeJournalDifferentialRound6AdversarialTest {

    private val underTest: StorageUnderTest = newStorageUnderTest()
    private val journal = OutcomeJournal(underTest.storage, UploaderClock.System)

    @AfterTest
    fun cleanUp() = underTest.cleanUp()

    private class Model(val response: UploadResponse, val stamp: Int?)

    private val ids: List<String> = listOf(
        "a", "b2", "item.1", ".dot", "~", "~~0041", "a~b", "%41", "a:b", "a|b", "a*b", "CON", "\u65E5\u672C\u8A9E", "\u0424\u043E\u0442\u043E",
        "\uD83D\uDE00", "a\u00A0b", "UPPER-CASE-ID-WITH-DASHES", "0", "-", "_",
    ) + if (underTest.keepsCaseApart) {
        listOf(
            "A", "Photo-AbC", "photo-abc", "PHOTO-ABC", "\u00E9", "e\u0301", "\u00C9", "\u0424\u041E\u0422\u041E",
            "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF", "abcdefab-abcd-abcd-abcd-abcdefabcdef",
        )
    } else {
        emptyList()
    }

    private fun response(random: Random): UploadResponse = UploadResponse(
        statusCode = random.nextInt(200, 600),
        headers = (0 until random.nextInt(0, 4)).associate { "H-${random.nextInt(3)}" to "v${random.nextInt(100)}" },
        body = ByteArray(random.nextInt(0, 300)) { random.nextInt().toByte() },
        isBodyTruncated = random.nextInt(5) == 0,
    )

    @Test
    fun `a random script over odd ids ends equal to the model on this platform`() = runTest {
        for (seed in 0 until 6) {
            val random = Random(66_000 + seed)
            val model: MutableMap<String, Model> = linkedMapOf()
            val trace: MutableList<String> = mutableListOf()
            repeat(150) { step ->
                val id: String = ids[random.nextInt(ids.size)]
                val kind: Int = random.nextInt(10)
                trace += "$step:${if (kind <= 4) "record" else if (kind <= 6) "delete" else "peek"}:" + id.map { if (it.code in 0x21..0x7e) it.toString() else "\\u" + it.code.toString(16) }.joinToString("")
                when (kind) {
                    in 0..4 -> {
                        val stored: UploadResponse = response(random)
                        val stamp: Int? = if (random.nextBoolean()) random.nextInt(0, 9) else null
                        val limit: Int = listOf(0, 10, 64, 100_000)[random.nextInt(4)]
                        assertEquals(true, journal.record(id, stored, limit, stamp), "seed $seed step $step record '$id'")
                        model[id] = Model(stored.enforced(limit.coerceIn(0, MAX_RESPONSE_BODY_BYTES)), stamp)
                    }
                    in 5..6 -> {
                        journal.delete(id)
                        model.remove(id)
                    }
                    else -> Unit
                }
                val entry: JournalEntry = journal.inspect(id)
                val expected: Model? = model[id]
                if (expected == null) {
                    assertEquals(JournalEntry.Absent, entry, "seed $seed step $step '$id' reads an entry the model does not have")
                } else {
                    assertIs<JournalEntry.Valid>(entry, "seed $seed step $step '$id' lost its entry")
                    assertEquals(expected.response.statusCode, entry.response.statusCode, "seed $seed step $step '$id' status")
                    assertEquals(expected.response.headers, entry.response.headers, "seed $seed step $step '$id' headers")
                    assertContentEquals(expected.response.body(), entry.response.body(), "seed $seed step $step '$id' reads another entry's body")
                    assertEquals(expected.response.isBodyTruncated, entry.response.isBodyTruncated, "seed $seed step $step '$id' truncated flag")
                    assertEquals(expected.stamp, entry.attemptStamp, "seed $seed step $step '$id' stamp")
                }
                assertEquals(model.keys.sorted(), underTest.storage.itemIds().sorted(), "seed $seed step $step: the sweep would list other ids; case apart=${underTest.keepsCaseApart}; last ops ${trace.takeLast(6)}")
            }
            val replayed: MutableMap<String, UploadResponse> = linkedMapOf()
            journal.sweep { itemId, stored, _, _ ->
                replayed[itemId] = stored
                JournalSettleOutcome.LANDED
            }
            assertEquals(model.keys.sorted(), replayed.keys.sorted(), "seed $seed: the sweep replayed other ids than were recorded")
            model.forEach { (id, m) -> assertContentEquals(m.response.body(), replayed.getValue(id).body(), "seed $seed: '$id' replayed another body") }
            assertEquals(emptyList(), underTest.storage.itemIds(), "seed $seed: the sweep left entries behind")
        }
    }
}
