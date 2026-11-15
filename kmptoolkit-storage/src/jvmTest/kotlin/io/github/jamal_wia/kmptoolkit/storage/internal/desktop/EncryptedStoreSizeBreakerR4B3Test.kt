package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
 * Breaker round 3 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-3; plan 4.11 and the executor's D-P10):
 * the 256 MiB store limit applied by COUNTING before a value is encoded or sealed (task/sto-desktop ea7fa0a8).
 *
 * Contract under attack (docs/kmptoolkit-storage/05-platform-notes.md "Locks"): a `put` whose encoded file would be larger
 * than the limit is `InvalidValue(key)` before anything is written or created; a file of exactly the limit is written and
 * read back; the size is worked out by counting, so a value that cannot fit never allocates its encoded copies. The sizes
 * here are computed from the frozen format v1 layout (plan 4.3), never from the production counters, so a counter that
 * disagrees with the encoder by one byte is red. Fake binding only; every random choice from a fixed seed named in the message.
 */
class EncryptedStoreSizeBreakerR4B3Test {

    private fun <T> withRig(os: DesktopOs = DesktopOs.MACOS, block: suspend SecureTestRig.() -> T): T =
        SecureTestRig(os).use { rig: SecureTestRig -> runBlocking { withTimeout(HANG_BOUND) { rig.block() } } }

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error

    /** The real cipher, counting the values it seals (the open seam of ea7fa0a8). */
    private class CountingCipher : EnvelopeCipher() {
        val sealed: AtomicInteger = AtomicInteger()

        override fun sealEntry(dataKey: ByteArray, kind: StoreKind, key: String, value: ByteArray): EncryptedEntry {
            sealed.incrementAndGet()
            return super.sealEntry(dataKey, kind, key, value)
        }
    }

    /**
     * Text of exactly [bytes] UTF-8 bytes, mixing one-, two-, three- and four-byte characters (a surrogate pair is ONE
     * four-byte character but TWO chars), chosen from [seed]: a counter that counts chars, or a pair as two three-byte halves,
     * is off.
     */
    private fun mixedText(bytes: Long, seed: Int): String {
        val random = Random(seed)
        val pieces: List<Pair<String, Int>> = listOf("😀" to 4, "中" to 3, "é" to 2, "a" to 1)
        val out = StringBuilder()
        var left: Long = bytes
        while (left > 0) {
            val fitting: List<Pair<String, Int>> = pieces.filter { it.second <= left }
            val piece: Pair<String, Int> = fitting[random.nextInt(fitting.size)]
            out.append(piece.first)
            left -= piece.second
        }
        return out.toString()
    }

    private fun SecureTestRig.quarantined(): List<String> = fileNames().filter { ".undecryptable-" in it }

    // ---- exact boundary, existing file ----

    /**
     * A put into an existing file whose result is EXACTLY the limit is written and read back by a new instance; one byte more
     * is refused with the file byte-identical; and at the limit even an empty value is refused. The value mixes every UTF-8
     * width. Red on: a counter that treats a surrogate pair as 6 bytes or a char as one byte, `>=` for `>`, a header or
     * entry overhead counted differently from the encoder.
     */
    @Test
    fun `an existing file grows to exactly the limit with a mixed-width value and one byte more is refused`() = withRig {
        val seed = 20_261_115
        val limit = 8_192L
        val cipher = CountingCipher()
        val store = store(maxStoreBytes = limit, cipher = cipher)
        assertEquals(StorageResult.Success(Unit), store.put("a", "x"))
        val base: Long = dataFile().length()
        assertEquals(HEADER_OS_ITEM + ENTRY_OVERHEAD + 1 + 1, base, "control: the format v1 layout this test computes from")
        val plain: Long = limit - base - ENTRY_OVERHEAD - "b".length

        val before: ByteArray = dataFile().readBytes()
        val sealedBefore: Int = cipher.sealed.get()
        assertEquals("b", assertIs<StorageError.InvalidValue>(failure(store.put("b", mixedText(plain + 1, seed)))).key, "seed=$seed")
        assertContentEquals(before, dataFile().readBytes(), "seed=$seed: the refused put changed the file")
        assertEquals(sealedBefore, cipher.sealed.get(), "seed=$seed: the refused value was sealed")

        val exact: String = mixedText(plain, seed)
        assertEquals(StorageResult.Success(Unit), store.put("b", exact), "seed=$seed")
        assertEquals(limit, dataFile().length(), "seed=$seed: the file is exactly the limit")

        val fresh = store(cache = OsKeyCache(), maxStoreBytes = limit)
        assertEquals(StorageResult.Success(exact), fresh.get("b"), "seed=$seed: a file of exactly the limit reads back")
        assertEquals(StorageResult.Success("x"), fresh.get("a"), "seed=$seed")

        val full: ByteArray = dataFile().readBytes()
        assertIs<StorageError.InvalidValue>(failure(store.put("c", "")), "seed=$seed: at the limit, even an empty value does not fit")
        assertContentEquals(full, dataFile().readBytes(), "seed=$seed")
        assertEquals(emptyList(), quarantined(), "seed=$seed: nothing was quarantined")
    }

