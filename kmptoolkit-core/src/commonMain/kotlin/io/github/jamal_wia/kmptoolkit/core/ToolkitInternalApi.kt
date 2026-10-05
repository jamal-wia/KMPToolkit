package io.github.jamal_wia.kmptoolkit.core

/**
 * Marks API that one `kmptoolkit-*` module needs from another but that is not part of the public
 * contract of the suite — the platform player behind a video player surface, the state-machine lock
 * the players and the recorder share. It is `public` only because Kotlin has no visibility between
 * "same module" and "everyone", and it can change in any release without a major-version bump; see
 * `docs/01-architecture.md`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Cross-module internal API — not part of the public contract.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ToolkitInternalApi
