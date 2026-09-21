package io.github.jamal_wia.kmptoolkit.video.player

/**
 * Marks API that `kmptoolkit-video-player-compose` needs from this module but that is not part of
 * the public contract — the platform player behind a [VideoPlayer], which the Compose surface
 * renders. It can change in any release without a major-version bump; see `docs/01-architecture.md`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Cross-module internal API — not part of the public contract.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ToolkitInternalApi
