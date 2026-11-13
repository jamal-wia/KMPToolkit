package io.github.jamal_wia.kmptoolkit.video.player

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.rate
import platform.AVFoundation.volume
import platform.Foundation.NSBundle

/**
 * D1 round 8: the iOS player against the documented contract of 05-platform-notes § iOS and the
 * sentences of 03-guide and 04-api-reference that a real `AVPlayer` has to honour, through the public
 * `createVideoPlayer()` path: the audio session (process-wide, so each test sets the category it
 * needs first and puts it back), the shape and identity of a load failure, a playhead that never
 * passes the duration, and a released player that reaches nothing on the platform.
 *
 * Every body runs inside `runOnMainLoop`, which has a hard 20 s bound and fails with its own message,
 * and every player is released on the exit path of its test.
 */
@OptIn(ToolkitInternalApi::class, ExperimentalForeignApi::class)
class IosEngineContractR8AdversarialTest {

    private val movie: String by lazy { writeTestMovie("kmptoolkit-video-player-ios-r8-test") }

    private fun newPlayer(managesAudioSession: Boolean = false): VideoPlayer = createVideoPlayer(
        config = VideoPlayerConfig(positionUpdateIntervalMs = FAST_POSITION_UPDATES_MS),
        managesAudioSession = managesAudioSession,
    )

    private fun VideoPlayer.avPlayer(): AVPlayer = assertNotNull(avPlayerOrNull(), "no AVPlayer behind the player")

    /** Runs [body] with a player and releases it on every exit path. */
    private fun withPlayer(managesAudioSession: Boolean = false, body: suspend (VideoPlayer) -> Unit) {
        val player: VideoPlayer = newPlayer(managesAudioSession)
        try {
            runOnMainLoop { body(player) }
        } finally {
            player.release()
        }
    }

    // --- The audio session (05-platform-notes: "The audio session") ----------------------------

    @Test
    fun `a player that manages the audio session switches it to Playback with the MoviePlayback mode on load`() {
        val path: String = movie
        val session: AVAudioSession = AVAudioSession.sharedInstance()
        session.resetToAmbient()
        try {
            assertEquals(AVAudioSessionCategoryAmbient, session.category, "premise: the test could not set Ambient")
            withPlayer(managesAudioSession = true) { player: VideoPlayer ->
                player.prepare(VideoSource.File(path))

                assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
                assertEquals(AVAudioSessionCategoryPlayback, session.category)
                assertEquals(AVAudioSessionModeMoviePlayback, session.mode)
            }
            // Documented: the session is left as it is after release().
            assertEquals(AVAudioSessionCategoryPlayback, session.category, "release() must not undo the category")
        } finally {
            session.resetToAmbient()
            session.setActive(false, error = null)
        }
    }

    @Test
    fun `a player that does not manage the audio session leaves it exactly as the app set it`() {
        val path: String = movie
        val session: AVAudioSession = AVAudioSession.sharedInstance()
        session.resetToAmbient()
        try {
            assertEquals(AVAudioSessionCategoryAmbient, session.category, "premise: the test could not set Ambient")
            assertNotEquals(AVAudioSessionModeMoviePlayback, session.mode, "premise: the mode is already MoviePlayback")
            withPlayer(managesAudioSession = false) { player: VideoPlayer ->
                player.prepare(VideoSource.File(path))
                player.play()
                delay(100)

                assertEquals(AVAudioSessionCategoryAmbient, session.category)
                assertEquals(AVAudioSessionModeDefault, session.mode)
            }
        } finally {
            session.resetToAmbient()
        }
    }

    // --- Load failures through the public path (05-platform-notes: "Errors") -------------------

    @Test
    fun `a failed AVPlayerItem arrives as the engine own IllegalStateException with domain code and description`() {
        withPlayer { player: VideoPlayer ->
            player.prepare(VideoSource.File("$MISSING_DIRECTORY/r8-no-such-movie.mp4"))

            val error: VideoPlayerState.Error = assertIs(player.stateFlow.value)
            val cause: Throwable = error.cause
            assertIs<IllegalStateException>(cause)
            assertTrue(
                ITEM_FAILURE_SHAPE.matches(cause.message.orEmpty()),
                "message does not carry the NSError domain, code and description: ${cause.message}",
            )
            // A stack-trace-recovered copy would carry the original as its cause: none exists here.
            assertNull(cause.cause, "the Throwable is a recovered copy of another one")
        }
    }

