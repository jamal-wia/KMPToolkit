package io.github.jamal_wia.kmptoolkit.storage

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The public value types of the desktop stores (plan 3.4, T11, T12): construction rules and what `toString` reveals.
 * Group `sto-desktop`, phase B (6e).
 */
class DesktopKeyTypesTest {

    @Test
    fun `options have the plan's defaults and reject a blank or null-character label`() {
        val defaults = DesktopSecureStorageOptions()
        assertEquals(null, defaults.itemLabel)
        assertTrue(defaults.allowUserInteraction)
        val custom = DesktopSecureStorageOptions(itemLabel = "My App", allowUserInteraction = false)
        assertEquals("My App", custom.itemLabel)
        assertFalse(custom.allowUserInteraction)
        for (bad: String in listOf("", " ", "\t\n", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException>("label '${bad.replace("\u0000", "\\0")}'") { DesktopSecureStorageOptions(itemLabel = bad) }
        }
    }

    @Test
    fun `passphrase iterations are checked at construction, both bounds are accepted`() {
        assertEquals(600_000, AppEncryptionKey.Passphrase({ "x".toCharArray() }).iterations, "the default")
        assertEquals(600_000, AppEncryptionKey.Passphrase({ "x".toCharArray() }, 600_000).iterations)
        assertEquals(10_000_000, AppEncryptionKey.Passphrase({ "x".toCharArray() }, 10_000_000).iterations)
        for (bad: Int in listOf(599_999, 0, -1, 10_000_001, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException>("iterations=$bad") { AppEncryptionKey.Passphrase({ "x".toCharArray() }, bad) }
        }
    }

    @Test
    fun `constructing a key never calls its provider`() {
        val calls = AtomicInteger()
        AppEncryptionKey.Passphrase({ calls.incrementAndGet(); "p".toCharArray() })
        AppEncryptionKey.Raw({ calls.incrementAndGet(); ByteArray(32) })
        assertEquals(0, calls.get(), "providers are called on first use only")
    }

    @Test
    fun `toString of both key types reveals nothing, not even through the provider`() {
        val canary = "CANARY-passphrase-4f2a"
        val passphrase = AppEncryptionKey.Passphrase({ canary.toCharArray() })
        val raw = AppEncryptionKey.Raw({ canary.toByteArray() })
        for (text: String in listOf(passphrase.toString(), raw.toString(), "$passphrase", "$raw")) {
            assertFalse(text.contains(canary), text)
            assertFalse(text.contains("Function"), "no lambda name either: $text")
        }
        assertEquals("AppEncryptionKey.Passphrase", passphrase.toString())
        assertEquals("AppEncryptionKey.Raw", raw.toString())
    }
}
