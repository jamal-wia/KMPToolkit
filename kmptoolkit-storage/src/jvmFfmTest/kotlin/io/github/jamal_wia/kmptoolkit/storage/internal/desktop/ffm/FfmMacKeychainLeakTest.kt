package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import java.lang.foreign.Linker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Plan 3.5 / PATTERNS "native handles": every raw call of the macOS binding frees every CF object it created, on success, on an
 * OS failure and when one of its own allocations fails half way. Runs on any machine against [FakeSecurityFramework]; the real
 * keychain is suite M.
 */
class FfmMacKeychainLeakTest {

    private val fake: FakeSecurityFramework = FakeSecurityFramework()
    private val ledger: NativeLedger = NativeLedger()
    private val calls: FfmMacKeychain = FfmMacKeychain(Foreign({ fake.lookup }, Linker.nativeLinker()), ledger)

    @AfterTest
    fun tearDown() {
        fake.close()
    }

    private fun assertNothingHeld(message: String) {
        assertEquals(emptyMap(), fake.live, "$message: the OS still holds CF objects")
        assertEquals(0, ledger.outstanding, "$message: the ledger says objects are still held")
        assertTrue(fake.badReleases.isEmpty(), "$message: released an object that was not live: ${fake.badReleases}")
    }

    @Test
    fun `a successful read returns the secret and releases the query and the data`() {
        val result: RawResult<ByteArray?> = calls.copyGenericPassword("svc", "acct", allowUi = true)

        assertContentEquals(fake.storedSecret, assertIs<RawResult.Success<ByteArray?>>(result).value)
        assertTrue(ledger.acquired >= 4, "control: the call acquired the dictionary, two strings and the data (${ledger.acquired})")
        assertNothingHeld("success")
    }

    @Test
    fun `an OS failure is the raw status and releases the query`() {
        fake.copyStatus = -25300
        val result: RawResult<ByteArray?> = calls.copyGenericPassword("svc", "acct", allowUi = false)

        assertEquals(-25300, assertIs<RawResult.Failure>(result).status.code)
        assertTrue(ledger.acquired >= 3, "control: the failed call had acquired something (${ledger.acquired})")
        assertNothingHeld("os failure")
    }

    /** Runs [call] once per allocation it makes, failing exactly that one, and checks nothing is held after each. */
    private fun failEachAllocation(name: String, call: () -> Any?) {
        fake.resetAllocationCount()
        call()
        val total: Int = fake.allocations
        assertTrue(total >= 3, "$name: control: the call allocates several objects (was $total)")
        assertNothingHeld("$name, no failure")
        for (nth: Int in 1..total) {
            fake.failAllocationAt = nth
            val outcome: Result<Any?> = runCatching { call() }
            // The binding's own allocations surface as OutOfMemoryError; the one the OS makes inside SecItemCopyMatching is a failure status.
            val surfaced: Boolean = outcome.exceptionOrNull() is OutOfMemoryError || outcome.getOrNull() is RawResult.Failure ||
                (outcome.getOrNull() as? Int)?.let { status: Int -> status != 0 } == true
            assertTrue(surfaced, "$name: allocation $nth of $total failing must surface, not be swallowed: $outcome")
            assertNothingHeld("$name, allocation $nth of $total failed")
        }
    }

    @Test
    fun `every allocation that fails frees what was allocated before it, for every raw call`() {
        failEachAllocation("add") { calls.addGenericPassword("svc", "acct", "label", ByteArray(32) { 7 }, allowUi = true) }
        failEachAllocation("delete") { calls.deleteGenericPassword("svc", "acct", allowUi = true) }
        failEachAllocation("copy") { calls.copyGenericPassword("svc", "acct", true) }
        failEachAllocation("probe") { calls.probe(false) }
    }

    @Test
    fun `the data the OS saw is the key and the add releases the wrapping CFData`() {
        val key = ByteArray(32) { (it * 3).toByte() }

        assertEquals(0, calls.addGenericPassword("svc", "acct", "label", key, allowUi = true))

        assertContentEquals(key, fake.secretSeenByOs)
        assertNothingHeld("add")
    }

    @Test
    fun `allowUi false makes add delete and read carry UIFail and never the Skip constant, allowUi true carries neither`() {
        val uiFail = "kSecUseAuthenticationUI=kSecUseAuthenticationUIFail"
        val operations: Map<String, (Boolean) -> Any?> = mapOf(
            "add" to { ui: Boolean -> calls.addGenericPassword("svc", "acct", "label", ByteArray(32), ui) },
            "delete" to { ui: Boolean -> calls.deleteGenericPassword("svc", "acct", ui) },
            "copy" to { ui: Boolean -> calls.copyGenericPassword("svc", "acct", ui) },
        )
        for ((name: String, operation: (Boolean) -> Any?) in operations) {
            fake.resetSets()
            operation(false)
            assertTrue(uiFail in fake.sets, "$name with allowUi=false must pass kSecUseAuthenticationUIFail: ${fake.sets}")
            fake.resetSets()
            operation(true)
            assertTrue(fake.sets.none { it.startsWith("kSecUseAuthenticationUI") }, "$name with allowUi=true must not suppress UI: ${fake.sets}")
        }
        assertTrue(fake.lookups.none { it.contains("Skip") }, "kSecUseAuthenticationUISkip must never be resolved: ${fake.lookups}")
        assertNothingHeld("ui flag")
    }

    @Test
    fun `a failed add reports the raw status and releases everything`() {
        fake.addStatus = -25299

        assertEquals(-25299, calls.addGenericPassword("svc", "acct", "label", ByteArray(32), allowUi = true))

        assertNothingHeld("duplicate")
    }

    @Test
    fun `delete and probe release everything and report the raw status`() {
        fake.deleteStatus = -25300
        assertEquals(-25300, calls.deleteGenericPassword("svc", "acct", allowUi = true))
        fake.copyStatus = -25308
        assertEquals(-25308, calls.probe(allowUi = true))
        assertNothingHeld("delete and probe")
    }

    @Test
    fun `a thousand calls leave the OS and the ledger balanced`() {
        repeat(250) {
            assertEquals(0, calls.addGenericPassword("svc", "acct", "label", ByteArray(32), allowUi = true))
            assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword("svc", "acct", false))
            assertEquals(0, calls.deleteGenericPassword("svc", "acct", allowUi = true))
            assertEquals(0, calls.probe(false))
        }
        assertNothingHeld("1000 calls")
        assertTrue(ledger.acquired > 3000, "control: the loop acquired objects (${ledger.acquired})")
    }

    @Test
    fun `a missing system symbol fails the load with a linkage error and never a silent half binding`() {
        val brokenForeign = Foreign({ name: String -> if (name.contains("Security.framework")) fake.lookup else java.lang.foreign.SymbolLookup { java.util.Optional.empty() } }, Linker.nativeLinker())

        assertFailsWith<UnsatisfiedLinkError> { FfmMacKeychain(brokenForeign, NativeLedger()) }
    }
}
