package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Memory
import com.sun.jna.Pointer
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Suite M of plan 6.4 over the real login keychain, through this artifact's JNA binding. Opt-in:
 * `./gradlew :kmptoolkit-storage-jna:jvmTest --tests '*RealKeychainSuiteTest' -Pkmptoolkit.secretstore=macos`
 * (add `-Pkmptoolkit.secretstore.required=true` to make a skip a failure).
 *
 * Safety: every item this suite creates has a service name made from this process id and the test name, so no other keychain
 * item is ever read, changed or deleted; each test deletes its own identities in `finally`/`AfterTest`, before and after.
 * An item created by this process trusts this process, so no access dialog is expected; **a dialog on screen means stop**.
 */
class RealKeychainSuiteTest {

    private val services: MutableList<String> = mutableListOf()
    private lateinit var calls: JnaMacKeychainCalls

    @BeforeTest
    fun setUp() {
        RealStoreGate.enter("macos")
        calls = JnaMacKeychainCalls(lazy(MacPlatform::load))
    }

    @AfterTest
    fun cleanUp() {
        if (!::calls.isInitialized) return
        // Only identities this suite named itself: no pattern, no wildcard, nothing it did not create.
        for (service: String in services) calls.deleteGenericPassword(service, ACCOUNT)
    }

    private fun service(test: String): String {
        val name: String = RealStoreGate.identity(test)
        services.add(name)
        calls.deleteGenericPassword(name, ACCOUNT)
        return name
    }

    private val key: ByteArray = ByteArray(32) { (it * 3 + 1).toByte() }

    @Test
    fun `an item round trips, a duplicate add is refused, and a delete removes exactly it`() {
        val service: String = service("roundtrip")
        assertEquals(-25300, assertIs<RawResult.Failure>(calls.copyGenericPassword(service, ACCOUNT, allowUi = true)).status.code, "absent: errSecItemNotFound, interactive")
        assertEquals(-25300, assertIs<RawResult.Failure>(calls.copyGenericPassword(service, ACCOUNT, allowUi = false)).status.code, "absent: non-interactive (the UIFail read of 4.5 case: recorded)")

        assertEquals(0, calls.addGenericPassword(service, ACCOUNT, "KMPToolkit test item", key))
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(service, ACCOUNT, true)).value)
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(service, ACCOUNT, false)).value, "an item this process created reads without a dialog")
        assertEquals(-25299, calls.addGenericPassword(service, ACCOUNT, "again", ByteArray(32) { 9 }), "errSecDuplicateItem: a key item is never updated")
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(service, ACCOUNT, true)).value, "the refused add changed nothing")

        assertEquals(0, calls.deleteGenericPassword(service, ACCOUNT))
        assertEquals(-25300, assertIs<RawResult.Failure>(calls.copyGenericPassword(service, ACCOUNT, true)).status.code)
        assertEquals(-25300, calls.deleteGenericPassword(service, ACCOUNT), "deleting an absent item is errSecItemNotFound")
    }

    @Test
    fun `two stores and non-ASCII names stay separate`() {
        val first: String = service("two-first-ünï")
        val second: String = service("two-second")
        assertEquals(0, calls.addGenericPassword(first, ACCOUNT, "first", key))
        assertEquals(0, calls.addGenericPassword(second, ACCOUNT, "second", ByteArray(32) { 5 }))
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(first, ACCOUNT, true)).value)
        assertContentEquals(ByteArray(32) { 5 }, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(second, ACCOUNT, true)).value)
        assertEquals(0, calls.deleteGenericPassword(first, ACCOUNT))
        assertEquals(0, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(second, ACCOUNT, true)).value!![0] - 5, "deleting one store leaves the other")
    }

    @Test
    fun `the probe never prompts and answers a status`() {
        assertTrue(calls.probe(allowUi = false) in listOf(0, -25300), "attribute-only probe of an absent service")
        assertTrue(calls.probe(allowUi = true) in listOf(0, -25300))
    }

    @Test
    fun `a data-protection keychain request from an unentitled java is errSecMissingEntitlement`() {
        val service: String = service("entitlement")
        val platform: MacPlatform = MacPlatform.load()
        val cf: CoreFoundationApi = platform.coreFoundation
        fun constant(name: String): Pointer = platform.symbols.address(name)!!.getPointer(0L)
        val text: ByteArray = service.toByteArray()
        val bytes = Memory(text.size.toLong()).also { it.write(0L, text, 0, text.size) }
        val account: ByteArray = ACCOUNT.toByteArray()
        val accountBytes = Memory(account.size.toLong()).also { it.write(0L, account, 0, account.size) }
        val refs: MutableList<Pointer> = mutableListOf()
        try {
            val dictionary: Pointer = cf.CFDictionaryCreateMutable(null, 0L, platform.symbols.address("kCFTypeDictionaryKeyCallBacks"), platform.symbols.address("kCFTypeDictionaryValueCallBacks"))!!.also { refs.add(it) }
            val serviceRef: Pointer = cf.CFStringCreateWithBytes(null, bytes, text.size.toLong(), JnaMacKeychainCalls.UTF8_ENCODING, false)!!.also { refs.add(it) }
            val accountRef: Pointer = cf.CFStringCreateWithBytes(null, accountBytes, account.size.toLong(), JnaMacKeychainCalls.UTF8_ENCODING, false)!!.also { refs.add(it) }
            val data: Pointer = cf.CFDataCreateMutable(null, 0L)!!.also { refs.add(it) }
            val secret = Memory(32).also { it.write(0L, key, 0, 32) }
            cf.CFDataAppendBytes(data, secret, 32L)
            secret.clear()
            cf.CFDictionarySetValue(dictionary, constant("kSecClass"), constant("kSecClassGenericPassword"))
            cf.CFDictionarySetValue(dictionary, constant("kSecAttrService"), serviceRef)
            cf.CFDictionarySetValue(dictionary, constant("kSecAttrAccount"), accountRef)
            cf.CFDictionarySetValue(dictionary, constant("kSecValueData"), data)
            cf.CFDictionarySetValue(dictionary, constant("kSecUseDataProtectionKeychain"), constant("kCFBooleanTrue"))
            val status: Int = platform.security.SecItemAdd(dictionary, null)
            try {
                assertEquals(-34018, status, "the data-protection keychain needs an entitlement the test java does not have")
            } finally {
                if (status == 0) platform.security.SecItemDelete(dictionary)
            }
        } finally {
            for (index: Int in refs.indices.reversed()) cf.CFRelease(refs[index])
        }
    }

    @Test
    fun `a thousand operations leave the descriptor count unchanged`() {
        val service: String = service("descriptors")
        assertEquals(0, calls.addGenericPassword(service, ACCOUNT, "descriptor loop", key))
        repeat(WARM_UP) { calls.copyGenericPassword(service, ACCOUNT, false) }
        val before: Long = RealStoreGate.openDescriptors()
        assertTrue(before > 0, "control: the descriptor count is readable")
        repeat(1_000) { index: Int ->
            assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(service, ACCOUNT, index % 2 == 0))
            if (index % 100 == 0) calls.probe(false)
        }
        val after: Long = RealStoreGate.openDescriptors()
        assertEquals(before, after, "1,000 keychain operations must not leak a descriptor")
        assertEquals(RawStatus(0).code, calls.deleteGenericPassword(service, ACCOUNT))
    }

    private companion object {
        const val ACCOUNT: String = "dir-0123456789abcdef0123456789abcdef"
        const val WARM_UP: Int = 50
    }
}
