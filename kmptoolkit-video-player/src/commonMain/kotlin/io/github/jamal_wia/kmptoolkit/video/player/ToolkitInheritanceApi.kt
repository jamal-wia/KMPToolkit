package io.github.jamal_wia.kmptoolkit.video.player

/**
 * Marks a public interface that only KMPToolkit implements — [VideoPlayer] here, and the controls
 * scope in `kmptoolkit-video-player-compose`. Consumers use these types freely; they do not
 * implement them, because a new abstract member may be added in any minor release, and a class
 * outside the library implementing the interface would then no longer compile or link.
 *
 * Implementing such an interface requires opting in to this annotation, which is an explicit
 * acceptance of that risk. There should be no need: to test code that consumes a [VideoPlayer],
 * build a real one over the scriptable engine from `kmptoolkit-video-player-testing` —
 * `createVideoPlayer(FakeVideoPlaybackEngine())`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Implemented by KMPToolkit only: new abstract members may be added in any release. " +
        "Use the library's own implementation (for a test double, createVideoPlayer over " +
        "FakeVideoPlaybackEngine from kmptoolkit-video-player-testing).",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class ToolkitInheritanceApi
