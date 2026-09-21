package io.github.jamal_wia.kmptoolkit.video.player.compose

/**
 * Marks an interface of this module that is safe to **use** but not to **implement** outside it —
 * [VideoControlsScope]. Its implementations belong to this library, and new members may be added to
 * it in a minor release; a class of yours implementing it would stop compiling then, or fail at
 * runtime against a newer library. Implementing it therefore needs an explicit opt-in, and nothing
 * the library promises covers such an implementation.
 *
 * Calling, reading and passing a [VideoControlsScope] around needs no opt-in: a scope is obtained
 * from [VideoPlayer]'s `controls` slot or from [rememberVideoControlsScope].
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Implemented by kmptoolkit-video-player-compose only: new members may be added in a minor release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class InternalForInheritanceVideoControlsApi
