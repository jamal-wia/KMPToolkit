package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.JvmSecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 5 (phase B breaker round 2, O6): the store size limit is checked by arithmetic BEFORE a value is
 * encoded, sealed or copied, so a value that cannot fit never allocates its UTF-8 bytes and its sealed copy (on a small heap
 * that used to end in an `OutOfMemoryError` instead of `InvalidValue`). The counters are the arithmetic against the real
 * encoder, and a counting cipher as the proof that nothing was sealed.
 */
class EncryptedStoreSizeRound5Test {

    /** The real cipher that counts the values it was asked to seal. */
    private class CountingCipher : EnvelopeCipher() {
        val sealed: AtomicInteger = AtomicInteger()

        override fun sealEntry(dataKey: ByteArray, kind: StoreKind, key: String, value: ByteArray): EncryptedEntry {
            sealed.incrementAndGet()
            return super.sealEntry(dataKey, kind, key, value)
        }
    }

    private fun <T> withRig(block: suspend SecureTestRig.() -> T): T =
        SecureTestRig().use { rig: SecureTestRig -> runBlocking { withTimeout(HANG_BOUND) { rig.block() } } }

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error

    @Test
    fun `strictUtf8Length is the length of the strict encoding for every kind of character`() {
        val seed = 20_261_115
        val random = Random(seed)
        val alphabet: List<String> = listOf("a", "Z", "~", "é", "ñ", "߿", "ࠀ", "€", "中", "￿", "😀", "𝄞")
        repeat(500) { sample: Int ->
            val text: String = buildString { repeat(random.nextInt(0, 200)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            assertEquals(text.toStrictUtf8().size.toLong(), text.strictUtf8Length(), "seed=$seed sample=$sample")
        }
        for (edge: String in listOf("", "\u007f", "\u0080", "߿", "ࠀ", "￿", "😀", "􏿿")) {
            assertEquals(edge.toStrictUtf8().size.toLong(), edge.strictUtf8Length(), "edge ${edge.map { it.code }}")
        }
    }

    @Test
    fun `a character that has no UTF-8 form counts three bytes and never throws`() {
        assertEquals(3L, "\uD800".strictUtf8Length(), "a lone high surrogate")
        assertEquals(3L, "\uDC00".strictUtf8Length(), "a lone low surrogate")
        assertEquals(5L, "a\uD800b".strictUtf8Length(), "a high surrogate before an ordinary character")
        assertEquals(6L, "\uD800\uD83D".strictUtf8Length(), "two high surrogates in a row are two lone ones")
        assertEquals(1L + 3L, "a\uD83D".strictUtf8Length(), "a high surrogate at the end")
    }

    @Test
    fun `entrySize is what an entry adds to the encoded file and encodedSize is the length encode produces`() {
        val header: HeaderBody = HeaderBody.RawKey(ByteArray(SecureFileFormat.WRAPPED_KEY_BYTES))
        val keyCheck = ByteArray(SecureFileFormat.KEY_CHECK_BYTES)
        val short = EncryptedEntry("a", ByteArray(SecureFileFormat.NONCE_BYTES), ByteArray(SecureFileFormat.TAG_BYTES + 5))
        val long = EncryptedEntry("é€😀", ByteArray(SecureFileFormat.NONCE_BYTES), ByteArray(SecureFileFormat.TAG_BYTES + 1_000))

        for (entries: List<EncryptedEntry> in listOf(emptyList(), listOf(short), listOf(short, long))) {
            assertEquals(SecureFileFormat.encode(header, keyCheck, entries).size.toLong(), SecureFileFormat.encodedSize(header, entries), "${entries.size} entries")
        }
        assertEquals(
            SecureFileFormat.encodedSize(header, listOf(short, long)) - SecureFileFormat.encodedSize(header, listOf(short)),
            SecureFileFormat.entrySize(long.key, 1_000),
            "the entry's own contribution, from the plaintext length",
        )
    }

    @Test
    fun `a value whose entry alone passes the limit is refused before any lock, file or OS call, though it has fewer characters than the limit`() = withRig {
        val limit = 2_000L
        val store: JvmSecureKeyValueStorage = store(maxStoreBytes = limit)
        // 1 000 characters, 3 000 UTF-8 bytes: it passed the earlier character-count bound.
        val value: String = "€".repeat(1_000)

        assertEquals("k", assertIs<StorageError.InvalidValue>(failure(store.put("k", value))).key)

        assertEquals(emptyList(), fileNames(), "no store file and no lock file")
        assertEquals(emptyList(), fake.calls, "no OS call")
    }

    @Test
    fun `a value that cannot fit an existing store is never sealed, and the file is untouched`() = withRig {
        val limit = 4_096L
        val cipher = CountingCipher()
        val store: JvmSecureKeyValueStorage = store(maxStoreBytes = limit, cipher = cipher)
        assertEquals(StorageResult.Success(Unit), store.put("a", "x".repeat(2_000)))
        assertEquals(1, cipher.sealed.get(), "control: a value that is stored is sealed once")
        val before: ByteArray = dataFile().readBytes()

        // 700 characters, 2 100 bytes: its entry fits alone (2 135 bytes), the file with "a" (57 + 2 035) does not.
        val refused: StorageError = failure(store.put("b", "\u20ac".repeat(700)))

        assertEquals("b", assertIs<StorageError.InvalidValue>(refused).key)
        assertEquals(1, cipher.sealed.get(), "the refused value was sealed")
        assertContentEquals(before, dataFile().readBytes(), "the refused put wrote nothing")
        assertEquals(StorageResult.Success("x".repeat(2_000)), store.get("a"))
    }

    @Test
    fun `a first put that cannot fit seals nothing and creates no key, file or item`() = withRig {
        val limit = 4_096L
        val cipher = CountingCipher()
        val addsBefore: Int = fake.count("addGenericPassword")
        val store: JvmSecureKeyValueStorage = store(maxStoreBytes = limit, cipher = cipher)

        // 4 020 characters: its entry (4 055 bytes) fits alone, the file with the 57-byte header (4 112) does not.
        assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(4_020))))

        assertEquals(0, cipher.sealed.get(), "the refused value was sealed after the key was prepared")
        assertTrue(fileNames().none { it == dataFile().name }, "no store file: ${fileNames()}")
        assertEquals(addsBefore, fake.count("addGenericPassword"), "no OS item")
        assertEquals(StorageResult.Success(Unit), store.put("k", "fits"), "control: the same store takes a value that fits")
        assertEquals(1, cipher.sealed.get(), "control: and seals it")
    }

    private companion object {
        val HANG_BOUND = 60.seconds
    }
}
