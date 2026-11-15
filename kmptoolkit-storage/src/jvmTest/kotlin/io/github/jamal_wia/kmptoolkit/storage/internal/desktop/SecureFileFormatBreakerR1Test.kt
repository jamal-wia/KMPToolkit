package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Breaker round 1 of group sto-desktop (phase A): format v1, the AES-GCM envelope and PBKDF2 (plan 4.3, 4.8) against text
 * that has no UTF-8 form. Format v1 freezes "NFC, then UTF-8" for the passphrase and "key UTF-8" for entry names; a string
 * with an unpaired surrogate has no UTF-8 encoding, and a silent replacement by `?` makes two different texts one.
 *
 * - A passphrase must never open a store made with a different passphrase (4.8: a wrong passphrase is `AccessDenied`).
 *   Refusing such a passphrase is an acceptable answer; deriving the key of another passphrase is not.
 * - An entry name must come back as it was written, or be refused when written; a value sealed under one name must never
 *   open under another.
 */
class SecureFileFormatBreakerR1Test {

    private val salt: ByteArray = ByteArray(SecureFileFormat.SALT_BYTES) { (it * 7).toByte() }

    private fun kekOrRefused(passphrase: String): ByteArray? = try {
        Pbkdf2.deriveKek(passphrase.toCharArray(), salt, Pbkdf2.ITERATIONS_FLOOR)
    } catch (_: IllegalArgumentException) {
        null
    }

    @Test
    fun `passphrases that differ only in unpaired surrogates never derive one key`() {
        val passphrases: List<String> = listOf("open sesame?", "open sesame\uD800", "open sesame\uDFFF", "open sesame\uDBFF")
        val keys: List<Pair<String, ByteArray>> = passphrases.mapNotNull { text: String -> kekOrRefused(text)?.let { text to it } }

        for (i: Int in keys.indices) {
            for (j: Int in i + 1 until keys.size) {
                if (keys[i].second.contentEquals(keys[j].second)) {
                    fail(
                        "passphrase ${escaped(keys[i].first)} and ${escaped(keys[j].first)} derive the same key: " +
                            "one opens the store of the other",
                    )
                }
            }
        }
        assertEquals(true, keys.any { it.first == "open sesame?" }, "control: a valid passphrase is derived")
    }

    @Test
    fun `an entry name with no UTF-8 form is refused or comes back unchanged, and never opens under another name`() {
        val cipher = EnvelopeCipher()
        val dataKey: ByteArray = cipher.newDataKey()
        val name = "token\uD800"
        val sealed: EncryptedEntry = try {
            cipher.sealEntry(dataKey, StoreKind.RAW_KEY, name, byteArrayOf(1, 2, 3))
        } catch (_: IllegalArgumentException) {
            return // refused when written: acceptable
        }
        val impostor = EncryptedEntry("token?", sealed.nonce, sealed.sealed)
        assertNull(cipher.openEntry(dataKey, StoreKind.RAW_KEY, impostor), "a value sealed under ${escaped(name)} opens under \"token?\"")

        val header = HeaderBody.RawKey(ByteArray(SecureFileFormat.WRAPPED_KEY_BYTES))
        val keyCheck: ByteArray = cipher.sealKeyCheck(dataKey, SecureFileFormat.headerPrefix(header))
        val bytes: ByteArray = try {
            SecureFileFormat.encode(header, keyCheck, listOf(sealed))
        } catch (_: IllegalArgumentException) {
            return
        }
        val parsed: ParseResult.Parsed = assertIs(SecureFileFormat.parse(bytes))
        assertEquals(name, parsed.file.entries.single().key, "the writer stored another name than it was given")
    }

    private fun escaped(text: String): String =
        text.map { if (it.isSurrogate()) "\\u%04X".format(it.code) else it.toString() }.joinToString("", "\"", "\"")
}
