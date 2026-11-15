package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Format v1 and its cryptography (plan 4.3, T1 and T2 at the level of the format and the cipher): known-answer tests
 * against published vectors, golden files of every kind, and the tamper, truncation, downgrade and splice attacks that
 * must never produce a wrong value. What a verdict becomes for the app (`Undecryptable`, `AccessDenied`) is decided above
 * these primitives and is the key lifecycle's tests.
 */
class SecureFileFormatTest {

    private val cipher = EnvelopeCipher()

    private sealed interface Verdict {
        class Values(val values: Map<String, ByteArray>) : Verdict

        class Malformed(val reason: MalformedReason) : Verdict

        data object UnwrapFailed : Verdict

        data object KeyCheckFailed : Verdict

        class EntryFailed(val key: String) : Verdict
    }

    /**
     * Opens [bytes] the way the stores will: parse, (kinds 3 and 4) unwrap the data key with [secret] as the
     * key-encryption key, verify the key-check, open every entry. [secret] is the data key itself for kinds 1 and 2.
     */
    private fun evaluate(bytes: ByteArray, secret: ByteArray): Verdict {
        val parsed: ParsedStoreFile = when (val result: ParseResult = SecureFileFormat.parse(bytes)) {
            is ParseResult.Malformed -> return Verdict.Malformed(result.reason)
            is ParseResult.Parsed -> result.file
        }
        val dataKey: ByteArray = when (val header: HeaderBody = parsed.header) {
            is HeaderBody.OsItem, is HeaderBody.Dpapi -> secret
            is HeaderBody.Passphrase ->
                cipher.unwrapDataKey(secret, header.wrappedKey, SecureFileFormat.wrapAad(parsed.kind, header.beforeWrappedKey()))
            is HeaderBody.RawKey ->
                cipher.unwrapDataKey(secret, header.wrappedKey, SecureFileFormat.wrapAad(parsed.kind, header.beforeWrappedKey()))
        } ?: return Verdict.UnwrapFailed
        if (!cipher.verifyKeyCheck(dataKey, parsed.headerPrefix, parsed.keyCheck)) return Verdict.KeyCheckFailed
        val values: LinkedHashMap<String, ByteArray> = LinkedHashMap()
        for (entry: EncryptedEntry in parsed.entries) {
            values[entry.key] = cipher.openEntry(dataKey, parsed.kind, entry) ?: return Verdict.EntryFailed(entry.key)
        }
        return Verdict.Values(values)
    }

    private fun assertOpensGolden(bytes: ByteArray, secret: ByteArray, what: String) {
        val verdict: Verdict = evaluate(bytes, secret)
        assertIs<Verdict.Values>(verdict, "$what must open, was $verdict")
        assertEquals(GoldenStoreFiles.ENTRIES.keys, verdict.values.keys, what)
        for ((key: String, value: ByteArray) in GoldenStoreFiles.ENTRIES) assertContentEquals(value, verdict.values.getValue(key), "$what $key")
    }

