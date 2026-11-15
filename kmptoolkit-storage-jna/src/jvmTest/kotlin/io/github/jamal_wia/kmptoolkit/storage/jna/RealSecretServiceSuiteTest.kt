package io.github.jamal_wia.kmptoolkit.storage.jna

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretLookup
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Suite L of plan 6.4 over the real Secret Service, through this artifact's JNA binding. Opt-in:
 * `gradlew :kmptoolkit-storage-jna:jvmTest --tests *RealSecretServiceSuiteTest -Pkmptoolkit.secretstore=linux`
 * (add `-Pkmptoolkit.secretstore.required=true` to make a skip a failure). Run it on JDK 21 (this artifact is the JDK 21 binding).
 *
 * The run is prepared by the environment, and `-Pkmptoolkit.secretstore.scenario` says how:
 * - `unlocked` (default): `dbus-run-session -- sh -c 'echo "" | gnome-keyring-daemon --unlock --components=secrets; <gradle command>'`
 *   gives a session bus with an unlocked default collection;
 * - `nobus`: no session bus (`DBUS_SESSION_BUS_ADDRESS` unset, no `$XDG_RUNTIME_DIR/bus`): every call must answer a failure status;
 * - `noservice`: a session bus with no Secret Service owner and none activatable: every call must answer a failure status.
 *
 * Every item this suite stores carries an attribute value made from this process id and the test name, and is cleared by
 * exactly those attributes in `AfterTest`, so no other secret is ever read, changed or removed.
 */
class RealSecretServiceSuiteTest {

    private val identities: MutableList<Map<String, String>> = mutableListOf()
    private lateinit var calls: JnaSecretServiceCalls
    private val secret: ByteArray = "c2VjcmV0LWtleS1iYXNlNjQ=".toByteArray()

    @BeforeTest
    fun setUp() {
        RealStoreGate.enter("linux")
        calls = JnaSecretServiceCalls(lazy(LinuxPlatform::load))
    }

    @AfterTest
    fun cleanUp() {
        if (!::calls.isInitialized || RealStoreGate.scenario != "unlocked") return
        for (identity: Map<String, String> in identities) calls.clear(identity)
    }

    private fun identity(test: String): Map<String, String> {
        val attributes: Map<String, String> = linkedMapOf("service" to RealStoreGate.identity(test), "directory" to "0123456789abcdef0123456789abcdef")
        identities.add(attributes)
        return attributes
    }

    private fun assumeScenario(expected: String) = Assume.assumeTrue("scenario ${RealStoreGate.scenario}, this test needs $expected", RealStoreGate.scenario == expected)

    @Test
    fun `libsecret and every symbol of the binding resolve on this machine`() {
        assumeScenario("unlocked")
        assertIs<LinuxPlatform.Ready>(LinuxPlatform.load(), "libsecret 0.19 or newer must load here")
    }

    @Test
    fun `an item stored with its label and attributes is found, then cleared, then not found`() {
        assumeScenario("unlocked")
        val attributes: Map<String, String> = identity("roundtrip")
        assertSame(SecretLookup.NotFound, assertIs<RawResult.Success<SecretLookup>>(calls.lookup(attributes, allowUi = true)).value)
        assertEquals(RawStatus.SUCCESS, calls.store("KMPToolkit test item", attributes, secret))
        val found = assertIs<SecretLookup.Found>(assertIs<RawResult.Success<SecretLookup>>(calls.lookup(attributes, allowUi = false)).value)
        assertContentEquals(secret, found.secret)
        assertEquals(RawStatus.SUCCESS, calls.clear(attributes))
        assertSame(SecretLookup.NotFound, assertIs<RawResult.Success<SecretLookup>>(calls.lookup(attributes, true)).value)
        assertEquals(RawStatus.SUCCESS, calls.clear(attributes), "clearing nothing is a success")
    }

    @Test
    fun `two identities stay separate and a partial attribute set does not match`() {
        assumeScenario("unlocked")
        val first: Map<String, String> = identity("separate-first")
        val second: Map<String, String> = identity("separate-second")
        assertEquals(RawStatus.SUCCESS, calls.store("first", first, secret))
        assertSame(SecretLookup.NotFound, assertIs<RawResult.Success<SecretLookup>>(calls.lookup(second, true)).value)
        assertEquals(RawStatus.SUCCESS, calls.clear(second))
        assertIs<SecretLookup.Found>(assertIs<RawResult.Success<SecretLookup>>(calls.lookup(first, true)).value, "clearing the other identity left this one")
    }

    @Test
    fun `the default collection is unlocked and the backend is not the portal on a plain session`() {
        assumeScenario("unlocked")
        assertEquals(false, assertIs<RawResult.Success<Boolean>>(calls.defaultCollectionLocked()).value)
        assertEquals(false, assertIs<RawResult.Success<Boolean>>(calls.backendIsPortal()).value)
    }

    @Test
    fun `without a session bus or a Secret Service every call is a failure status, never an exception`() {
        Assume.assumeTrue("scenario ${RealStoreGate.scenario}", RealStoreGate.scenario == "nobus" || RealStoreGate.scenario == "noservice")
        val attributes: Map<String, String> = identity("absent")
        val lookup: RawStatus = assertIs<RawResult.Failure>(calls.lookup(attributes, false)).status
        val store: RawStatus = calls.store("l", attributes, secret)
        val locked: RawStatus = assertIs<RawResult.Failure>(calls.defaultCollectionLocked()).status
        for (status: RawStatus in listOf(lookup, store, locked)) {
            assertFalse(status.isSuccess)
            assertFalse(status.isSymbolMissing, "libsecret loaded; it is the service that is missing")
            assertTrue(status.domain != null, "a libsecret failure carries its GError domain: $status")
        }
        println("scenario=${RealStoreGate.scenario} lookup=$lookup store=$store collectionLocked=$locked")
    }

    @Test
    fun `a thousand operations leave the descriptor count unchanged`() {
        assumeScenario("unlocked")
        val attributes: Map<String, String> = identity("descriptors")
        assertEquals(RawStatus.SUCCESS, calls.store("descriptor loop", attributes, secret))
        repeat(WARM_UP) { calls.lookup(attributes, false) }
        val before: Long = RealStoreGate.openDescriptors()
        assertTrue(before > 0, "control: the descriptor count is readable")
        repeat(1_000) { index: Int ->
            assertIs<SecretLookup.Found>(assertIs<RawResult.Success<SecretLookup>>(calls.lookup(attributes, index % 2 == 0)).value)
            if (index % 100 == 0) calls.defaultCollectionLocked()
        }
        assertEquals(before, RealStoreGate.openDescriptors(), "1,000 Secret Service operations must not leak a descriptor")
    }

    private companion object {
        const val WARM_UP: Int = 50
    }
}
