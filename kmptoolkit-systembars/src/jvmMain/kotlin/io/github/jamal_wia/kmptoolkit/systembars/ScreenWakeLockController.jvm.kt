package io.github.jamal_wia.kmptoolkit.systembars

/** Creates the JVM (desktop) [ScreenWakeLockController]. */
public fun createScreenWakeLockController(): ScreenWakeLockController = JvmScreenWakeLockController()

/**
 * Desktop no-op.
 *
 * Keeping a desktop display awake means talking to the host OS power manager — a different API per
 * platform, none of them reachable from plain JVM Compose, and none of them what this module's
 * contract models (a foreground window asking its own screen to stay on). A caller that keeps the
 * screen on for audio or video playback therefore still compiles and runs on desktop; the display
 * simply follows the user's own power settings, which is the behaviour a desktop user expects
 * anyway.
 */
internal class JvmScreenWakeLockController : ScreenWakeLockController {

    override fun setKeepScreenOn(enabled: Boolean) {
        // no-op — see the class KDoc
    }
}
