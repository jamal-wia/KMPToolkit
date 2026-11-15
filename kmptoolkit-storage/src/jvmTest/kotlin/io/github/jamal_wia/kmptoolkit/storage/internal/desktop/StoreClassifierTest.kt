package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Plan Q5: any parse failure of a store file is `FileState.Unparsable` (so `Undecryptable(null)` above), decided in ONE
 * classification step for both encrypted stores; only a newer format version is marked, because only `clear()` may touch
 * it. Group `sto-desktop`, phase B (6d).
 */
class StoreClassifierTest {

    private val osKinds: Set<StoreKind> = setOf(StoreKind.OS_ITEM, StoreKind.DPAPI)
    private val appKinds: Set<StoreKind> = setOf(StoreKind.PASSPHRASE, StoreKind.RAW_KEY)

    private fun unparsable(state: FileState): FileState.Unparsable = assertIs(state)

    @Test
    fun `no file is Absent and a golden file of the right kind is Parsed`() {
        assertEquals(FileState.Absent, StoreClassifier.classify(null, osKinds))
        assertEquals(StoreKind.OS_ITEM, assertIs<FileState.Parsed>(StoreClassifier.classify(GoldenStoreFiles.KIND_1_OS_ITEM, osKinds)).file.kind)
        assertEquals(StoreKind.DPAPI, assertIs<FileState.Parsed>(StoreClassifier.classify(GoldenStoreFiles.KIND_2_DPAPI, osKinds)).file.kind)
        assertEquals(StoreKind.PASSPHRASE, assertIs<FileState.Parsed>(StoreClassifier.classify(GoldenStoreFiles.KIND_3_PASSPHRASE, appKinds)).file.kind)
        assertEquals(StoreKind.RAW_KEY, assertIs<FileState.Parsed>(StoreClassifier.classify(GoldenStoreFiles.KIND_4_RAW_KEY, appKinds)).file.kind)
    }

    @Test
    fun `a kind that does not belong to this store type is Unparsable, never opened`() {
        for (golden: ByteArray in listOf(GoldenStoreFiles.KIND_3_PASSPHRASE, GoldenStoreFiles.KIND_4_RAW_KEY)) {
            assertEquals("KIND_NOT_OF_THIS_STORE", unparsable(StoreClassifier.classify(golden, osKinds)).reason)
        }
        for (golden: ByteArray in listOf(GoldenStoreFiles.KIND_1_OS_ITEM, GoldenStoreFiles.KIND_2_DPAPI)) {
            assertEquals("KIND_NOT_OF_THIS_STORE", unparsable(StoreClassifier.classify(golden, appKinds)).reason)
        }
        // Kind swap 1 <-> 3 by editing the kind byte of a golden file: the structure then fails or the kind is foreign.
        val swapped: ByteArray = GoldenStoreFiles.KIND_1_OS_ITEM.copyOf().also { it[5] = 3 }
        assertFalse(StoreClassifier.classify(swapped, osKinds) is FileState.Parsed)
    }

    @Test
    fun `an iteration count outside the accepted range is Unparsable before any derivation`() {
        for (iterations: Long in listOf(0L, 1L, 599_999L, 10_000_001L, SecureFileFormat.UINT_MAX)) {
            val bytes: ByteArray = GoldenStoreFiles.KIND_3_PASSPHRASE.copyOf()
            // magic 4, version 1, kind 1, length 2, salt 16, then the u32 iteration count.
            ByteBuffer.wrap(bytes).putInt(4 + 1 + 1 + 2 + 16, iterations.toInt())
            assertEquals("ITERATIONS_OUT_OF_RANGE", unparsable(StoreClassifier.classify(bytes, appKinds)).reason, "iterations=$iterations")
        }
        for (iterations: Long in listOf(600_000L, 10_000_000L)) {
            val bytes: ByteArray = GoldenStoreFiles.KIND_3_PASSPHRASE.copyOf()
            ByteBuffer.wrap(bytes).putInt(4 + 1 + 1 + 2 + 16, iterations.toInt())
            assertIs<FileState.Parsed>(StoreClassifier.classify(bytes, appKinds), "iterations=$iterations is in the range")
        }
    }

    @Test
    fun `only valid magic with a version above 1 is marked newer`() {
        for (version: Int in listOf(2, 3, 127, 255)) {
            val bytes: ByteArray = GoldenStoreFiles.KIND_1_OS_ITEM.copyOf().also { it[4] = version.toByte() }
            assertTrue(unparsable(StoreClassifier.classify(bytes, osKinds)).newerVersion, "version $version")
        }
        val versionZero: ByteArray = GoldenStoreFiles.KIND_1_OS_ITEM.copyOf().also { it[4] = 0 }
        assertFalse(unparsable(StoreClassifier.classify(versionZero, osKinds)).newerVersion, "version 0 is garbage, not newer")
        val badMagic: ByteArray = GoldenStoreFiles.KIND_1_OS_ITEM.copyOf().also { it[0] = 'X'.code.toByte(); it[4] = 2 }
        assertFalse(unparsable(StoreClassifier.classify(badMagic, osKinds)).newerVersion, "a foreign file is never 'newer'")
        // Too short to carry a header: truncation is reported before the version is judged, so it is not "newer" either.
        val onlyMagicAndVersion: ByteArray = SecureFileFormat.MAGIC + byteArrayOf(2)
        assertEquals("TRUNCATED", unparsable(StoreClassifier.classify(onlyMagicAndVersion, osKinds)).reason)
        assertFalse(unparsable(StoreClassifier.classify(onlyMagicAndVersion, osKinds)).newerVersion)
    }

    @Test
    fun `every kind of garbage is Unparsable and never throws`() {
        val seed = 20_261_115L
        val random = Random(seed)
        val goldens: List<ByteArray> = listOf(
            GoldenStoreFiles.KIND_1_OS_ITEM, GoldenStoreFiles.KIND_2_DPAPI, GoldenStoreFiles.KIND_3_PASSPHRASE, GoldenStoreFiles.KIND_4_RAW_KEY,
        )
        assertTrue(unparsable(StoreClassifier.classify(ByteArray(0), osKinds)).reason.isNotEmpty(), "an empty file is not a store")
        for (golden: ByteArray in goldens) {
            for (length: Int in 0 until golden.size step 7) {
                assertFalse(StoreClassifier.classify(golden.copyOf(length), osKinds + appKinds) is FileState.Parsed, "seed=$seed truncated to $length")
            }
            repeat(200) { round: Int ->
                val copy: ByteArray = golden.copyOf()
                repeat(1 + random.nextInt(4)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
                // Whatever it is, classification answers and never throws.
                StoreClassifier.classify(copy, osKinds + appKinds).also { check(it !is FileState.Absent) { "seed=$seed round=$round" } }
            }
            val trailing: ByteArray = golden + byteArrayOf(0)
            assertEquals("TRAILING_BYTES", unparsable(StoreClassifier.classify(trailing, osKinds + appKinds)).reason)
        }
        repeat(500) { StoreClassifier.classify(Random(seed + it).nextBytes(random.nextInt(0, 300)), osKinds + appKinds) }
    }
}