    @Test
    fun `a file that is not media arrives with the same shape and no recovered copy`() {
        withPlayer { player: VideoPlayer ->
            val notMedia: String = NSBundle.mainBundle.executablePath ?: error("no executable path")
            player.prepare(VideoSource.File(notMedia))

            val cause: Throwable = assertIs<VideoPlayerState.Error>(player.stateFlow.value).cause
            assertIs<IllegalStateException>(cause)
            assertTrue(ITEM_FAILURE_SHAPE.matches(cause.message.orEmpty()), "message: ${cause.message}")
            assertNull(cause.cause)
        }
    }

    @Test
    fun `an asset missing from the bundle a blank file path and a refused URL arrive as IllegalArgumentException`() {
        withPlayer { player: VideoPlayer ->
            val missing: Throwable = prepareAndGetCause(player, VideoSource.Asset("r8-not-bundled.mp4"))
            assertIs<IllegalArgumentException>(missing)
            assertTrue(missing.message.orEmpty().contains("r8-not-bundled.mp4"), "message: ${missing.message}")
            assertNull(missing.cause)

            val blank: Throwable = prepareAndGetCause(player, VideoSource.File("   "))
            assertIs<IllegalArgumentException>(blank)
            assertNull(blank.cause)

            val refused: Throwable = prepareAndGetCause(player, VideoSource.Remote("not a url"))
            assertIs<IllegalArgumentException>(refused)
            assertNull(refused.cause)

            assertNull(player.avPlayer().currentItem, "a refused source left an item behind")
        }
    }

    @Test
    fun `a failed load keeps every setting and the next load plays with them`() {
        val path: String = movie
        withPlayer { player: VideoPlayer ->
            player.setVolume(0.4f)
            player.setMuted(false)
            player.setPlaybackSpeed(2f)
            player.prepare(VideoSource.File("$MISSING_DIRECTORY/r8-missing-again.mp4"))
            assertIs<VideoPlayerState.Error>(player.stateFlow.value)
            assertEquals(0.4f, player.volumeFlow.value)
            assertEquals(2f, player.playbackSpeedFlow.value)

            player.prepare(VideoSource.File(path))
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            assertEquals(0.4f, player.avPlayer().volume, "the volume was not pushed onto the new source")
            assertEquals(0f, player.avPlayer().rate, "a speed must not start a paused player")
        }
    }

    // --- The playhead never passes the duration (the iOS twin of R6-O1) ------------------------

