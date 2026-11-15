package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.MacKeychainCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Suite M (plan 6.4): the macOS FFM binding against the real login keychain of whoever runs it, with
 * `-Pkmptoolkit.secretstore=macos`. Every item it creates has a unique test service (`kmptoolkit-storage.test.ffm-<pid>-<n>`)
 * and is deleted in `finally`/`@AfterTest`; no other item is read, changed or deleted. If a dialog appears, stop the run:
 * nothing here may prompt (a prompt means the keychain is locked or the access list refuses this `java`).
 *
 * Statuses are asserted as raw OSStatus values: the mapping above the seam is not this suite's subject.
 */
class FfmMacKeychainRealStoreTest {

    private val ledger: NativeLedger = NativeLedger()
    private lateinit var calls: MacKeychainCalls
    private val created: MutableList<String> = mutableListOf()

    @BeforeTest
    fun setUp() {
        RealStoreGate.require("macos")
        calls = FfmNativeKeyStoreBinding(Foreign.system(), ledger, FfmOs.MACOS).macOs()
    }

    @AfterTest
    fun cleanUp() {
        // Only the services this test created, one by one; a missing item is fine.
        if (::calls.isInitialized) created.forEach { service: String -> calls.deleteGenericPassword(service, ACCOUNT) }
        if (::calls.isInitialized) assertEquals(0, ledger.outstanding, "every CF object the calls created must be released")
    }

    private fun newService(): String = "kmptoolkit-storage.test.ffm-${ProcessHandle.current().pid()}-${COUNTER.incrementAndGet()}"
        .also { created.add(it) }

    private fun secret(seed: Long): ByteArray = ByteArray(32).also { Random(seed).nextBytes(it) }