    /**
     * Replacing a key: the old entry goes away, so a value that fits only WITHOUT the old one is accepted, and the file is
     * exactly the limit. Red on: measuring the new entry on top of the file that still holds the old value of the same key.
     */
    @Test
    fun `replacing the only entry with a value that fits only without the old one is accepted at exactly the limit`() = withRig {
        val seed = 7
        val limit = 4_096L
        val store = store(maxStoreBytes = limit)
        assertEquals(StorageResult.Success(Unit), store.put("k", "v".repeat(3_000)))
        val plain: Long = limit - HEADER_OS_ITEM - ENTRY_OVERHEAD - 1

        val replacement: String = mixedText(plain, seed)
        assertEquals(StorageResult.Success(Unit), store.put("k", replacement), "seed=$seed")
        assertEquals(limit, dataFile().length(), "seed=$seed")
        assertEquals(StorageResult.Success(replacement), store(cache = OsKeyCache(), maxStoreBytes = limit).get("k"), "seed=$seed")
    }

    // ---- exact boundary, create path ----

    private class Kind(val label: String, val header: Long, val os: DesktopOs, val open: SecureTestRig.(Long, EnvelopeCipher) -> KeyValueStorage)

    private val kinds: List<Kind> = listOf(
        Kind("macOS OS item", HEADER_OS_ITEM, DesktopOs.MACOS) { limit, cipher -> store(maxStoreBytes = limit, cipher = cipher) },
        Kind("Linux OS item", HEADER_OS_ITEM, DesktopOs.LINUX) { limit, cipher -> store(maxStoreBytes = limit, cipher = cipher) },
        Kind("app raw key", HEADER_RAW_KEY, DesktopOs.MACOS) { limit, cipher ->
            JvmAppKeyEncryptedKeyValueStorage("raw", directory, AppEncryptionKey.Raw { ByteArray(32) { 3 } }, cipher = cipher, maxStoreBytes = limit)
        },
        Kind("app passphrase", HEADER_PASSPHRASE, DesktopOs.MACOS) { limit, cipher ->
            JvmAppKeyEncryptedKeyValueStorage("pass", directory, AppEncryptionKey.Passphrase({ "correct horse".toCharArray() }), cipher = cipher, maxStoreBytes = limit)
        },
    )

    /**
     * The FIRST put of every store type: a value whose new file would be one byte over the limit creates nothing (no file,
     * no OS item, nothing sealed), and the value whose new file is exactly the limit is created and read back. Red on: a create
     * path that measures without the header, or with another header than the one it writes.
     */
    @Test
    fun `the first put of every store type creates a file of exactly the limit and refuses one byte more`() {
        val seed = 314
        val limit = 4_096L
        for (kind: Kind in kinds) {
            withRig(kind.os) {
                val cipher = CountingCipher()
                val store: KeyValueStorage = kind.open(this, limit, cipher)
                val plain: Long = limit - kind.header - ENTRY_OVERHEAD - 1

                assertIs<StorageError.InvalidValue>(failure(store.put("k", mixedText(plain + 1, seed))), "${kind.label} seed=$seed")
                assertEquals(emptyList(), fileNames().filter { it.endsWith(".bin") }, "${kind.label}: a refused first put created a file")
                assertEquals(0, cipher.sealed.get(), "${kind.label}: a refused first put sealed its value")
                assertEquals(0, fake.count("addGenericPassword") + fake.count("store"), "${kind.label}: a refused first put created an OS item")

                val exact: String = mixedText(plain, seed)
                assertEquals(StorageResult.Success(Unit), store.put("k", exact), "${kind.label} seed=$seed")
                val written: File = directory.listFiles()!!.single { it.name.endsWith(".bin") }
                assertEquals(limit, written.length(), "${kind.label} seed=$seed: the first file is exactly the limit")
                assertEquals(StorageResult.Success(exact), kind.open(this, limit, EnvelopeCipher()).get("k"), "${kind.label} seed=$seed: read back by a new instance")
            }
        }
    }