    @Test
    fun `a seek beyond the end lands on the duration and the playhead never passes it`() {
        val path: String = movie
        withPlayer { player: VideoPlayer ->
            player.prepare(VideoSource.File(path))
            val duration: Long = assertIs<VideoPlayerState.Ready>(player.stateFlow.value).duration

            player.seekTo(Long.MAX_VALUE)
            assertEquals(duration, player.playbackPositionFlow.value, "the target is clamped to the duration at once")
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value, "a seek in Ready leaves it Ready")
            assertNeverAbove(player, duration, observeMs = 400L)

            player.seekTo(-1L)
            assertEquals(0L, player.playbackPositionFlow.value, "a negative target goes to 0")
        }
    }

    @Test
    fun `playing to the end and a seek forward from near the end never publish a position above the duration`() {
        val path: String = movie
        withPlayer { player: VideoPlayer ->
            player.prepare(VideoSource.File(path))
            val duration: Long = assertIs<VideoPlayerState.Ready>(player.stateFlow.value).duration
            val positions: MutableList<Long> = mutableListOf()

            player.seekTo(duration - 200L)
            player.play()
            val completed: Boolean = awaitCondition(3_000) {
                positions += player.playbackPositionFlow.value
                player.stateFlow.value is VideoPlayerState.Completed
            }
            assertTrue(completed, "never completed: ${player.stateFlow.value}")
            assertTrue(positions.all { position: Long -> position <= duration }, "positions above $duration: $positions")

            player.seekForward(Long.MAX_VALUE)
            assertEquals(duration, player.playbackPositionFlow.value, "seekForward saturates at the duration")
            assertNeverAbove(player, duration, observeMs = 300L)
        }
    }

    // --- A released player reaches nothing on the platform (03-guide: "The release contract") --

    @Test
    fun `release is idempotent keeps the one AVPlayer emptied and a later prepare reports instead of loading`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        var first: AVPlayer? = null
        try {
            runOnMainLoop {
                first = player.avPlayer()
                player.prepare(VideoSource.File(path))
                player.play()
                delay(100)

                player.release()
                player.release()
                player.close()

                assertSame(first, player.avPlayer(), "the same AVPlayer survives the release")
                assertNull(player.avPlayer().currentItem)
                assertEquals(0f, player.avPlayer().rate)
                assertEquals(VideoPlayerState.Idle, player.stateFlow.value)

                player.prepare(VideoSource.File(path))
                val error: VideoPlayerState.Error = assertIs(player.stateFlow.value)
                assertIs<VideoPlayerReleasedException>(error.cause)
                assertNull(player.avPlayer().currentItem, "a prepare after release loaded an item")
            }
        } finally {
            player.release()
        }
    }

    @Test
    fun `settings after release update the flows and reach neither the AVPlayer nor its rate`() {
        val path: String = movie
        withPlayer { player: VideoPlayer ->
            player.prepare(VideoSource.File(path))
            val avPlayer: AVPlayer = player.avPlayer()
            player.release()
            val volumeBefore: Float = avPlayer.volume

            player.setVolume(0.1f)
            player.setMuted(true)
            player.setPlaybackSpeed(3f)
            player.setRepeatMode(RepeatMode.One)
            player.play()
            player.seekTo(500L)
            delay(100)

            assertEquals(0.1f, player.volumeFlow.value)
            assertEquals(3f, player.playbackSpeedFlow.value)
            assertEquals(volumeBefore, avPlayer.volume, "a setting reached the platform after release")
            assertEquals(0f, avPlayer.rate)
            assertNull(avPlayer.currentItem)
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        }
    }

    @Test
    fun `unload twice leaves the AVPlayer empty and the player usable`() {
        val path: String = movie
        withPlayer { player: VideoPlayer ->
            player.prepare(VideoSource.File(path))
            player.play()
            player.unload()
            player.unload()

            assertNull(player.avPlayer().currentItem)
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertNull(player.videoSizeFlow.value)
            assertEquals(0L, player.bufferedPositionFlow.value)

            player.prepare(VideoSource.File(path))
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
        }
    }

    /** The session the app owns in these tests: Ambient with the default mode, whatever an earlier test left. */
    private fun AVAudioSession.resetToAmbient() {
        setCategory(
            category = AVAudioSessionCategoryAmbient,
            mode = AVAudioSessionModeDefault,
            options = 0uL,
            error = null,
        )
    }

    private suspend fun prepareAndGetCause(player: VideoPlayer, source: VideoSource): Throwable {
        player.prepare(source)
        return assertIs<VideoPlayerState.Error>(player.stateFlow.value).cause
    }

    /** Watches the playhead for [observeMs] and fails if it is ever above [duration]. */
    private suspend fun assertNeverAbove(player: VideoPlayer, duration: Long, observeMs: Long) {
        var waitedMs = 0L
        while (waitedMs < observeMs) {
            val position: Long = player.playbackPositionFlow.value
            assertTrue(position <= duration, "position $position above the duration $duration")
            delay(OBSERVE_STEP_MS)
            waitedMs += OBSERVE_STEP_MS
        }
    }

    private companion object {
        const val FAST_POSITION_UPDATES_MS: Long = 50L
        const val OBSERVE_STEP_MS: Long = 20L

        /** A directory that does not exist: no file is created or listed anywhere. */
        const val MISSING_DIRECTORY: String = "/kmptoolkit-r8-no-such-directory"

        /** `AVPlayerItem failed (<domain> <code>): <localizedDescription>` (05-platform-notes, Errors). */
        val ITEM_FAILURE_SHAPE: Regex = Regex("""AVPlayerItem failed \(\S+ -?\d+\): .+""")
    }
}
