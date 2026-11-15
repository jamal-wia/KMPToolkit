package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Breaker round 2 of group sto-desktop (phase A): format v1 and the AES-GCM envelope (plan 4.3) against a hostile file,
 * exhaustively instead of by sample.
 *
 * - Every truncation of a valid file (each prefix length) and every single-bit flip of it: the parser never throws, and a
 *   damaged file never yields the original plaintexts as if nothing happened. A flip either breaks the structure, or breaks
 *   the key-check (header bytes are authenticated by it), or makes at least one entry fail its tag (name, nonce, ciphertext
 *   and tag are all bound) - except a flip that leaves every authenticated byte as it was, which cannot exist in v1.
 * - Iteration counts just outside the frozen open range [600,000, 10,000,000] are refused before any derivation, at both
 *   ends and at the u32 extremes; the range itself is accepted.
 * - Nonces: every encryption takes a fresh nonce, so many seals of one value under one key never repeat a nonce.
 */
class SecureFileFormatBreakerR2Test {

    private val cipher = EnvelopeCipher()
    private val dataKey: ByteArray = ByteArray(SecureFileFormat.DATA_KEY_BYTES) { (it * 13 + 5).toByte() }
    private val plaintexts: Map<String, ByteArray> = linkedMapOf(
        "a" to byteArrayOf(1, 2, 3),
        "été" to ByteArray(0),
        "k😀" to ByteArray(20) { it.toByte() },
    )

    private fun validFile(header: HeaderBody): ByteArray {
        val prefix: ByteArray = SecureFileFormat.headerPrefix(header)
        val keyCheck: ByteArray = cipher.sealKeyCheck(dataKey, prefix)
        val entries: List<EncryptedEntry> = plaintexts.map { (name, value) -> cipher.sealEntry(dataKey, header.kind, name, value) }
        return SecureFileFormat.encode(header, keyCheck, entries)
    }

    private val headers: List<HeaderBody> = listOf(
        HeaderBody.OsItem(ByteArray(SecureFileFormat.FINGERPRINT_BYTES) { it.toByte() }, SecureFileFormat.OS_TAG_MACOS),
        HeaderBody.Dpapi(ByteArray(40) { (it + 1).toByte() }),
        HeaderBody.Passphrase(ByteArray(SecureFileFormat.SALT_BYTES) { 7 }, Pbkdf2.DEFAULT_ITERATIONS.toLong(), ByteArray(SecureFileFormat.WRAPPED_KEY_BYTES) { 9 }),
        HeaderBody.RawKey(ByteArray(SecureFileFormat.WRAPPED_KEY_BYTES) { 3 }),
    )

    /** Whether [bytes] opens to exactly the original plaintexts: the damage went unnoticed. */
    private fun opensUnchanged(bytes: ByteArray): Boolean {
        val parsed: ParseResult = try {
            SecureFileFormat.parse(bytes)
        } catch (error: Throwable) {
            fail("parse threw ${error::class.java.simpleName} for a damaged file of ${bytes.size} bytes")
        }
        if (parsed !is ParseResult.Parsed) return false
        val file: ParsedStoreFile = parsed.file
        if (!cipher.verifyKeyCheck(dataKey, file.headerPrefix, file.keyCheck)) return false
        if (file.entries.size != plaintexts.size) return false
        return file.entries.all { entry: EncryptedEntry ->
            val opened: ByteArray? = cipher.openEntry(dataKey, file.kind, entry)
            opened != null && plaintexts[entry.key]?.contentEquals(opened) == true
        }
    }

    @Test
    fun `every truncation and every single-bit flip of a valid file is noticed and never makes the parser throw`() {
        for (header: HeaderBody in headers) {
            val bytes: ByteArray = validFile(header)
            assertTrue(opensUnchanged(bytes), "control: the undamaged ${header.kind} file opens")
            for (length: Int in 0 until bytes.size) {
                if (opensUnchanged(bytes.copyOf(length))) fail("${header.kind}: a file truncated to $length of ${bytes.size} bytes opened as whole")
            }
            for (index: Int in bytes.indices) {
                for (bit: Int in 0 until 8) {
                    val damaged: ByteArray = bytes.copyOf()
                    damaged[index] = (damaged[index].toInt() xor (1 shl bit)).toByte()
                    if (opensUnchanged(damaged)) fail("${header.kind}: flipping bit $bit of byte $index went unnoticed")
                }
            }
            for (extra: Int in 1..3) {
                if (opensUnchanged(bytes + ByteArray(extra))) fail("${header.kind}: $extra trailing bytes went unnoticed")
            }
        }
    }

    @Test
    fun `iteration counts outside the frozen open range are refused before any derivation, the range itself is accepted`() {
        val refused: List<Long> = listOf(0, 1, Pbkdf2.ITERATIONS_FLOOR - 1L, Pbkdf2.ITERATIONS_CEILING + 1L, Int.MAX_VALUE.toLong(), Int.MAX_VALUE + 1L, SecureFileFormat.UINT_MAX)
        val accepted: List<Long> = listOf(Pbkdf2.ITERATIONS_FLOOR.toLong(), Pbkdf2.ITERATIONS_CEILING.toLong())
        val kinds: Set<StoreKind> = setOf(StoreKind.PASSPHRASE)
        for (iterations: Long in refused + accepted) {
            val header = HeaderBody.Passphrase(ByteArray(SecureFileFormat.SALT_BYTES) { 1 }, iterations, ByteArray(SecureFileFormat.WRAPPED_KEY_BYTES) { 2 })
            val state: FileState = StoreClassifier.classify(validFile(header), kinds)
            if (iterations in refused) {
                assertIs<FileState.Unparsable>(state, "iterations=$iterations must be refused before any derivation")
            } else {
                assertIs<FileState.Parsed>(state, "iterations=$iterations is inside the frozen range")
            }
        }
        for (iterations: Long in refused.filter { it <= Int.MAX_VALUE }) {
            assertFailsWith<IllegalArgumentException>("deriveKek must refuse iterations=$iterations itself") {
                Pbkdf2.deriveKek("pass".toCharArray(), ByteArray(SecureFileFormat.SALT_BYTES), iterations.toInt())
            }
        }
    }

    @Test
    fun `many seals of one value under one key never repeat a nonce, in entries, key-checks and wraps alike`() {
        val seen: HashSet<String> = HashSet()
        val prefix: ByteArray = SecureFileFormat.headerPrefix(headers[3])
        val aad: ByteArray = SecureFileFormat.wrapAad(StoreKind.RAW_KEY, ByteArray(0))
        repeat(SEALS) {
            val nonces: List<ByteArray> = listOf(
                cipher.sealEntry(dataKey, StoreKind.RAW_KEY, "same", byteArrayOf(42)).nonce,
                cipher.sealKeyCheck(dataKey, prefix).copyOf(SecureFileFormat.NONCE_BYTES),
                cipher.wrapDataKey(dataKey, dataKey, aad).copyOf(SecureFileFormat.NONCE_BYTES),
            )
            for (nonce: ByteArray in nonces) {
                assertTrue(seen.add(nonce.joinToString(",")), "a nonce repeated under one key after ${seen.size} encryptions")
            }
        }
        assertEquals(SEALS * 3, seen.size)
    }

    private companion object {
        const val SEALS: Int = 5_000
    }
}