    /**
     * Windows: the header carries the DPAPI blob, whose size is known only after `protect`. The create path must measure with
     * THAT header: exactly the limit is written, one byte more is refused and leaves no file. The header size is measured on
     * another store of the same rig.
     */
    @Test
    fun `a Windows first put measures with the DPAPI header it writes`() = withRig(DesktopOs.WINDOWS) {
        val seed = 99
        val limit = 4_096L
        assertEquals(StorageResult.Success(Unit), store(name = "probe", maxStoreBytes = limit).put("k", ""))
        val header: Long = dataFile(name = "probe").length() - ENTRY_OVERHEAD - 1
        assertTrue(header > HEADER_OS_ITEM, "control: the DPAPI header is larger than an OS-item header ($header)")
        val store = store(maxStoreBytes = limit)
        val plain: Long = limit - header - ENTRY_OVERHEAD - 1

        assertIs<StorageError.InvalidValue>(failure(store.put("k", mixedText(plain + 1, seed))), "seed=$seed")
        assertTrue(!dataFile().exists(), "seed=$seed: a refused first put created the file")
        val exact: String = mixedText(plain, seed)
        assertEquals(StorageResult.Success(Unit), store.put("k", exact), "seed=$seed")
        assertEquals(limit, dataFile().length(), "seed=$seed")
        assertEquals(StorageResult.Success(exact), store(cache = OsKeyCache(), maxStoreBytes = limit).get("k"), "seed=$seed")
    }

    // ---- the create rows that move or replace the user's file ----

    /**
     * A put over a lost key with entries (plan 4.4: quarantine, then create) and over an unparsable file, with a value that
     * cannot fit a new file: refused BEFORE the quarantine, so the user's file stays in place byte for byte and no item is
     * created. And over a lost key with zero entries (replace in place): the empty file is untouched. Red on: a size check
     * after `createFile` (the user's data would be moved aside for a put that then fails).
     */
    @Test
    fun `a value that cannot fit never moves aside or replaces the file it would have replaced`() = withRig {
        val limit = 4_096L
        val tooBig: String = "v".repeat((limit - HEADER_OS_ITEM - ENTRY_OVERHEAD - 1 + 1).toInt())

        // Lost key, entries present.
        assertEquals(StorageResult.Success(Unit), store(maxStoreBytes = limit).put("a", "1"))
        fake.macItems.clear()
        val withEntries: ByteArray = dataFile().readBytes()
        val adds: Int = fake.count("addGenericPassword")
        assertIs<StorageError.InvalidValue>(failure(store(cache = OsKeyCache(), maxStoreBytes = limit).put("b", tooBig)), "lost key, entries")
        assertContentEquals(withEntries, dataFile().readBytes(), "lost key with entries: the user's file was moved or changed")
        assertEquals(emptyList(), quarantined(), "lost key with entries: quarantined for a refused put")
        assertEquals(adds, fake.count("addGenericPassword"), "lost key with entries: an item was created for a refused put")

        // Lost key, zero entries.
        assertEquals(StorageResult.Success(Unit), store(name = "empty", maxStoreBytes = limit).ensureKey())
        fake.macItems.clear()
        val empty: ByteArray = dataFile(name = "empty").readBytes()
        assertIs<StorageError.InvalidValue>(failure(store(name = "empty", cache = OsKeyCache(), maxStoreBytes = limit).put("b", tooBig)), "lost key, zero entries")
        assertContentEquals(empty, dataFile(name = "empty").readBytes(), "lost key with zero entries: the file was replaced")

        // Unparsable file.
        val garbage: ByteArray = ByteArray(100) { it.toByte() }
        dataFile(name = "junk").writeBytes(garbage)
        assertIs<StorageError.InvalidValue>(failure(store(name = "junk", maxStoreBytes = limit).put("b", tooBig)), "unparsable file")
        assertContentEquals(garbage, dataFile(name = "junk").readBytes(), "unparsable file: moved or changed for a refused put")
        assertEquals(emptyList(), quarantined(), "nothing was quarantined anywhere")
    }

