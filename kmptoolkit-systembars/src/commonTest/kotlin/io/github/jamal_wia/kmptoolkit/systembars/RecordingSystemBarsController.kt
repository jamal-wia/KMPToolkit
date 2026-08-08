package io.github.jamal_wia.kmptoolkit.systembars

/**
 * The real controller with the platform call recorded instead of performed.
 *
 * The point of testing through the production [LayeredSystemBarsController] rather than a
 * hand-written stand-in is that the layering, the atomic transitions and the "did anything actually
 * change" decision are the behaviour under test — a stand-in would only re-implement them and then
 * agree with itself.
 */
internal class RecordingSystemBarsController(
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
) : LayeredSystemBarsController(initialConfig) {

    /** Every configuration handed to the platform, in order. */
    val applied: MutableList<SystemBarsConfig> = mutableListOf()

    /**
     * Turned off by the concurrency tests. A plain list appended from several threads is itself a
     * race, and a test that trips over its own instrumentation proves nothing about the code.
     */
    var recordApplications: Boolean = true

    override fun applyToPlatform(config: SystemBarsConfig) {
        if (recordApplications) applied += config
    }

    /** Exposes the protected re-apply hook so the "recreated window" path is testable. */
    fun forceReapply() {
        reapplyToPlatform()
    }
}
