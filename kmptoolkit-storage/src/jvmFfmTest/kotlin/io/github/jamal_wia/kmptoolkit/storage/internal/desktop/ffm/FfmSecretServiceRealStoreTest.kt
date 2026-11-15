package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretLookup
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretServiceCalls
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.util.Base64
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
 * Suite L (plan 6.4): the libsecret FFM binding against a real Secret Service, with `-Pkmptoolkit.secretstore=linux`. Needs a
 * session bus and an unlocked default collection, for example in a container:
 * `dbus-run-session -- sh -c 'echo "" | gnome-keyring-daemon --unlock --components=secrets; <gradle command>'`.
 *
 * Every item it creates has a unique `service` attribute (`kmptoolkit-storage.test.ffm-<pid>-<n>`) and is cleared in
 * `@AfterTest`; no other item is touched. An environment without a Secret Service skips the suite (fails it with
 * `-Pkmptoolkit.secretstore.required=true`). Daemon-manipulating cases (locked collection, killed daemon, dismissed prompt) and
 * the old-libsecret container run are manual runs of the lead and need a scripted environment this suite does not own.
 */
class FfmSecretServiceRealStoreTest {

    private val ledger: NativeLedger = NativeLedger()
    private lateinit var calls: SecretServiceCalls
    private val created: MutableList<Map<String, String>> = mutableListOf()

    @BeforeTest
    fun setUp() {
        RealStoreGate.require("linux")
        calls = FfmNativeKeyStoreBinding(Foreign.system(), ledger, FfmOs.LINUX).linux()
        val probe: RawResult<SecretLookup> = calls.lookup(newAttributes(), allowUi = false)
        if (probe is RawResult.Failure) {
            RealStoreGate.environmentUnavailable("linux", "the Secret Service answered ${probe.status}")
        }
    }

    @AfterTest
    fun tearDown() {
        if (!::calls.isInitialized) return
        created.forEach { attributes: Map<String, String> -> calls.clear(attributes) }
        assertEquals(0, ledger.outstanding, "every glib object the calls created must be released")
    }

    private fun newAttributes(): Map<String, String> =
        mapOf("service" to "kmptoolkit-storage.test.ffm-${ProcessHandle.current().pid()}-${COUNTER.incrementAndGet()}", "directory" to "0".repeat(32))
            .also { created.add(it) }

    private fun secret(seed: Long): ByteArray =
        Base64.getEncoder().encode(ByteArray(32).also { Random(seed).nextBytes(it) })

    @Test
    fun `store lookup clear round trip with label and attributes`() {
        val attributes: Map<String, String> = newAttributes()
        val value: ByteArray = secret(1L)

        assertEquals(RawStatus.SUCCESS, calls.store("kmptoolkit test item", attributes, value))
        val found: SecretLookup = assertIs<RawResult.Success<SecretLookup>>(calls.lookup(attributes, allowUi = true)).value
        assertContentEquals(value, assertIs<SecretLookup.Found>(found).secret)
        assertEquals(RawStatus.SUCCESS, calls.clear(attributes))
        assertEquals(SecretLookup.NotFound, assertIs<RawResult.Success<SecretLookup>>(calls.lookup(attributes, allowUi = true)).value)
    }

    @Test
    fun `a lookup that matches nothing is NotFound and clearing nothing succeeds`() {
        val attributes: Map<String, String> = newAttributes()

        assertEquals(SecretLookup.NotFound, assertIs<RawResult.Success<SecretLookup>>(calls.lookup(attributes, allowUi = false)).value)
        assertEquals(RawStatus.SUCCESS, calls.clear(attributes))
    }

    @Test
    fun `the two attributes address one identity only and clearing one keeps the other`() {
        val one: Map<String, String> = newAttributes()
        val two: Map<String, String> = one + ("directory" to "1".repeat(32))
        created.add(two)
        assertEquals(RawStatus.SUCCESS, calls.store("one", one, secret(2L)))
        assertEquals(RawStatus.SUCCESS, calls.store("two", two, secret(3L)))

        assertEquals(RawStatus.SUCCESS, calls.clear(one))

        assertEquals(SecretLookup.NotFound, assertIs<RawResult.Success<SecretLookup>>(calls.lookup(one, true)).value)
        assertContentEquals(secret(3L), assertIs<SecretLookup.Found>(assertIs<RawResult.Success<SecretLookup>>(calls.lookup(two, true)).value).secret)
    }

    @Test
    fun `the backend and collection questions answer without prompting`() {
        assertIs<RawResult.Success<Boolean>>(calls.backendIsPortal())
        assertIs<RawResult.Success<Boolean>>(calls.defaultCollectionLocked())
        assertEquals(0, ledger.outstanding)
    }

    @Test
    fun `one thousand operations leave no descriptor and no glib object behind`() {
        val attributes: Map<String, String> = newAttributes()
        val value: ByteArray = secret(4L)
        val before: Long = TestFs.requireOpenDescriptorCount()

        repeat(250) {
            assertEquals(RawStatus.SUCCESS, calls.store("loop", attributes, value))
            assertIs<SecretLookup.Found>(assertIs<RawResult.Success<SecretLookup>>(calls.lookup(attributes, allowUi = false)).value)
            assertEquals(RawStatus.SUCCESS, calls.clear(attributes))
            assertEquals(SecretLookup.NotFound, assertIs<RawResult.Success<SecretLookup>>(calls.lookup(attributes, allowUi = false)).value)
        }

        assertEquals(0, ledger.outstanding, "leaked glib objects")
        assertEquals(before, TestFs.requireOpenDescriptorCount(), "leaked file descriptors")
        assertTrue(ledger.acquired > 1000, "control: the loop acquired objects (${ledger.acquired})")
    }

    private companion object {
        val COUNTER: AtomicInteger = AtomicInteger()
    }
}
