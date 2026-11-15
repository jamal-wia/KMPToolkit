package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretServiceCalls
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The old-libsecret run of suite L (plan 6.4, 4.7): on a Linux whose libsecret is older than 0.19 (a Rocky Linux 8 container has
 * 0.18.6) every call of the FFM binding answers the dedicated SYMBOL_MISSING status and throws nothing; above the seam that is
 * `Unsupported(NO_KEY_STORE)`. Runs only with `-Pkmptoolkit.secretstore=linux -Pkmptoolkit.secretstore.oldLibsecret=true`, and
 * on any other libsecret it must FAIL (the answer would then be a defect of the binding, as `secret_backend_get_sync` was).
 * With `-Pkmptoolkit.secretstore.required=true` a run without the properties fails too, so the lead's command cannot run nothing.
 */
class FfmSecretServiceOldLibsecretRealStoreTest {

    private val ledger: NativeLedger = NativeLedger()
    private val attributes: Map<String, String> =
        mapOf("service" to "kmptoolkit-storage.test.ffm-old-${ProcessHandle.current().pid()}", "directory" to "0".repeat(32))

    private fun calls(): SecretServiceCalls {
        RealStoreGate.require("linux")
        Assume.assumeTrue("the old-libsecret run is off (-Pkmptoolkit.secretstore.oldLibsecret=true on a libsecret older than 0.19)", RealStoreGate.oldLibsecret)
        return FfmNativeKeyStoreBinding(Foreign.system(), ledger, FfmOs.LINUX).linux()
    }

    @Test
    fun `every call answers SYMBOL_MISSING on a libsecret older than 0_19 and nothing is thrown or leaked`() {
        val service: SecretServiceCalls = calls()

        assertTrue(assertIs<RawResult.Failure>(service.lookup(attributes, allowUi = false)).status.isSymbolMissing)
        assertEquals(RawStatus.SYMBOL_MISSING, service.store("old libsecret", attributes, byteArrayOf(1, 2, 3)))
        assertEquals(RawStatus.SYMBOL_MISSING, service.clear(attributes))
        assertTrue(assertIs<RawResult.Failure>(service.defaultCollectionLocked()).status.isSymbolMissing)
        assertTrue(assertIs<RawResult.Failure>(service.backendIsPortal()).status.isSymbolMissing)
        assertEquals(0, ledger.acquired, "nothing was acquired from a library that was refused at load")
    }
}
