package io.github.jamal_wia.kmptoolkit.storage.jna

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Breaker round 1 of group sto-jna (STO-8) over the real login keychain: binary keys, non-BMP identities, the
 * non-interactive add/read/delete path end to end, and an add/delete churn loop. Opt-in exactly like suite M:
 * `./gradlew :kmptoolkit-storage-jna:jvmTest --tests '*RealKeychainBreakerR1Test' -Pkmptoolkit.secretstore=macos`.
 *
 * Safety: every item has a service made from this process id and the test name ([RealStoreGate.identity]); each test deletes
 * exactly its own identities before and after (AfterTest), nothing else is ever read, changed or deleted. Items created by
 * this process trust it, so no dialog is expected; a dialog on screen means stop.
 */
class RealKeychainBreakerR1Test {

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
        for (service: String in services) calls.deleteGenericPassword(service, ACCOUNT, allowUi = true)
    }

    private fun service(test: String): String {
        val name: String = RealStoreGate.identity("breaker-r1.$test")
        services.add(name)
        calls.deleteGenericPassword(name, ACCOUNT, allowUi = true)
        return name
    }

    @Test
    fun `a key holding every byte value, zeros included, round trips without truncation on the non-interactive path`() {
        val service: String = service("binary")
        val key = ByteArray(256) { it.toByte() }
        assertEquals(0, calls.addGenericPassword(service, ACCOUNT, "binary", key, allowUi = false))
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(service, ACCOUNT, allowUi = false)).value)
        assertEquals(0, calls.deleteGenericPassword(service, ACCOUNT, allowUi = false), "a non-interactive delete of an own item succeeds")
        assertEquals(-25300, assertIs<RawResult.Failure>(calls.copyGenericPassword(service, ACCOUNT, allowUi = false)).status.code)
    }

    @Test
    fun `identities that differ only past the Basic Multilingual Plane stay separate`() {
        val first: String = service("emoji-🔑")
        val second: String = service("emoji-🔒")
        assertEquals(0, calls.addGenericPassword(first, ACCOUNT, "first 🔑", ByteArray(32) { 1 }, allowUi = true))
        assertEquals(0, calls.addGenericPassword(second, ACCOUNT, "second", ByteArray(32) { 2 }, allowUi = true), "a near-identical service is a different item, not a duplicate")
        assertContentEquals(ByteArray(32) { 1 }, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(first, ACCOUNT, true)).value)
        assertContentEquals(ByteArray(32) { 2 }, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(second, ACCOUNT, true)).value)
    }

    @Test
    fun `two hundred add and delete cycles leave the descriptor count unchanged and the last key readable`() {
        val service: String = service("churn")
        repeat(WARM_UP) { index: Int ->
            assertEquals(0, calls.addGenericPassword(service, ACCOUNT, "churn", ByteArray(32) { index.toByte() }, allowUi = false))
            assertEquals(0, calls.deleteGenericPassword(service, ACCOUNT, allowUi = false))
        }
        val before: Long = RealStoreGate.openDescriptors()
        assertTrue(before > 0, "control: the descriptor count is readable")
        repeat(CYCLES) { index: Int ->
            assertEquals(0, calls.addGenericPassword(service, ACCOUNT, "churn", ByteArray(32) { index.toByte() }, allowUi = index % 2 == 0), "cycle $index add")
            assertContentEquals(ByteArray(32) { index.toByte() }, assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword(service, ACCOUNT, false)).value, "cycle $index read")
            if (index < CYCLES - 1) assertEquals(0, calls.deleteGenericPassword(service, ACCOUNT, allowUi = index % 3 == 0), "cycle $index delete")
        }
        assertEquals(before, RealStoreGate.openDescriptors(), "add/read/delete churn must not leak a descriptor")
    }

    private companion object {
        const val ACCOUNT: String = "dir-fedcba9876543210fedcba9876543210"
        const val WARM_UP: Int = 10
        const val CYCLES: Int = 200
    }
}
