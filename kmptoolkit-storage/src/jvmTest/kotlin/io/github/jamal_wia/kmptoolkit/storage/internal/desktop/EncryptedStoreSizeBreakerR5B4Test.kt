package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 4 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-4), against task/sto-desktop 9dadc3e4:
 * the round-6 fixes B-O8 (a first put is measured against the smallest file of the store's kind BEFORE any key material is
 * asked for) and B-m1 (the entry name is counted, never encoded, before its 65 535-byte bound).
 *
 * Contract (05-platform-notes "A store file is at most 256 MiB", D-P10): a first put whose entry cannot fit even the smallest
 * file of its kind answers `InvalidValue(key)` and makes NO key-store call (no keychain delete/add, no Linux preflight, no
 * DPAPI protect, no provider, no PBKDF2), creates no store file and moves nothing to quarantine; a value that fits is never
 * refused by that check (it is exact for kinds 1, 3, 4; for DPAPI it is a lower bound, so DPAPI may be asked first only in the
 * window between the smallest and the real DPAPI header). The window between the pre-lock bound (the entry alone) and the
 * smallest header is what these tests probe, at its exact edges, per kind.
 *
 * Fake binding only; small store limit ([LIMIT]); hang bounds only.
 */
class EncryptedStoreSizeBreakerR5B4Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-size-b4").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(HANG_BOUND) { block() } }

    /** Bytes an entry adds to a file, from the format text (2 + name + nonce 12 + length 4 + value + tag 16). */
    private fun entryBytes(keyBytes: Int, valueBytes: Int): Int = 2 + keyBytes + 12 + 4 + valueBytes + 16

    /** The value length that makes a file of [headerFileBytes] plus one entry named "k" exactly [LIMIT] bytes. */
    private fun exactValue(headerFileBytes: Int): Int = LIMIT.toInt() - headerFileBytes - entryBytes(1, 0)

    private fun SecureTestRig.quarantined(): List<String> = fileNames().filter { ".undecryptable-" in it }

    /**
     * macOS and Linux (kind 1, exact): the value one byte over the exact fit is refused with NO native call at all (Linux
     * without UI would otherwise ask `defaultCollectionLocked` in its preflight; macOS would delete and add an item), no store
     * file; the exact fit is created at exactly [LIMIT] bytes. Red on: the pre-check after `createPlan` (native calls seen),
     * a pre-check with a header that is too large (exact fit refused), or one that is too small (no difference to round 5).
     */
    @Test
    fun `a first OS-item put one byte over the exact fit makes no native call and the exact fit is created`() = run {
        for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
            SecureTestRig(os).use { rig: SecureTestRig ->
                val store = rig.store(maxStoreBytes = LIMIT, options = DesktopSecureStorageOptions(allowUserInteraction = false))
                val exact: Int = exactValue(OS_ITEM_FILE)

                val refused = assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(exact + 1))), "$os")
                assertEquals("k", refused.key, "$os")
                assertEquals(emptyList(), rig.fake.callNames, "$os: a key-store call was made for a value that can never fit")
                assertTrue(!rig.dataFile().exists(), "$os: a store file was created")

                assertEquals(StorageResult.Success(Unit), store.put("k", "v".repeat(exact)), "$os: the exact fit must be accepted")
                assertEquals(LIMIT, rig.dataFile().length(), "$os: control, the file is exactly the limit")
                assertTrue(rig.fake.callNames.isNotEmpty(), "$os: control, creating the store did call the key store")
            }
        }
    }

    /**
     * Windows (kind 2, a lower bound): over the SMALLEST DPAPI file, no `protect` is made; inside the window between the
     * smallest and the real header, `protect` is made once and the answer is still `InvalidValue` with no file (the documented
     * residual); the exact fit with the real header is created at exactly [LIMIT]. Red on: `protect` for a value over the
     * smallest file, a fitting value refused, or a value inside the window answered with anything but `InvalidValue`.
     */
    @Test
    fun `a first DPAPI put is refused without protect over the smallest file and only the residual window asks DPAPI`() = run {
        SecureTestRig(DesktopOs.WINDOWS).use { rig: SecureTestRig ->
            val store = rig.store(maxStoreBytes = LIMIT)
            val overSmallest: Int = exactValue(DPAPI_SMALLEST_FILE) + 1

            assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(overSmallest))))
            assertEquals(0, rig.fake.count("protect"), "DPAPI was asked for a value that cannot fit even the smallest file")
            assertTrue(!rig.dataFile().exists())

            // Inside the window: fits the smallest header, not the real one (the fake's blob is 46 bytes).
            assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(overSmallest - 1))), "the residual window")
            assertEquals(1, rig.fake.count("protect"), "control: the residual window asks DPAPI exactly once")
            assertTrue(!rig.dataFile().exists(), "a file was created for a value that does not fit the real header")

            assertEquals(StorageResult.Success(Unit), store.put("k", "v".repeat(exactValue(DPAPI_FAKE_FILE))), "the exact fit with the real header")
            assertEquals(LIMIT, rig.dataFile().length(), "control: exactly the limit")
        }
    }

    /**
     * App-key store, both kinds (3 and 4, exact): one byte over the exact fit never calls the provider (so never runs PBKDF2);
     * the exact fit calls it once and is created at exactly [LIMIT]. Then `changeKey` from raw to passphrase on an ABSENT
     * file binds the new key without a file: the next first put must be measured with the NEW kind's header (a stale kind
     * would accept a value that cannot fit, or refuse one that does).
     */
    @Test
    fun `a first app-key put is measured with the kind of the bound key, also after changeKey on an absent store`() = run {
        val calls = AtomicInteger()
        val raw = AppEncryptionKey.Raw { calls.incrementAndGet(); ByteArray(32) { 3 } }
        val passphrase = AppEncryptionKey.Passphrase(provider = { calls.incrementAndGet(); "pass phrase b4".toCharArray() })

        for ((label: String, key: AppEncryptionKey, header: Int) in listOf(Triple("raw", raw, RAW_FILE), Triple("passphrase", passphrase, PASSPHRASE_FILE))) {
            val dir: File = File(directory, label).apply { mkdirs() }
            val store = JvmAppKeyEncryptedKeyValueStorage("b4", dir, key, Dispatchers.IO, CrossProcessLock(), maxStoreBytes = LIMIT)
            calls.set(0)
            assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(exactValue(header) + 1))), label)
            assertEquals(0, calls.get(), "$label: the provider was asked for a value that can never fit")
            assertTrue(!File(dir, "${appKeyStoreId("b4")}.bin").exists(), "$label: a file was created")
            assertEquals(StorageResult.Success(Unit), store.put("k", "v".repeat(exactValue(header))), "$label: the exact fit")
            assertEquals(LIMIT, File(dir, "${appKeyStoreId("b4")}.bin").length(), "$label: control, exactly the limit")
            assertEquals(1, calls.get(), "$label: control, the provider was asked once")
        }

        // Raw-bound instance re-bound to a passphrase while it has no file: the bigger (passphrase) header now applies.
        val dir: File = File(directory, "rebound").apply { mkdirs() }
        val store = JvmAppKeyEncryptedKeyValueStorage("b4", dir, raw, Dispatchers.IO, CrossProcessLock(), maxStoreBytes = LIMIT)
        assertEquals(StorageResult.Success(Unit), store.changeKey(passphrase), "changeKey on an absent store")
        calls.set(0)
        val fitsRawOnly: Int = exactValue(RAW_FILE)
        assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(fitsRawOnly))), "a value that fits only the old kind's header")
        assertEquals(0, calls.get(), "the passphrase provider was asked for a value that cannot fit a passphrase file")
        assertEquals(StorageResult.Success(Unit), store.put("k", "v".repeat(exactValue(PASSPHRASE_FILE))))
        assertEquals(LIMIT, File(dir, "${appKeyStoreId("b4")}.bin").length())
    }

    /**
     * The lost-key rows (plan 4.4) go through `create` too: an existing file with an entry whose item is gone, on macOS and on
     * Linux without UI (whose create would run a preflight call). A put that cannot fit even the smallest file must not run the
     * preflight, delete or add an item, or move the user's file to quarantine (the only call allowed is the fetch that found
     * the key lost); the same for an unparsable file (AfterQuarantine). Red on: a quarantine or a key-store call other than
     * the fetch for a value that is then refused.
     */
    @Test
    fun `a put that cannot fit the smallest file never quarantines or touches the key store on the lost-key and unparsable rows`() = run {
        for ((os: DesktopOs, fetch: String) in listOf(DesktopOs.MACOS to "copyGenericPassword", DesktopOs.LINUX to "lookup")) {
            SecureTestRig(os).use { rig: SecureTestRig ->
                // macOS needs UI for a definitive not-found (conservative rule of 4.5); Linux without UI runs the preflight call.
                val options = DesktopSecureStorageOptions(allowUserInteraction = os == DesktopOs.MACOS)
                assertEquals(StorageResult.Success(Unit), rig.store(maxStoreBytes = LIMIT, options = options).put("a", "1"), "$os: control")
                rig.fake.macItems.clear()
                rig.fake.secretItems.clear()
                val before: ByteArray = rig.dataFile().readBytes()
                val calls: Int = rig.fake.calls.size

                val store = rig.store(maxStoreBytes = LIMIT, cache = OsKeyCache(), options = options)
                assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(exactValue(OS_ITEM_FILE) + 1))), "$os: lost key")
                assertEquals(listOf(fetch), rig.fake.callNames.drop(calls), "$os lost key: only the fetch may run for a value that cannot fit")
                assertContentEquals(before, rig.dataFile().readBytes(), "$os lost key: the user's file changed")
                assertEquals(emptyList(), rig.quarantined(), "$os lost key: moved to quarantine for a refused put")
                assertTrue(store.put("k", "fits") is StorageResult.Success, "$os: control, a fitting put repairs the store")

                rig.dataFile().writeBytes("not a store file".toByteArray())
                val garbage: ByteArray = rig.dataFile().readBytes()
                val calls2: Int = rig.fake.calls.size
                val quarantinedBefore: List<String> = rig.quarantined()
                assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(exactValue(OS_ITEM_FILE) + 1))), "$os: unparsable")
                assertEquals(emptyList(), rig.fake.callNames.drop(calls2), "$os unparsable: a key-store call for a value that cannot fit")
                assertContentEquals(garbage, rig.dataFile().readBytes(), "$os unparsable: the file changed")
                assertEquals(quarantinedBefore, rig.quarantined(), "$os unparsable: moved to quarantine for a refused put")
            }
        }
    }

    /**
     * B-m1 boundaries with mixed widths: a seeded name of 1-, 2-, 3- and 4-byte characters (surrogate pairs) of exactly 65 535
     * UTF-8 bytes is stored and read back, on the create path AND on an existing file, on both store types; the same name plus
     * one ASCII character (65 536) is refused with `InvalidValue` before any I/O (no file, no provider). A counter that
     * disagrees with the encoder by one for any width turns this red.
     */
    @Test
    fun `a mixed-width entry name of exactly 65535 bytes is stored and one byte more is refused before any I O`() = run {
        val seed = 0xB4L
        val name: String = mixedName(Random(seed), SecureFileFormat.MAX_ENTRY_KEY_BYTES)
        assertEquals(SecureFileFormat.MAX_ENTRY_KEY_BYTES, name.toByteArray(Charsets.UTF_8).size, "seed=$seed: control, the name is exactly the bound")
        assertTrue(name.any { it.isHighSurrogate() } && name.any { it.code in 0x80..0x7FF } && name.any { it.code in 0x800..0xD7FF }, "seed=$seed: control, all widths")
        val tooLong: String = name + "x"

        val calls = AtomicInteger()
        val key = AppEncryptionKey.Raw { calls.incrementAndGet(); ByteArray(32) { 5 } }
        SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
            val stores: List<Pair<String, () -> EncryptedKeyValueStorage>> = listOf(
                "OS-key" to { rig.store() },
                "app-key" to { JvmAppKeyEncryptedKeyValueStorage("names", rig.directory, key, Dispatchers.IO, CrossProcessLock()) },
            )
            for ((label: String, open: () -> EncryptedKeyValueStorage) in stores) {
                val store: EncryptedKeyValueStorage = open()
                val before: List<String> = rig.fileNames()
                assertIs<StorageError.InvalidValue>(failure(store.put(tooLong, "v")), "seed=$seed $label: 65 536 bytes on the create path")
                assertEquals(before, rig.fileNames(), "seed=$seed $label: the refused name created a file")
                assertEquals(StorageResult.Success(Unit), store.put(name, "first"), "seed=$seed $label: exactly the bound, create path")
                assertEquals(StorageResult.Success(Unit), store.put(name, "second"), "seed=$seed $label: exactly the bound, existing file")
                assertIs<StorageError.InvalidValue>(failure(store.put(tooLong, "v")), "seed=$seed $label: 65 536 bytes on an existing file")
                assertEquals(StorageResult.Success("second"), store.get(name), "seed=$seed $label: read back")
                assertEquals(StorageResult.Success(null), store.get(tooLong), "seed=$seed $label: the refused name was stored")
            }
            assertEquals(1, calls.get(), "seed=$seed: the provider is asked once for the one app-key instance, never for a refused name")
        }
    }

    /** A name of exactly [bytes] UTF-8 bytes from 1/2/3/4-byte characters, in an order drawn from [random]. */
    private fun mixedName(random: Random, bytes: Int): String {
        val builder = StringBuilder()
        var left: Int = bytes
        while (left > 0) {
            val width: Int = minOf(left, 1 + random.nextInt(4))
            when (width) {
                1 -> builder.append('a' + random.nextInt(26))
                2 -> builder.append((0x400 + random.nextInt(0x100)).toChar())
                3 -> builder.append((0x4E00 + random.nextInt(0x1000)).toChar())
                else -> builder.appendCodePoint(0x1F600 + random.nextInt(0x40))
            }
            left -= width
        }
        return builder.toString()
    }

    private companion object {
        const val LIMIT: Long = 4_096
        /** Prefix 8 + body + key-check 28 + count 4, per kind (format v1). */
        const val OS_ITEM_FILE: Int = 8 + 17 + 28 + 4
        const val RAW_FILE: Int = 8 + 60 + 28 + 4
        const val PASSPHRASE_FILE: Int = 8 + 80 + 28 + 4
        /** The smallest DPAPI file: a 1-byte blob with its u16 length. */
        const val DPAPI_SMALLEST_FILE: Int = 8 + 2 + 1 + 28 + 4
        /** The fake's DPAPI blob of a 32-byte key: magic 6 + entropy tag 8 + 32. */
        const val DPAPI_FAKE_FILE: Int = 8 + 2 + 46 + 28 + 4
        val HANG_BOUND = 120.seconds
    }
}
