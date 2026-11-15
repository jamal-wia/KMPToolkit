package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The shape of the seam (plan 3.5) and the behaviour the fake binding promises to every test above it. The seam
 * is a contract between this artifact and the FFM and JNA bindings, so its function set is pinned here.
 */
class NativeKeyStoreSeamTest {

    private fun names(type: Class<*>): Set<String> = type.declaredMethods.filterNot(Method::isSynthetic).map { it.name }.toSet()

    @Test
    fun `the seam has exactly the calls of the plan and no update call`() {
        assertEquals(setOf("macOs", "windows", "linux"), names(NativeKeyStoreBinding::class.java))
        val mac: Set<String> = names(MacKeychainCalls::class.java)
        val dpapi: Set<String> = names(DpapiCalls::class.java)
        val secret: Set<String> = names(SecretServiceCalls::class.java)
        assertEquals(setOf("copyGenericPassword", "addGenericPassword", "deleteGenericPassword", "probe"), mac)
        assertEquals(setOf("protect", "unprotect"), dpapi)
        assertEquals(setOf("lookup", "store", "clear", "defaultCollectionLocked", "backendIsPortal"), secret)
        for (name: String in mac + dpapi + secret + names(NativeKeyStoreBinding::class.java)) {
            assertFalse(name.contains("update", ignoreCase = true), "R7: a key item is never updated, found '$name'")
        }
    }

    @Test
    fun `raw statuses compare by value and know success and the missing-symbol status`() {
        assertTrue(RawStatus.SUCCESS.isSuccess)
        assertFalse(RawStatus(-25300).isSuccess)
        assertFalse(RawStatus(0, domain = "g-io-error-quark").isSuccess)
        assertTrue(RawStatus.SYMBOL_MISSING.isSymbolMissing)
        assertFalse(RawStatus(-1_000_001).isSymbolMissing, "the code alone is not the symbol-missing status")
        assertEquals(RawStatus(3, "d", "n"), RawStatus(3, "d", "n"))
        assertEquals(RawStatus(3, "d", "n").hashCode(), RawStatus(3, "d", "n").hashCode())
        assertEquals("Found", SecretLookup.Found(byteArrayOf(1, 2, 3)).toString(), "a found secret never prints")
    }

    @Test
    fun `the fake macOS store adds, copies a defensive copy, rejects a duplicate and deletes`() {
        val fake = FakeNativeKeyStoreBinding()
        val mac: MacKeychainCalls = fake.macOs()
        val secret = ByteArray(32) { it.toByte() }

        assertEquals(0, mac.addGenericPassword("svc", "acct", "label", secret))
        secret.fill(0) // the caller wipes its array: the store must not change
        val read: RawResult<ByteArray?> = mac.copyGenericPassword("svc", "acct", allowUi = true)
        assertContentEquals(ByteArray(32) { it.toByte() }, (read as RawResult.Success).value)
        read.value!!.fill(7)
        assertContentEquals(ByteArray(32) { it.toByte() }, (mac.copyGenericPassword("svc", "acct", true) as RawResult.Success).value)
        assertEquals(FakeNativeKeyStoreBinding.ERR_SEC_DUPLICATE_ITEM, mac.addGenericPassword("svc", "acct", "l", secret))
        assertEquals(0, mac.deleteGenericPassword("svc", "acct"))
        assertEquals(
            -25300,
            (mac.copyGenericPassword("svc", "acct", false) as RawResult.Failure).status.code,
        )
        assertEquals(
            listOf("addGenericPassword", "copyGenericPassword", "copyGenericPassword", "addGenericPassword", "deleteGenericPassword", "copyGenericPassword"),
            fake.callNames,
        )
        assertTrue(fake.calls.none { it.detail.contains("[B@") }, "a recorded detail must not carry secret bytes")
    }

    @Test
    fun `the fake DPAPI round trips and refuses another entropy or a foreign blob`() {
        val fake = FakeNativeKeyStoreBinding()
        val dpapi: DpapiCalls = fake.windows()
        val entropy = "kmptoolkit-storage:a".toByteArray()
        val blob: ByteArray = (dpapi.protect(byteArrayOf(1, 2, 3), entropy) as RawResult.Success).value

        assertContentEquals(byteArrayOf(1, 2, 3), (dpapi.unprotect(blob, entropy) as RawResult.Success).value)
        assertEquals(13, (dpapi.unprotect(blob, "other".toByteArray()) as RawResult.Failure).status.code)
        assertEquals(13, (dpapi.unprotect(byteArrayOf(9, 9, 9), entropy) as RawResult.Failure).status.code)
        fake.dpapiNoSession = true
        assertEquals(1312, (dpapi.protect(byteArrayOf(1), entropy) as RawResult.Failure).status.code)
        assertEquals(1312, (dpapi.unprotect(blob, entropy) as RawResult.Failure).status.code)
    }

    @Test
    fun `the fake Secret Service distinguishes found, locked match, not found and a missing symbol`() {
        val fake = FakeNativeKeyStoreBinding()
        val secret: SecretServiceCalls = fake.linux()
        val attributes: Map<String, String> = mapOf("service" to "s", "directory" to "d")

        assertIs<SecretLookup.NotFound>((secret.lookup(attributes, true) as RawResult.Success).value)
        assertTrue(secret.store("label", attributes, byteArrayOf(5)).isSuccess)
        assertContentEquals(byteArrayOf(5), ((secret.lookup(attributes, false) as RawResult.Success).value as SecretLookup.Found).secret)
        assertIs<SecretLookup.NotFound>((secret.lookup(mapOf("service" to "s", "directory" to "other"), true) as RawResult.Success).value)

        fake.secretItems.getValue(attributes).locked = true
        assertIs<SecretLookup.LockedMatch>((secret.lookup(attributes, false) as RawResult.Success).value)
        assertEquals(0, fake.promptCount, "control: no prompt without UI")
        assertIs<SecretLookup.Found>((secret.lookup(attributes, true) as RawResult.Success).value)
        assertEquals(1, fake.promptCount)
        fake.promptDismissed = true
        assertIs<SecretLookup.LockedMatch>((secret.lookup(attributes, true) as RawResult.Success).value)

        assertTrue(secret.clear(mapOf("service" to "s")).isSuccess)
        assertTrue(fake.secretItems.isEmpty())
        fake.searchSymbolMissing = true
        assertTrue((secret.lookup(attributes, true) as RawResult.Failure).status.isSymbolMissing)
        assertTrue(secret.store("l", attributes, byteArrayOf(1)).isSymbolMissing)
    }

    @Test
    fun `the fake runs the before hook on the calling thread for every call and a forced status replaces the result`() {
        val fake = FakeNativeKeyStoreBinding()
        val hooked = AtomicBoolean()
        fake.before = { name: String -> if (name == "probe") hooked.set(true) }
        fake.forcedStatus["probe"] = -25308
        fake.forcedStatus["addGenericPassword"] = -34018

        assertEquals(-25308, fake.macOs().probe(allowUi = false))
        assertTrue(hooked.get())
        assertEquals(-34018, fake.macOs().addGenericPassword("s", "a", "l", byteArrayOf(1)))
        assertTrue(fake.macItems.isEmpty(), "a forced status must have no effect")
    }
}