    @Test
    fun `add copy delete round trip returns exactly the secret`() {
        val service: String = newService()
        val key: ByteArray = secret(1L)

        assertEquals(0, calls.addGenericPassword(service, ACCOUNT, "kmptoolkit test item", key))
        val read: RawResult<ByteArray?> = calls.copyGenericPassword(service, ACCOUNT, allowUi = true)
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(read).value)
        assertEquals(0, calls.deleteGenericPassword(service, ACCOUNT))
        val gone: RawResult<ByteArray?> = calls.copyGenericPassword(service, ACCOUNT, allowUi = true)
        assertEquals(ERR_SEC_ITEM_NOT_FOUND, assertIs<RawResult.Failure>(gone).status.code)
        assertEquals(0, ledger.outstanding)
    }

    @Test
    fun `a non-interactive read of an item this process created succeeds without a dialog`() {
        val service: String = newService()
        val key: ByteArray = secret(2L)
        assertEquals(0, calls.addGenericPassword(service, ACCOUNT, "kmptoolkit test item", key))

        val read: RawResult<ByteArray?> = calls.copyGenericPassword(service, ACCOUNT, allowUi = false)

        assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(read).value)
    }

    @Test
    fun `non-interactive add and delete of this process own item work without a dialog`() {
        val service: String = newService()
        val mac: FfmMacKeychain = FfmMacKeychain(Foreign.system(), ledger)
        val key: ByteArray = secret(9L)

        assertEquals(0, mac.addGenericPassword(service, ACCOUNT, "kmptoolkit test item", key, allowUi = false))
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(mac.copyGenericPassword(service, ACCOUNT, allowUi = false)).value)
        assertEquals(0, mac.deleteGenericPassword(service, ACCOUNT, allowUi = false))
        assertEquals(ERR_SEC_ITEM_NOT_FOUND, mac.deleteGenericPassword(service, ACCOUNT, allowUi = false))
    }

    @Test
    fun `a missing item is the raw not-found status for both interaction modes`() {
        val service: String = newService()
        for (allowUi: Boolean in listOf(true, false)) {
            val read: RawResult<ByteArray?> = calls.copyGenericPassword(service, ACCOUNT, allowUi)
            assertEquals(ERR_SEC_ITEM_NOT_FOUND, assertIs<RawResult.Failure>(read).status.code, "allowUi=$allowUi")
        }
        assertEquals(ERR_SEC_ITEM_NOT_FOUND, calls.deleteGenericPassword(service, ACCOUNT))
    }

    @Test
    fun `adding over an existing item is a duplicate and leaves the stored secret untouched`() {
        val service: String = newService()
        val first: ByteArray = secret(3L)
        assertEquals(0, calls.addGenericPassword(service, ACCOUNT, "first", first))

        assertEquals(ERR_SEC_DUPLICATE_ITEM, calls.addGenericPassword(service, ACCOUNT, "second", secret(4L)))

        val read: RawResult<ByteArray?> = calls.copyGenericPassword(service, ACCOUNT, allowUi = true)
        assertContentEquals(first, assertIs<RawResult.Success<ByteArray?>>(read).value)
        assertEquals(0, ledger.outstanding, "the failed add must release what it created")
    }

    @Test
    fun `two identities do not see each other and deleting one keeps the other`() {
        val one: String = newService()
        val two: String = newService()
        assertEquals(0, calls.addGenericPassword(one, ACCOUNT, "one", secret(5L)))
        assertEquals(0, calls.addGenericPassword(two, ACCOUNT, "two", secret(6L)))

        assertEquals(0, calls.deleteGenericPassword(one, ACCOUNT))

        assertEquals(ERR_SEC_ITEM_NOT_FOUND, assertIs<RawResult.Failure>(calls.copyGenericPassword(one, ACCOUNT, true)).status.code)
        assertContentEquals(
            secret(6L),
            assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(two, ACCOUNT, true)).value,
        )
    }

    @Test
    fun `non-ascii service account and label are carried as UTF-8`() {
        val service: String = newService() + ".é中"
        created.add(service)
        val account = "dir-üñ"
        val key: ByteArray = secret(7L)
        try {
            assertEquals(0, calls.addGenericPassword(service, account, "étiquette 中", key))
            assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(service, account, true)).value)
        } finally {
            calls.deleteGenericPassword(service, account)
        }
    }

    @Test
    fun `the probe never prompts and answers a status`() {
        val status: Int = calls.probe(allowUi = false)
        // The probe item does not exist: not found (or success if somebody made one). Anything else is recorded by the lead.
        assertTrue(status == 0 || status == ERR_SEC_ITEM_NOT_FOUND, "probe status was $status")
        assertEquals(status, calls.probe(allowUi = true), "allowUi must not change a probe: it never shows a dialog")
        assertEquals(0, ledger.outstanding)
    }

    @Test
    fun `one thousand operations leave no descriptor and no CF object behind`() {
        val service: String = newService()
        val key: ByteArray = secret(8L)
        val before: Long = TestFs.requireOpenDescriptorCount()

        repeat(250) {
            assertEquals(0, calls.addGenericPassword(service, ACCOUNT, "loop", key))
            assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(service, ACCOUNT, allowUi = false))
            assertEquals(ERR_SEC_DUPLICATE_ITEM, calls.addGenericPassword(service, ACCOUNT, "loop", key))
            assertEquals(0, calls.deleteGenericPassword(service, ACCOUNT))
        }

        assertEquals(0, ledger.outstanding, "leaked CF objects")
        assertEquals(before, TestFs.requireOpenDescriptorCount(), "leaked file descriptors")
        assertTrue(ledger.acquired > 1000, "control: the loop really acquired CF objects (${ledger.acquired})")
    }

    private companion object {
        const val ACCOUNT: String = "dir-00000000000000000000000000000000"
        const val ERR_SEC_ITEM_NOT_FOUND: Int = -25300
        const val ERR_SEC_DUPLICATE_ITEM: Int = -25299
        val COUNTER: AtomicInteger = AtomicInteger()
    }
}
