package io.github.jamal_wia.kmptoolkit.flashlight

/**
 * Blinks the device's camera torch as a cue, on a repeating on/off rhythm.
 *
 * This is the only type your shared code should depend on. The concrete instance is built in
 * platform code — `createFlashlight(context)` on Android, `createFlashlight()` on iOS — and
 * handed to common code as this interface, which is what keeps a `Context` out of `commonMain`
 * without an `expect` declaration that would have to lie about its parameters.
 *
 * **Contract:**
 * - The torch is a **cue, not a light**: [start] never just turns it on, only ever blinks it in a
 *   [FlashPattern], and every implementation leaves it OFF once [stop] runs — including after
 *   cancellation mid-cycle.
 * - [start] returns immediately; the blinking runs on its own until [stop]. Calling it again
 *   replaces the running pattern rather than layering a second one on top, so a caller may re-arm
 *   freely.
 * - Neither [start] nor [stop] throws. A device with no flash unit, or a torch the system refuses
 *   to hand over (another app holds the camera), makes every call a silent no-op — losing one cue
 *   among several is never worth an exception in the code that asked for it.
 * - Implementations are safe to call from any thread.
 *
 * Callers do not have to check [isAvailable] before calling — every call is already a no-op
 * without a torch. It exists so a caller that wants to fall back to another cue can ask first.
 *
 * Implement it yourself when you need a decorator: a settings-aware wrapper that checks the user's
 * "torch cue" preference before delegating is the common case, and is a few lines.
 */
public interface Flashlight {

    /** Whether this device has a torch at all. False on any device with a camera and no flash. */
    public val isAvailable: Boolean

    /**
     * Starts blinking [pattern] and keeps going until [stop].
     *
     * Returns immediately; the blinking runs on its own. Calling it again replaces the running
     * pattern rather than stacking a second one, so a caller may re-arm freely.
     */
    public fun start(pattern: FlashPattern)

    /** Stops the blinking and leaves the torch off. Safe to call when nothing is running. */
    public fun stop()
}

/**
 * A [Flashlight] that does nothing, reports [Flashlight.isAvailable] as `false`, and ignores every
 * [Flashlight.start] and [Flashlight.stop] call.
 *
 * Use it as the instance you inject when the user has turned this cue off in your settings, or on
 * a target where you have not wired a real implementation — that way the call sites in shared code
 * stay unconditional instead of growing a null check each.
 *
 * The returned instance is stateless; calling this repeatedly is free of consequence.
 */
public fun noOpFlashlight(): Flashlight = NoOpFlashlight

private object NoOpFlashlight : Flashlight {
    override val isAvailable: Boolean = false
    override fun start(pattern: FlashPattern): Unit = Unit
    override fun stop(): Unit = Unit
}
