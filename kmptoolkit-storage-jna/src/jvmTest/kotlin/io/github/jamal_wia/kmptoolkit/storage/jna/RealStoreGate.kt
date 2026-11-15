package io.github.jamal_wia.kmptoolkit.storage.jna

import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.util.Locale
import org.junit.Assume

/**
 * The opt-in of the real-store suites (plan 3.9, 6.4): `-Pkmptoolkit.secretstore=macos|windows|linux` runs the suite of
 * that operating system, and `-Pkmptoolkit.secretstore.required=true` turns "this suite could not run here" into a failure,
 * so a broken key store cannot pass as a green run. A suite of another operating system is always skipped.
 */
internal object RealStoreGate {
    private val property: String = System.getProperty("kmptoolkit.secretstore").orEmpty()
    private val required: Boolean = System.getProperty("kmptoolkit.secretstore.required") == "true"

    fun hostIs(store: String): Boolean {
        val name: String = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        return when (store) {
            "macos" -> name.startsWith("mac")
            "windows" -> name.startsWith("windows")
            else -> name.startsWith("linux")
        }
    }

    /** Skips the calling test unless the suite of [store] is enabled for this host; fails instead when the run is `required`. */
    fun enter(store: String) {
        if (!hostIs(store)) {
            if (required && property == store) error("kmptoolkit.secretstore=$store was requested on a host that is not $store")
            Assume.assumeTrue("the $store real-store suite needs a $store host", false)
        }
        if (property != store) {
            check(!required) { "the $store real-store suite is required but -Pkmptoolkit.secretstore=$store was not given" }
            Assume.assumeTrue("the $store real-store suite is opt-in: -Pkmptoolkit.secretstore=$store", false)
        }
    }

    /** The run scenario of the Linux suite: `unlocked` (default), `nobus` or `noservice`. */
    val scenario: String get() = System.getProperty("kmptoolkit.secretstore.scenario").orEmpty().ifEmpty { "unlocked" }

    /** A name no other process or test run shares: the process id and the test, with no randomness. */
    fun identity(test: String): String = "kmptoolkit-storage-jna-test.${ProcessHandle.current().pid()}.$test"

    /** Open file descriptors of this process (macOS and Linux); a host that cannot count them fails the test. */
    fun openDescriptors(): Long = TestFs.requireOpenDescriptorCount()
}
