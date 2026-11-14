package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D2 boss 2, W1 (found red on c16a102b, fixed by the state flag atLoopRestart, ruling reviews/d2-boss2-ruling-opus.md): 05
 * "Playback speed" says that a resume of a looping clip from the point where its loop restarted, with no seek since, plays with no
 * native seek of its own, "including a speed that arrives within about 600 ms of a plain `play()`", and that the near-start seek is
 * used for "the first play, a replay", not for such a resume. The B6 fix put that into start() only: setSpeed's FRESH branch (a speed
 * right after a plain play near the start, while the status is not yet PLAYING) did not ask and issued SEEK, PLAY, the
 * away-and-back, 300 to 500 ms after JavaFX's own restart seek (inside setSpeed's own 300 ms seek window the speed is held
 * instead, so the FRESH seek landed only in the second part of the first half second after the wrap). The branch now skips its seek
 * while the player stands where the loop restarted.
 *
 * Consumer shape: a looping clip at 1x paused right at the wrap, the speed changed to 2x while paused, play pressed about a
 * third of a second after the wrap: the shared player calls start() and then gives the speed.
 */
class FreshSpeedAfterWrapBoss2W1ModelTest {

    @Test
    fun aSpeedRightAfterAPlainResumeWhileThePlayerStandsWhereTheLoopRestartedIsGivenWithoutANativeSeek() {
        for (unity: Boolean in listOf(true, false)) {
            for (gapMs: Long in listOf(310L, 360L, 450L)) {
                fxModel {
                    val backend: ComposedBackend = composeAll(
                        ScriptedBackend(host, clip = ClipModel(durationMs = 3_000.0, keyFrameIntervalMs = 100.0), script = StatusScript()),
                        timeScript = TimeScript(),
                        hazardConfig = HazardConfig(decoderStartsAtUnity = unity),
                    )
                    val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
                    load(engine, VideoSource.Asset(TestClip.ASSET))
                    try {
                        val composed: ComposedPlayer = backend.last
                        val native: ScriptedNativePlayer = composed.player
                        engine.setLooping(true)
                        engine.seekTo(2_300L)
                        engine.start()
                        var guard = 0
                        while (composed.loop.cycle < 2) {
                            check(guard++ < 100_000) { "the clip never wrapped" }
                            advanceBy(1L)
                        }
                        engine.pause()
                        advanceBy(gapMs)
                        val before: Int = native.log.size
                        engine.start()
                        engine.setSpeed(2f)
                        advanceBy(1_000L)
                        val after: List<NativeCallEntry> = native.log.entries.drop(before).filter { it.call != NativeCall.SNAPSHOT }
                        assertEquals(
                            emptyList(),
                            after.filter { it.call == NativeCall.SEEK }.map { it.toString() },
                            "unity=$unity gap=${gapMs}ms: a native seek while the player stands where the loop restarted:\n" +
                                after.joinToString("\n") { "    $it" },
                        )
                    } finally {
                        engine.release()
                        advanceBy(50)
                    }
                }
            }
        }
    }
}
