package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D2 round 15, r15-D1 (ruling reviews/d2-r15-ruling-opus.md): the exception classes JavaFX really throws for a `Remote` URL it
 * rejects while building its `Media`, before it opens anything. The docs (03-guide "Sources on desktop" and "Errors you can see",
 * 04-api-reference, 05-platform-notes) state: a URL JavaFX cannot parse or that has no scheme arrives as `IllegalArgumentException`,
 * a protocol JavaFX does not support (for example `ftp:`) as `UnsupportedOperationException`, in the guide's `else` branch (neither
 * is a `MediaException`, a `FileNotFoundException` or a `JavaFxVideoPlayerException`).
 *
 * Real media: JavaFX's `Media` constructor needs the toolkit, so the test goes through the public factory. No network is touched:
 * every URL is rejected before a connection is attempted. Only the exception CLASSES are asserted, never the messages. Every wait is
 * the `prepare` call itself under a hang bound that fails with its own message.
 */
class JavaFxRemoteUrlRejectionR15AdversarialTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    /** Prepares [url] on a fresh player through the public factory and returns the cause of the resulting `Error` state. */
    private fun causeOf(url: String): Throwable {
        val player: VideoPlayer = createJavaFxVideoPlayer(VideoPlayerConfig())
        try {
            runBlocking { withTimeout(HANG_MS) { player.prepare(VideoSource.Remote(url)) } }
            val state: VideoPlayerState = player.stateFlow.value
            assertTrue(state is VideoPlayerState.Error, "$url: the state is $state, not Error")
            return (state as VideoPlayerState.Error).cause
        } finally {
            player.release()
        }
    }

    private fun assertLandsInTheElseBranch(url: String, cause: Throwable) {
        val inAnEarlierBranch: Boolean = cause is JavaFxVideoPlayerException ||
            cause is java.io.FileNotFoundException ||
            cause is javafx.scene.media.MediaException
        assertTrue(!inAnEarlierBranch, "$url: ${cause::class.java.name} would be caught by an earlier branch of the guide's when")
    }

    @Test
    fun anFtpUrlArrivesAsUnsupportedOperationException() {
        val url = "ftp://example.test/a.mp4"
        val cause: Throwable = causeOf(url)
        assertTrue(cause is UnsupportedOperationException, "$url: ${cause::class.java.name}")
        assertLandsInTheElseBranch(url, cause)
    }

    @Test
    fun aGopherUrlArrivesAsUnsupportedOperationException() {
        val url = "gopher:nothing"
        val cause: Throwable = causeOf(url)
        assertTrue(cause is UnsupportedOperationException, "$url: ${cause::class.java.name}")
        assertLandsInTheElseBranch(url, cause)
    }

    @Test
    fun anUnparseableUrlArrivesAsIllegalArgumentException() {
        for (url: String in listOf("not a url", "http://", "http://[::1")) {
            val cause: Throwable = causeOf(url)
            assertTrue(cause is IllegalArgumentException, "$url: ${cause::class.java.name}")
            assertLandsInTheElseBranch(url, cause)
        }
    }

    @Test
    fun aUrlWithoutASchemeArrivesAsIllegalArgumentException() {
        for (url: String in listOf("///", "a.mp4")) {
            val cause: Throwable = causeOf(url)
            assertTrue(cause is IllegalArgumentException, "$url: ${cause::class.java.name}")
            assertLandsInTheElseBranch(url, cause)
        }
    }

    private companion object {
        /** Hang bound of one `prepare`, in milliseconds. A rejected URL answers at once; this only ends a hang with a message. */
        const val HANG_MS: Long = 30_000L
    }
}
