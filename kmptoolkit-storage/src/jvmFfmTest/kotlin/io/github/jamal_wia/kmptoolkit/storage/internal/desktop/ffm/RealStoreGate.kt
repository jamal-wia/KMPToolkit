package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import org.junit.Assume

/**
 * The opt-in gate of the real-store suites (plan 3.9, 6.4): `-Pkmptoolkit.secretstore=macos|windows|linux` runs the suite of that
 * OS against the real operating-system key store; every other suite skips. `-Pkmptoolkit.secretstore.required=true` turns a skip
 * into a failure, so a gate that must run cannot silently run nothing.
 */
internal object RealStoreGate {

    /** The selected OS, or an empty string. */
    val selected: String get() = System.getProperty("kmptoolkit.secretstore").orEmpty()

    /** Whether `-Pkmptoolkit.secretstore.required=true` was given. */
    val required: Boolean get() = System.getProperty("kmptoolkit.secretstore.required") == "true"

    /**
     * Whether `-Pkmptoolkit.secretstore.oldLibsecret=true` was given: the run is on a libsecret older than 0.19 (RHEL 8's 0.18.6),
     * where the Linux binding is expected to answer SYMBOL_MISSING on every call. Without it a SYMBOL_MISSING answer from a real
     * libsecret is a defect of the binding, never a reason to skip.
     */
    val oldLibsecret: Boolean get() = System.getProperty("kmptoolkit.secretstore.oldLibsecret") == "true"

    /** Runs the calling test when the suite of [os] is selected; skips it, or fails it when required and nothing was selected. */
    fun require(os: String) {
        if (selected == os) return
        if (selected.isEmpty() && required) {
            throw AssertionError("kmptoolkit.secretstore.required=true but -Pkmptoolkit.secretstore is not set (expected $os)")
        }
        Assume.assumeTrue("real-store suite for $os is off (-Pkmptoolkit.secretstore=$os to run it)", false)
    }

    /** A skip that becomes a failure when the real store is required: the environment cannot serve the suite ([reason]). */
    fun environmentUnavailable(os: String, reason: String): Nothing {
        if (required && selected == os) throw AssertionError("real-store suite for $os is required but the environment cannot run it: $reason")
        Assume.assumeTrue("real-store suite for $os cannot run here: $reason", false)
        throw AssertionError("unreachable")
    }
}