    private val goldens: List<Triple<String, ByteArray, ByteArray>> by lazy {
        val kek3: ByteArray = Pbkdf2.deriveKek(GoldenStoreFiles.PASSPHRASE_NFC.toCharArray(), GoldenStoreFiles.SALT, 600_000)
        listOf(
            Triple("kind 1", GoldenStoreFiles.KIND_1_OS_ITEM, GoldenStoreFiles.DATA_KEY),
            Triple("kind 2", GoldenStoreFiles.KIND_2_DPAPI, GoldenStoreFiles.DATA_KEY),
            Triple("kind 3", GoldenStoreFiles.KIND_3_PASSPHRASE, kek3),
            Triple("kind 4", GoldenStoreFiles.KIND_4_RAW_KEY, GoldenStoreFiles.RAW_KEK),
        )
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // ---- T1: known-answer tests --------------------------------------------------------------------------------------

    @Test
    fun `AES-256-GCM matches the published test vectors with and without additional data`() {
        // Test cases 13-16 of the GCM specification (the AES-256 vectors of the NIST GCM validation set).
        val zeroKey = ByteArray(32)
        val zeroIv = ByteArray(12)
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", hexOf(EnvelopeCipher.gcmEncrypt(zeroKey, zeroIv, ByteArray(0), ByteArray(0))))
        assertEquals(
            "cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919",
            hexOf(EnvelopeCipher.gcmEncrypt(zeroKey, zeroIv, ByteArray(0), ByteArray(16))),
        )

        val key: ByteArray = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val iv: ByteArray = hex("cafebabefacedbaddecaf888")
        val plaintext64: ByteArray = hex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255",
        )
        val withoutAad: String = "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad" + "b094dac5d93471bdec1a502270e3cc6c"
        assertEquals(withoutAad, hexOf(EnvelopeCipher.gcmEncrypt(key, iv, ByteArray(0), plaintext64)))
        assertContentEquals(plaintext64, EnvelopeCipher.gcmDecrypt(key, iv, ByteArray(0), hex(withoutAad)))

        val plaintext60: ByteArray = plaintext64.copyOf(60)
        val aad: ByteArray = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val withAad: String = "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" + "76fc6ece0f4e1768cddf8853bb2d551b"
        assertEquals(withAad, hexOf(EnvelopeCipher.gcmEncrypt(key, iv, aad, plaintext60)))
        assertContentEquals(plaintext60, EnvelopeCipher.gcmDecrypt(key, iv, aad, hex(withAad)))
        assertEquals(null, EnvelopeCipher.gcmDecrypt(key, iv, aad.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, hex(withAad)))
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 matches the vectors of RFC 7914 section 11`() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hexOf(Pbkdf2.derive("passwd".toCharArray(), "salt".toByteArray(), 1, 64)),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            hexOf(Pbkdf2.derive("Password".toCharArray(), "NaCl".toByteArray(), 80_000, 64)),
        )
    }

    @Test
    fun `the golden file of every kind still opens, and a wrong key or kek never does`() {
        for ((what: String, bytes: ByteArray, secret: ByteArray) in goldens) {
            assertOpensGolden(bytes, secret, what)
            val wrong: ByteArray = secret.copyOf().also { it[5] = (it[5].toInt() xor 0x40).toByte() }
            val verdict: Verdict = evaluate(bytes, wrong)
            val expected: Boolean = if (what == "kind 3" || what == "kind 4") verdict is Verdict.UnwrapFailed else verdict is Verdict.KeyCheckFailed
            assertTrue(expected, "$what with a wrong key must be refused before any value, was $verdict")
        }
    }

    @Test
    fun `the same passphrase typed in NFC and in NFD opens the same golden passphrase file`() {
        val nfc: CharArray = GoldenStoreFiles.PASSPHRASE_NFC.toCharArray()
        val nfd: CharArray = GoldenStoreFiles.PASSPHRASE_NFD.toCharArray()
        assertNotEquals(nfc.concatToString(), nfd.concatToString(), "control: the two spellings differ before normalisation")
        assertContentEquals(Pbkdf2.normalizedUtf8(nfc), Pbkdf2.normalizedUtf8(nfd))
        assertContentEquals(byteArrayOf(0x70, 0xC3.toByte(), 0xA4.toByte(), 0x73, 0x73, 0x77, 0xC3.toByte(), 0xB6.toByte(), 0x72, 0x64), Pbkdf2.normalizedUtf8(nfd))

        val kek: ByteArray = Pbkdf2.deriveKek(nfd, GoldenStoreFiles.SALT, 600_000)
        assertOpensGolden(GoldenStoreFiles.KIND_3_PASSPHRASE, kek, "kind 3 opened by the NFD spelling")
        assertEquals(GoldenStoreFiles.PASSPHRASE_NFD, nfd.concatToString(), "the caller's passphrase must not be modified")
    }

    @Test
    fun `two seals of the same value carry different nonces and ciphertexts, and one nonce gives one ciphertext`() {
        val key = ByteArray(32) { 9 }
        val nonces: MutableSet<String> = HashSet()
        val ciphertexts: MutableSet<String> = HashSet()
        repeat(500) {
            val entry: EncryptedEntry = cipher.sealEntry(key, StoreKind.RAW_KEY, "k", "same value".toByteArray())
            nonces += hexOf(entry.nonce)
            ciphertexts += hexOf(entry.sealed)
        }
        assertEquals(500, nonces.size, "a nonce was reused")
        assertEquals(500, ciphertexts.size)
        val nonce = ByteArray(12) { 3 }
        assertEquals(
            hexOf(EnvelopeCipher.gcmEncrypt(key, nonce, ByteArray(1), "v".toByteArray())),
            hexOf(EnvelopeCipher.gcmEncrypt(key, nonce, ByteArray(1), "v".toByteArray())),
            "control: the same nonce is the only thing that repeats a ciphertext",
        )
    }

    @Test
    fun `every kind round trips through the writer, including no entries, empty values and the longest names`() {
        val longName: String = "n".repeat(SecureFileFormat.MAX_ENTRY_KEY_BYTES)
        val values: Map<String, ByteArray> = mapOf(
            "" to "empty name".toByteArray(),
            "empty value" to ByteArray(0),
            longName to ByteArray(300_000) { (it % 251).toByte() },
            "😀" to "x".toByteArray(),
        )
        val dataKey: ByteArray = cipher.newDataKey()
        val kek: ByteArray = cipher.newDataKey()
        val salt: ByteArray = cipher.newSalt()
        val beforeWrapped: ByteArray = HeaderBody.Passphrase(salt, 700_000, ByteArray(60)).beforeWrappedKey()
        val headers: List<HeaderBody> = listOf(
            HeaderBody.OsItem(ByteArray(16) { it.toByte() }, SecureFileFormat.OS_TAG_LINUX),
            HeaderBody.Dpapi(ByteArray(SecureFileFormat.MAX_DPAPI_BLOB_BYTES) { it.toByte() }),
            HeaderBody.Passphrase(salt, 700_000, cipher.wrapDataKey(kek, dataKey, SecureFileFormat.wrapAad(StoreKind.PASSPHRASE, beforeWrapped))),
            HeaderBody.RawKey(cipher.wrapDataKey(kek, dataKey, SecureFileFormat.wrapAad(StoreKind.RAW_KEY, ByteArray(0)))),
        )
        for (header: HeaderBody in headers) {
            for (names: Collection<String> in listOf(emptyList(), values.keys)) {
                val entries: List<EncryptedEntry> = names.map { cipher.sealEntry(dataKey, header.kind, it, values.getValue(it)) }
                val bytes: ByteArray = SecureFileFormat.encode(header, cipher.sealKeyCheck(dataKey, SecureFileFormat.headerPrefix(header)), entries)
                val parsed: ParsedStoreFile = assertIs<ParseResult.Parsed>(SecureFileFormat.parse(bytes)).file
                assertEquals(header, parsed.header, "$header")
                assertEquals(entries, parsed.entries, "$header with ${names.size} entries")
                assertTrue(cipher.verifyKeyCheck(dataKey, parsed.headerPrefix, parsed.keyCheck))
                for (entry: EncryptedEntry in parsed.entries) {
                    assertContentEquals(values.getValue(entry.key), cipher.openEntry(dataKey, header.kind, entry), "$header ${entry.key.take(8)}")
                }
            }
        }
    }

    // ---- T2: tamper, truncation, downgrade, splice -----------------------------------------------------------------

    @Test
    fun `no flipped byte of any golden file gives a value, and every layer of the file is caught`() {
        for ((what: String, bytes: ByteArray, secret: ByteArray) in goldens) {
            val seen: MutableSet<String> = HashSet()
            for (offset: Int in bytes.indices) {
                for (mask: Int in intArrayOf(0x01, 0xFF)) {
                    val mutated: ByteArray = bytes.copyOf().also { it[offset] = (it[offset].toInt() xor mask).toByte() }
                    val verdict: Verdict = evaluate(mutated, secret)
                    assertFalse(verdict is Verdict.Values, "$what: flipping byte $offset by $mask still opened a file")
                    seen += verdict::class.simpleName.orEmpty()
                }
            }
            assertTrue("Malformed" in seen && "EntryFailed" in seen, "$what: control, the attack must reach both the structure and the entries, saw $seen")
            if (what == "kind 3" || what == "kind 4") assertTrue("UnwrapFailed" in seen, "$what: saw $seen") else assertTrue("KeyCheckFailed" in seen, "$what: saw $seen")
        }
    }

    @Test
    fun `truncation at every length is malformed, and a byte appended is malformed`() {
        for ((what: String, bytes: ByteArray, secret: ByteArray) in goldens) {
            assertIs<Verdict.Values>(evaluate(bytes, secret), "control: the whole $what opens")
            for (length: Int in 0 until bytes.size) {
                val verdict: Verdict = evaluate(bytes.copyOf(length), secret)
                assertTrue(verdict is Verdict.Malformed, "$what cut to $length bytes was $verdict")
            }
            val extended: Verdict = evaluate(bytes + byteArrayOf(0), secret)
            assertEquals(MalformedReason.TRAILING_BYTES, assertIs<Verdict.Malformed>(extended, "$what plus a byte").reason)
        }
    }

    @Test
    fun `a downgraded or unknown version and a swapped kind never open`() {
        for ((what: String, bytes: ByteArray, secret: ByteArray) in goldens) {
            for (version: Int in intArrayOf(0, 2, 3, 255)) {
                val verdict: Verdict = evaluate(bytes.copyOf().also { it[4] = version.toByte() }, secret)
                assertEquals(MalformedReason.UNSUPPORTED_VERSION, assertIs<Verdict.Malformed>(verdict, "$what version $version").reason)
            }
            for (kind: Int in intArrayOf(0, 1, 2, 3, 4, 5, 255)) {
                if (kind == bytes[5].toInt()) continue
                val verdict: Verdict = evaluate(bytes.copyOf().also { it[5] = kind.toByte() }, secret)
                assertFalse(verdict is Verdict.Values, "$what with kind swapped to $kind opened")
            }
        }
    }

    @Test
    fun `an entry moved to another key name fails its tag, in the same file and with the entry names swapped`() {
        val dataKey: ByteArray = cipher.newDataKey()
        val header = HeaderBody.OsItem(ByteArray(16) { 1 }, SecureFileFormat.OS_TAG_MACOS)
        val a: EncryptedEntry = cipher.sealEntry(dataKey, header.kind, "a", "value of a".toByteArray())
        val b: EncryptedEntry = cipher.sealEntry(dataKey, header.kind, "b", "value of b".toByteArray())
        val keyCheck: ByteArray = cipher.sealKeyCheck(dataKey, SecureFileFormat.headerPrefix(header))

        val honest: ByteArray = SecureFileFormat.encode(header, keyCheck, listOf(a, b))
        assertIs<Verdict.Values>(evaluate(honest, dataKey), "control")
        val swapped: ByteArray = SecureFileFormat.encode(header, keyCheck, listOf(EncryptedEntry("b", a.nonce, a.sealed), EncryptedEntry("a", b.nonce, b.sealed)))
        assertEquals("b", assertIs<Verdict.EntryFailed>(evaluate(swapped, dataKey)).key)
        val renamed: ByteArray = SecureFileFormat.encode(header, keyCheck, listOf(EncryptedEntry("c", a.nonce, a.sealed)))
        assertEquals("c", assertIs<Verdict.EntryFailed>(evaluate(renamed, dataKey)).key)
        val otherKind: EncryptedEntry = cipher.sealEntry(dataKey, StoreKind.RAW_KEY, "a", "value of a".toByteArray())
        val crossKind: ByteArray = SecureFileFormat.encode(header, keyCheck, listOf(otherKind))
        assertEquals("a", assertIs<Verdict.EntryFailed>(evaluate(crossKind, dataKey), "an entry of a kind-4 file in a kind-1 file").key)
    }

    @Test
    fun `a key-check copied from another file fails under both keys`() {
        val keyA: ByteArray = cipher.newDataKey()
        val keyB: ByteArray = cipher.newDataKey()
        val headerA = HeaderBody.OsItem(ByteArray(16) { 1 }, SecureFileFormat.OS_TAG_MACOS)
        val headerB = HeaderBody.OsItem(ByteArray(16) { 2 }, SecureFileFormat.OS_TAG_MACOS)
        val checkA: ByteArray = cipher.sealKeyCheck(keyA, SecureFileFormat.headerPrefix(headerA))
        val checkB: ByteArray = cipher.sealKeyCheck(keyB, SecureFileFormat.headerPrefix(headerB))

        assertIs<Verdict.Values>(evaluate(SecureFileFormat.encode(headerA, checkA, emptyList()), keyA), "control")
        // B's check on A's header: wrong key, and (under B's key) a different header.
        val spliced: ByteArray = SecureFileFormat.encode(headerA, checkB, emptyList())
        assertIs<Verdict.KeyCheckFailed>(evaluate(spliced, keyA))
        assertIs<Verdict.KeyCheckFailed>(evaluate(spliced, keyB))
        // The same key under another header: the check is bound to every header byte.
        val rebound: ByteArray = SecureFileFormat.encode(headerB, checkA, emptyList())
        assertIs<Verdict.KeyCheckFailed>(evaluate(rebound, keyA))
    }

    @Test
    fun `a changed salt or iteration count of a passphrase header breaks the unwrap, and a count outside the range is refused before any derivation`() {
        val bytes: ByteArray = GoldenStoreFiles.KIND_3_PASSPHRASE
        val kek: ByteArray = Pbkdf2.deriveKek(GoldenStoreFiles.PASSPHRASE_NFC.toCharArray(), GoldenStoreFiles.SALT, 600_000)
        val saltOffset = 8
        val iterationOffset: Int = saltOffset + 16
        assertContentEquals(GoldenStoreFiles.SALT, bytes.copyOfRange(saltOffset, saltOffset + 16), "control: the offsets point at the salt")
        for (offset: Int in listOf(saltOffset, iterationOffset + 3)) {
            val mutated: ByteArray = bytes.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertIs<Verdict.UnwrapFailed>(evaluate(mutated, kek), "offset $offset")
        }

        for (count: Long in listOf(0L, 1L, 599_999L, 10_000_001L, Int.MAX_VALUE.toLong(), SecureFileFormat.UINT_MAX)) {
            assertFalse(Pbkdf2.isAcceptableIterationCount(count), "$count")
        }
        assertTrue(Pbkdf2.isAcceptableIterationCount(600_000))
        assertTrue(Pbkdf2.isAcceptableIterationCount(10_000_000))
        for (count: Int in listOf(0, 599_999, 10_000_001, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("$count") { Pbkdf2.deriveKek("x".toCharArray(), GoldenStoreFiles.SALT, count) }
        }
        val header: HeaderBody.Passphrase = assertIs<HeaderBody.Passphrase>(assertIs<ParseResult.Parsed>(SecureFileFormat.parse(bytes)).file.header)
        assertEquals(600_000L, header.iterations)
        val maxed: ByteArray = SecureFileFormat.encode(
            HeaderBody.Passphrase(GoldenStoreFiles.SALT, SecureFileFormat.UINT_MAX, header.wrappedKey),
            ByteArray(28),
            emptyList(),
        )
        val parsed: HeaderBody.Passphrase = assertIs<HeaderBody.Passphrase>(assertIs<ParseResult.Parsed>(SecureFileFormat.parse(maxed)).file.header)
        assertEquals(SecureFileFormat.UINT_MAX, parsed.iterations, "the parser reports 2^32-1 as it is and leaves the range rule to the caller")
    }

    @Test
    fun `the parser never throws and never allocates by a claimed length, whatever the bytes`() {
        val seed = 7_115L
        val random = Random(seed)
        repeat(3_000) { round: Int ->
            val bytes = ByteArray(random.nextInt(0, 400)).also(random::nextBytes)
            if (round % 2 == 0 && bytes.size >= 8) {
                SecureFileFormat.MAGIC.copyInto(bytes)
                bytes[4] = 1
                bytes[5] = random.nextInt(1, 5).toByte()
            }
            val result: ParseResult = SecureFileFormat.parse(bytes)
            if (result is ParseResult.Parsed) {
                assertEquals(bytes.size, SecureFileFormat.encode(result.file.header, result.file.keyCheck, result.file.entries).size, "seed=$seed round=$round: a parsed file re-encodes to its length")
            }
        }
        val honest: ByteArray = GoldenStoreFiles.KIND_4_RAW_KEY
        val countOffset: Int = 8 + 60 + 28
        for (claim: Long in listOf(SecureFileFormat.UINT_MAX, 0x7FFF_FFFFL, 1_000_000L, 100L)) {
            val mutated: ByteArray = honest.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(countOffset, claim.toInt()) }
            assertEquals(MalformedReason.BAD_ENTRY_COUNT, assertIs<ParseResult.Malformed>(SecureFileFormat.parse(mutated), "claimed $claim entries").reason)
        }
        val firstKeyLength: Int = countOffset + 4
        val hugeKey: ByteArray = honest.copyOf().also { it[firstKeyLength] = 0xFF.toByte(); it[firstKeyLength + 1] = 0xFF.toByte() }
        assertEquals(MalformedReason.TRUNCATED, assertIs<ParseResult.Malformed>(SecureFileFormat.parse(hugeKey)).reason)
        val sealedLength: Int = firstKeyLength + 2 + 5 + 12
        val hugeSealed: ByteArray = honest.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(sealedLength, -1) }
        assertEquals(MalformedReason.BAD_ENTRY, assertIs<ParseResult.Malformed>(SecureFileFormat.parse(hugeSealed)).reason)
        val tinySealed: ByteArray = honest.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(sealedLength, 15) }
        assertEquals(MalformedReason.BAD_ENTRY, assertIs<ParseResult.Malformed>(SecureFileFormat.parse(tinySealed)).reason)
    }

    @Test
    fun `an entry name that is not valid UTF-8, and a duplicated name, are malformed`() {
        val honest: ByteArray = GoldenStoreFiles.KIND_4_RAW_KEY
        val firstKey: Int = 8 + 60 + 28 + 4 + 2
        assertContentEquals("alpha".toByteArray(), honest.copyOfRange(firstKey, firstKey + 5), "control: the offset points at the first name")
        val invalid: ByteArray = honest.copyOf().also { it[firstKey] = 0xC3.toByte(); it[firstKey + 1] = 0x28 }
        assertEquals(MalformedReason.INVALID_KEY_ENCODING, assertIs<ParseResult.Malformed>(SecureFileFormat.parse(invalid)).reason)

        val parsed: ParsedStoreFile = assertIs<ParseResult.Parsed>(SecureFileFormat.parse(honest)).file
        val twice: List<EncryptedEntry> = listOf(parsed.entries[0], EncryptedEntry(parsed.entries[0].key, parsed.entries[1].nonce, parsed.entries[1].sealed))
        val duplicated: ByteArray = SecureFileFormat.encode(parsed.header, parsed.keyCheck, twice)
        assertEquals(MalformedReason.DUPLICATE_ENTRY_KEY, assertIs<ParseResult.Malformed>(SecureFileFormat.parse(duplicated)).reason)
    }

    @Test
    fun `a failure of the cipher provider is thrown, never reported as a wrong key`() {
        val aad = ByteArray(4)
        assertFailsWith<IllegalArgumentException> { EnvelopeCipher.gcmDecrypt(ByteArray(16), ByteArray(12), aad, ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { EnvelopeCipher.gcmEncrypt(ByteArray(32), ByteArray(11), aad, ByteArray(1)) }
        assertEquals(null, EnvelopeCipher.gcmDecrypt(ByteArray(32), ByteArray(11), aad, ByteArray(32)), "a malformed nonce is a failed tag, not a provider fault")
        assertEquals(null, EnvelopeCipher.gcmDecrypt(ByteArray(32), ByteArray(12), aad, ByteArray(15)), "shorter than a tag")
    }

    @Test
    fun `wiping zeroes a key and the data key generator only uses SecureRandom`() {
        val key: ByteArray = cipher.newDataKey()
        assertTrue(key.any { it != 0.toByte() }, "control: a fresh key is not all zero")
        EnvelopeCipher.wipe(key)
        assertTrue(key.all { it == 0.toByte() })
        val fixed: SecureRandom = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1L) }
        val a: ByteArray = EnvelopeCipher(fixed).newDataKey()
        assertEquals(32, a.size)
        assertNotEquals(hexOf(a), hexOf(EnvelopeCipher(fixed).newDataKey()), "successive keys differ")
    }
}