    // ---- keys, empty values, surrogates ----

    /**
     * The empty value is a value: stored and read back as "" (never `null`), on the create path and into an existing file; and
     * the limit is hit exactly by the KEY's length alone with an empty value. Red on: a zero-length plaintext treated as absent,
     * or the key counted in chars.
     */
    @Test
    fun `an empty value round-trips and the limit is reached exactly by the key length`() = withRig {
        val seed = 2_718
        val limit = 2_048L
        val store = store(maxStoreBytes = limit)
        assertEquals(StorageResult.Success(Unit), store.put("first", ""))
        assertEquals(StorageResult.Success(""), store(cache = OsKeyCache(), maxStoreBytes = limit).get("first"), "an empty value on the create path")

        val base: Long = dataFile().length()
        val keyBytes: Long = limit - base - ENTRY_OVERHEAD
        val overKey: String = mixedText(keyBytes + 1, seed)
        assertIs<StorageError.InvalidValue>(failure(store.put(overKey, "")), "seed=$seed: a key one byte too long for the file")
        val key: String = mixedText(keyBytes, seed)
        assertEquals(StorageResult.Success(Unit), store.put(key, ""), "seed=$seed")
        assertEquals(limit, dataFile().length(), "seed=$seed")
        assertEquals(StorageResult.Success(""), store(cache = OsKeyCache(), maxStoreBytes = limit).get(key), "seed=$seed: an empty value in an existing file")
    }

    /**
     * Entry names are at most 65 535 UTF-8 BYTES (format v1): a name of exactly 65 535 bytes in three- and four-byte characters
     * is stored and read back, one byte more is `InvalidValue` with no file, no lock file and no OS call. Red on: a key bound in
     * chars, or a pair counted as two characters.
     */
    @Test
    fun `an entry name of exactly 65535 UTF-8 bytes is stored and one byte more is refused before any I O`() = withRig {
        val pairs: String = "😀".repeat(16_383) + "€" // 65 532 + 3 bytes, 32 767 chars
        assertEquals(65_535, pairs.toByteArray(Charsets.UTF_8).size, "control")

        assertIs<StorageError.InvalidValue>(failure(store().put(pairs + "a", "v")), "65 536 bytes")
        assertEquals(emptyList(), fileNames(), "a refused name created a file or a lock file")
        assertEquals(emptyList(), fake.calls, "a refused name made an OS call")

        assertEquals(StorageResult.Success(Unit), store().put(pairs, "v"))
        assertEquals(StorageResult.Success("v"), store(cache = OsKeyCache()).get(pairs))
    }

    /**
     * An unpaired surrogate in the value is refused whatever its counted size: under and over the limit, as `InvalidValue`
     * naming the key, with no file, no lock file, no OS call (the counter's "3 bytes" for such a char never lets it reach
     * the encoder).
     */
    @Test
    fun `a value with an unpaired surrogate is refused under and over the limit before any I O`() = withRig {
        val limit = 1_024L
        for (value: String in listOf("ab\uD800", "\uDC00" + "a".repeat(2_000), "\uD83D".repeat(400))) {
            assertEquals("k", assertIs<StorageError.InvalidValue>(failure(store(maxStoreBytes = limit).put("k", value))).key, "value of ${value.length} chars")
        }
        assertEquals(emptyList(), fileNames())
        assertEquals(emptyList(), fake.calls)
    }

    // ---- huge input on a small heap (a child JVM with a fixed heap: deterministic) ----

    private fun javaCommand(): String =
        ProcessHandle.current().info().command().orElse(null) ?: File(File(System.getProperty("java.home"), "bin"), "java").path

