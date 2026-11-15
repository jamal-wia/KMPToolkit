package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.MacKeychainCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

/**
 * The cleanup of the smoke (breaker round 1: B2 and m3). No key store is touched: the OS calls are a fake that keeps its items in a
 * map and records every call, so every test proves the item existed before the removal (the control) and is gone after it.
 */
class CleanupTest {

    private val item: MacKeyItem = MacKeyItem("smoke.test.kmptoolkit.securestorage", "dir-0123456789abcdef0123456789abcdef")
    private val keyBytes: ByteArray = ByteArray(32) { it.toByte().inc() }
    private val fake: FakeKeychain = FakeKeychain()

    // --- the removal ---

    @Test
    fun `a present item is read, deleted and then confirmed absent, with no dialog`() {
        fake.items[item.service to item.account] = keyBytes
        MacKeyItemRemoval(fake, item).remove(expectPresent = true)
        assertEquals(listOf("copy", "delete", "copy"), fake.calls, "read first (the control), then delete, then read again")
        assertTrue(fake.items.isEmpty(), "the item is gone")
        assertTrue(fake.allowUiValues.none { it }, "no call may allow a dialog")
    }

    @Test
    fun `the control fails when no item is at the derived identity, and names it`() {
        // An item exists, but under another identity: the smoke's derivation has drifted from the library's.
        fake.items["other.service" to "dir-other"] = keyBytes
        val failure: SmokeFailure = assertFailsWith<SmokeFailure> { MacKeyItemRemoval(fake, item).remove(expectPresent = true) }
        assertTrue("control failed" in failure.message.orEmpty() && item.service in failure.message.orEmpty(), failure.message)
        assertEquals(1, fake.items.size, "an item of another identity is never touched")
        assertFalse("delete" in fake.calls, "no delete is made at an identity the control did not find")
    }

    @Test
    fun `a failed run removes a present item and accepts an absent one`() {
        fake.items[item.service to item.account] = keyBytes
        MacKeyItemRemoval(fake, item).remove(expectPresent = false)
        assertTrue(fake.items.isEmpty())
        // The failure may have come before the key was made: nothing to remove is fine, and the delete is still asked.
        fake.calls.clear()
        MacKeyItemRemoval(fake, item).remove(expectPresent = false)
        assertEquals(listOf("copy", "delete", "copy"), fake.calls)
    }

    @Test
    fun `a delete that fails is a failure`() {
        fake.items[item.service to item.account] = keyBytes
        fake.deleteStatus = -25293
        val failure: SmokeFailure = assertFailsWith<SmokeFailure> { MacKeyItemRemoval(fake, item).remove(expectPresent = true) }
        assertTrue("-25293" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `an item that survives a delete reported as successful is a failure`() {
        fake.items[item.service to item.account] = keyBytes
        fake.deleteRemovesItem = false
        val failure: SmokeFailure = assertFailsWith<SmokeFailure> { MacKeyItemRemoval(fake, item).remove(expectPresent = true) }
        assertTrue("still in the login keychain" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a read that fails for another reason than not-found is a failure`() {
        fake.copyFailure = RawStatus(-25308)
        val failure: SmokeFailure = assertFailsWith<SmokeFailure> { MacKeyItemRemoval(fake, item).remove(expectPresent = false) }
        assertTrue("-25308" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `the key bytes the control reads are wiped`() {
        fake.items[item.service to item.account] = keyBytes
        MacKeyItemRemoval(fake, item).remove(expectPresent = true)
        assertTrue(keyBytes.all { it == 0.toByte() }, "the host only needs the answer, not the key")
    }

    @Test
    fun `a wedged native call ends in a failure within its bound`() {
        val release: CountDownLatch = CountDownLatch(1)
        fake.beforeCopy = { release.await(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS) }
        try {
            val failure: SmokeFailure = assertFailsWith<SmokeFailure> { MacKeyItemRemoval(fake, item, boundMillis = 100L).remove(expectPresent = false) }
            assertTrue("time limit" in failure.message.orEmpty(), failure.message)
        } finally {
            release.countDown()
        }
    }

    // --- the identity ---

    @Test
    fun `the identity is the service name plus the SHA-256 of the canonical directory, pinned by a known answer`() {
        val derived: MacKeyItem = MacKeyItem.ofCanonical("run.one", "/tmp/smoke-vector")
        assertEquals("run.one.kmptoolkit.securestorage", derived.service)
        assertEquals("dir-71a3ea064f9f350ad4e30fec4dff92da", derived.account)
    }

    @Test
    fun `the directory is normalized to NFC like the library does on macOS`() {
        val composed: MacKeyItem = MacKeyItem.ofCanonical("n", "/tmp/café")
        val decomposed: MacKeyItem = MacKeyItem.ofCanonical("n", "/tmp/café")
        assertEquals("dir-0873cca0bdcc4a24732629f0cd458640", composed.account)
        assertEquals(composed.account, decomposed.account)
    }

    @Test
    fun `the service is normalized to NFC like the library does on macOS`() {
        val composed: MacKeyItem = MacKeyItem.ofCanonical("caf\u00e9", "/tmp/d")
        val decomposed: MacKeyItem = MacKeyItem.ofCanonical("cafe\u0301", "/tmp/d")
        assertEquals("caf\u00e9.kmptoolkit.securestorage", decomposed.service, "a decomposed store name is spelled composed")
        assertEquals(composed.toString(), decomposed.toString())
    }

    @Test
    fun `two runs never share an identity and a link resolves to its target`() {
        assumeFalse("symbolic links need a privilege on Windows", System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val real: File = Files.createTempDirectory("smoke-identity-").toFile()
        val link: File = File(real.parentFile, real.name + "-link")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
            assertEquals(MacKeyItem.of("a", real).toString(), MacKeyItem.of("a", link).toString(), "same identity through a link")
            assertTrue(MacKeyItem.of("a", real).toString() != MacKeyItem.of("b", real).toString(), "another run name is another service")
        } finally {
            link.delete()
            real.deleteRecursively()
        }
    }

    /** A keychain in a map. Records the calls and the allowUi values; every failure mode is a field. */
    private class FakeKeychain : MacKeychainCalls {
        val items: MutableMap<Pair<String, String>, ByteArray> = mutableMapOf()
        val calls: MutableList<String> = mutableListOf()
        val allowUiValues: MutableList<Boolean> = mutableListOf()
        var deleteStatus: Int = 0
        var deleteRemovesItem: Boolean = true
        var copyFailure: RawStatus? = null
        var beforeCopy: () -> Unit = {}

        override fun copyGenericPassword(service: String, account: String, allowUi: Boolean): RawResult<ByteArray?> {
            calls += "copy"
            allowUiValues += allowUi
            beforeCopy()
            copyFailure?.let { return RawResult.Failure(it) }
            val found: ByteArray? = items[service to account]
            return if (found == null) RawResult.Failure(RawStatus(-25300)) else RawResult.Success(found)
        }

        override fun addGenericPassword(service: String, account: String, label: String, secret: ByteArray, allowUi: Boolean): Int {
            items[service to account] = secret
            return 0
        }

        override fun deleteGenericPassword(service: String, account: String, allowUi: Boolean): Int {
            calls += "delete"
            allowUiValues += allowUi
            if (deleteStatus != 0) return deleteStatus
            if (deleteRemovesItem) items.remove(service to account)
            return 0
        }

        override fun probe(allowUi: Boolean): Int = 0
    }

    private companion object {
        const val HARD_TEST_BOUND_SECONDS: Long = 10L
    }
}