    private fun runChild(directory: File, mode: String): List<String> {
        val process: Process = ProcessBuilder(
            listOf(javaCommand(), "-Xmx128m", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"), HugeInputChild::class.java.name, directory.path, mode),
        ).redirectErrorStream(true).start()
        try {
            val output: CompletableFuture<List<String>> =
                CompletableFuture.supplyAsync { BufferedReader(InputStreamReader(process.inputStream)).use { it.readLines() } }
            val lines: List<String> = output.get(CHILD_BOUND_SECONDS, TimeUnit.SECONDS)
            assertTrue(process.waitFor(CHILD_BOUND_SECONDS, TimeUnit.SECONDS), "the child did not exit")
            return lines
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * Control for O6 (the fix of ea7fa0a8), on a 128 MiB heap: a VALUE of 16 million three-byte characters (a 32 MB String,
     * 48 MB of UTF-8) over a store limit of 1 MiB is `InvalidValue`, because it is counted, never encoded. Red on: encoding the value before the bound.
     */
    @Test
    fun `a huge value is refused by counting on a small heap`() = withRig {
        assertEquals(listOf("InvalidValue"), runChild(directory, "value").filter { it.isNotBlank() }.takeLast(1))
    }

    /**
     * The same input as the KEY: a key of 16 million three-byte characters is over the 65 535-byte name limit and must be
     * `InvalidValue` the same way. Red on: `execute` measuring the key with `toStrictUtf8()` (EncryptedStoreEngine.kt:150),
     * which builds the whole encoding (a growing ByteBuffer of up to 70 MB plus a 48 MB copy next to the 32 MB String) and
     * ends in an `OutOfMemoryError` thrown out of `put` instead of a typed failure.
     */
    @Test
    fun `a huge key is refused by counting on a small heap, not with an OutOfMemoryError`() = withRig {
        assertEquals(listOf("InvalidValue"), runChild(directory, "key").filter { it.isNotBlank() }.takeLast(1))
    }

    private companion object {
        val HANG_BOUND = 120.seconds
        const val CHILD_BOUND_SECONDS: Long = 120

        /** Magic 4, version 1, kind 1, body length 2, the 28-byte key-check, the 4-byte count (plan 4.3). */
        const val FIXED: Long = 4 + 1 + 1 + 2 + 28 + 4

        /** Kind 1 body: fingerprint 16, OS tag 1. */
        const val HEADER_OS_ITEM: Long = FIXED + 17

        /** Kind 4 body: the wrapped key, 60. */
        const val HEADER_RAW_KEY: Long = FIXED + 60

        /** Kind 3 body: salt 16, iterations 4, wrapped key 60. */
        const val HEADER_PASSPHRASE: Long = FIXED + 80

        /** Per entry: name length 2, nonce 12, ciphertext length 4, tag 16 (plus the name and the plaintext). */
        const val ENTRY_OVERHEAD: Long = 2 + 12 + 4 + 16
    }
}

/**
 * Child JVM of [EncryptedStoreSizeBreakerR4B3Test]: puts a 16-million-character text as the value (`args[1]` = `value`) or as
 * the key (`key`) into an app-key store in `args[0]` with a 1 MiB limit, and prints the simple name of the error, or `Success`, or
 * `THREW <class>` when `put` threw.
 */
object HugeInputChild {
    @JvmStatic
    fun main(args: Array<String>) {
        // A 1 MiB store limit: the 48 MB value is over it, and the bound must be found by counting.
        val store = JvmAppKeyEncryptedKeyValueStorage("huge", File(args[0]), AppEncryptionKey.Raw { ByteArray(32) { 5 } }, maxStoreBytes = 1L shl 20)
        val line: String = try {
            val huge: String = "€".repeat(16_000_000)
            val result: StorageResult<Unit> = runBlocking { if (args[1] == "key") store.put(huge, "v") else store.put("k", huge) }
            when (result) {
                is StorageResult.Success -> "Success"
                is StorageResult.Failure -> result.error::class.java.simpleName
            }
        } catch (error: Throwable) {
            "THREW ${error::class.java.name}"
        }
        println(line)
        System.out.flush()
    }
}
