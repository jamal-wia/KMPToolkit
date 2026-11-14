package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxPlayback
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import java.lang.reflect.Field
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D2 boss 4: the orderings around the end of a cycle that the boss-3 fix (`NativeEvents.onCycleDecision`, the snapshot of the
 * looping setting JavaFX's decision read) did not pin. JavaFX decides loop-or-end in one FX task and posts the end handler as a later
 * one; every test puts calls between the two and checks what the engine does with the end JavaFX made:
 * - two quick toggles in the gap follow the decision, not the toggles (an end stays an end, a loop stays a loop);
 * - a race end (looping switched on in the gap) never sets the loop-restart state, a loop wrap always does;
 * - the shared player's `RepeatMode.One` answer to a race end (`seekTo(0)` then `start()` from inside the completion, core
 *   `EngineVideoPlayer.completedLocked`) restarts the clip, which moves and loops at its next end, at speeds 1, 2 and 0.5;
 * - a pause or a release in the gap leaves nothing behind.
 *
 * Contract: core 03-guide "Looping" (`Completed` is never reached in `One`), javafx 05 "Looping" and "End of media". Both decoder
 * variants of the model.
 */
class CycleDecisionOrderingsD2Boss4ModelTest {

    private class Completions : VideoPlaybackEngineListener {
        var completed: Int = 0
        var onEachCompletion: (() -> Unit)? = null
        override fun onCompleted() {
            completed++
            onEachCompletion?.invoke()
        }

        override fun onFailed(cause: Throwable) = Unit
        override fun onBufferingChanged(isBuffering: Boolean) = Unit
        override fun onVideoSizeChanged(size: VideoSize?) = Unit
    }

    /** The player's listener with a hook right behind `onCycleDecision` (the gap before JavaFX's end handler). */
    private class DecisionTap(private val real: NativeEvents) : NativeEvents by real {
        var decisions: Int = 0
        var afterDecision: (() -> Unit)? = null

        override fun onCycleDecision() {
            decisions++
            real.onCycleDecision()
            afterDecision?.invoke()
        }
    }

    private class Ctx(
        val engine: JavaFxVideoEngine,
        val native: ScriptedNativePlayer,
        val composed: ComposedPlayer,
        val listener: Completions,
        val tap: DecisionTap,
        val step: (Long) -> Unit,
    ) {
        fun runUntil(boundMs: Long, what: String, done: () -> Boolean) {
            var elapsed = 0L
            while (!done()) {
                check(elapsed < boundMs) { "$what within $boundMs ms of virtual time\n${native.log.tail(12)}" }
                step(STEP_MS)
                elapsed += STEP_MS
            }
        }
    }

    private fun withEngine(unity: Boolean, body: (Ctx) -> Unit) {
        fxModel {
            val backend: ComposedBackend = composeAll(
                ScriptedBackend(host, clip = ClipModel(durationMs = CLIP_MS, keyFrameIntervalMs = 100.0), script = StatusScript()),
                timeScript = TimeScript(),
                hazardConfig = HazardConfig(decoderStartsAtUnity = unity),
            )
            val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
            val listener = Completions()
            engine.setListener(listener)
            load(engine, VideoSource.Asset(TestClip.ASSET))
            try {
                val composed: ComposedPlayer = backend.last
                val tap = DecisionTap(checkNotNull(composed.player.events) { "the player has no listener" })
                composed.player.setEvents(tap)
                body(Ctx(engine, composed.player, composed, listener, tap) { advanceBy(it) })
            } finally {
                engine.release()
                advanceBy(50)
            }
        }
    }

    private val sessionField: Field = JavaFxVideoEngine::class.java.getDeclaredField("session").apply { isAccessible = true }

    private fun playbackOf(engine: JavaFxVideoEngine): FxPlayback {
        val session: Any = checkNotNull(sessionField.get(engine)) { "no session after the load" }
        val field: Field = session.javaClass.getDeclaredField("playback").apply { isAccessible = true }
        return checkNotNull(field.get(session) as FxPlayback?) { "no playback after the load" }
    }

    private fun privateOf(engine: JavaFxVideoEngine, name: String): Any? {
        val field: Field = FxPlayback::class.java.getDeclaredField(name).apply { isAccessible = true }
        return field.get(playbackOf(engine))
    }

    private fun atLoopRestart(engine: JavaFxVideoEngine): Boolean = privateOf(engine, "atLoopRestart") as Boolean

    private fun startNearTheEnd(ctx: Ctx, looping: Boolean, speed: Float) {
        ctx.engine.setLooping(looping)
        ctx.engine.setSpeed(speed)
        ctx.engine.seekTo(if (speed < 1f) 2_700L else 2_300L)
        ctx.engine.start()
        ctx.runUntil(MAX_RUN_MS, "the decoder never reached the end of the first cycle") { ctx.tap.decisions > 0 }
    }

    @Test
    fun twoQuickTogglesInTheGapFollowTheDecisionAndNotTheToggles() {
        for (unity: Boolean in listOf(true, false)) {
            // An end decided with looping off: on then off in the gap is still an end.
            withEngine(unity) { ctx: Ctx ->
                var fired = false
                ctx.tap.afterDecision = {
                    if (!fired) {
                        fired = true
                        ctx.engine.setLooping(true)
                        ctx.engine.setLooping(false)
                    }
                }
                startNearTheEnd(ctx, looping = false, speed = 2f)
                ctx.step(SETTLE_MS)
                assertEquals(1, ctx.listener.completed, "unity=$unity on-off in the gap of an end: not Completed once\n${ctx.native.log.tail(12)}")
                assertFalse(atLoopRestart(ctx.engine), "unity=$unity on-off in the gap of an end set the loop-restart state")
                assertEquals(null, privateOf(ctx.engine, "loopingAtDecision"), "unity=$unity the snapshot outlived its end handler")
            }
            // A loop decided with looping on: off then on in the gap is still a loop.
            withEngine(unity) { ctx: Ctx ->
                var fired = false
                ctx.tap.afterDecision = {
                    if (!fired) {
                        fired = true
                        ctx.engine.setLooping(false)
                        ctx.engine.setLooping(true)
                    }
                }
                startNearTheEnd(ctx, looping = true, speed = 2f)
                ctx.runUntil(MAX_RUN_MS, "unity=$unity the clip never wrapped") { ctx.composed.loop.cycle >= 2 }
                ctx.step(SETTLE_MS)
                assertEquals(0, ctx.listener.completed, "unity=$unity off-on in the gap of a loop was reported Completed\n${ctx.native.log.tail(12)}")
                assertTrue(atLoopRestart(ctx.engine), "unity=$unity off-on in the gap of a loop did not take the loop branch")
                assertEquals(null, privateOf(ctx.engine, "loopingAtDecision"), "unity=$unity the snapshot outlived its end handler")
            }
        }
    }

    @Test
    fun theCoreRestartOfARaceEndMovesAndLoopsAtEverySpeed() {
        for (unity: Boolean in listOf(true, false)) {
            for (speed: Float in listOf(1f, 2f, 0.5f)) {
                withEngine(unity) { ctx: Ctx ->
                    val label = "unity=$unity speed=$speed"
                    var fired = false
                    ctx.tap.afterDecision = {
                        if (!fired) {
                            fired = true
                            ctx.engine.setLooping(true)
                        }
                    }
                    // What the shared player does with a completion in RepeatMode.One while Playing: seekTo(0), then start(), from the callback.
                    var restarts = 0
                    ctx.listener.onEachCompletion = {
                        restarts++
                        ctx.engine.seekTo(0L)
                        ctx.engine.start()
                    }
                    startNearTheEnd(ctx, looping = false, speed = speed)
                    ctx.runUntil(MAX_RUN_MS, "$label the race end was never reported") { ctx.listener.completed > 0 }
                    assertFalse(atLoopRestart(ctx.engine), "$label a race end set the loop-restart state")
                    ctx.runUntil(MAX_RUN_MS, "$label the restarted clip never moved past 600 ms") { ctx.engine.positionMs() > 600L }
                    assertFalse(ctx.composed.time.isHeld, "$label the decoder holds its playhead after the restart\n${ctx.native.log.tail(12)}")
                    ctx.runUntil(MAX_RUN_MS * 3, "$label the restarted clip never wrapped") { ctx.composed.loop.cycle >= 2 }
                    ctx.step(SETTLE_MS)
                    assertEquals(1, restarts, "$label the restarted clip was reported Completed again instead of looping\n${ctx.native.log.tail(12)}")
                    assertTrue(atLoopRestart(ctx.engine), "$label the wrap after the restart did not take the loop branch")
                    assertFalse(ctx.composed.time.isHeld, "$label the decoder holds its playhead after the wrap\n${ctx.native.log.tail(12)}")
                }
            }
        }
    }

    /**
     * javafx 05 "End of media": the engine pauses JavaFX at an end it reports, so a seek from `Completed` stays paused in JavaFX too. A race
     * end (looping switched on in the gap) is reported as an end while the looping setting reads on: it must pause JavaFX all the same.
     */
    @Test
    fun aRaceEndPausesJavaFxLikeAnyOtherEnd() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                var mark = -1
                ctx.tap.afterDecision = {
                    if (mark < 0) {
                        mark = ctx.native.log.size
                        ctx.engine.setLooping(true)
                    }
                }
                startNearTheEnd(ctx, looping = false, speed = 1f)
                ctx.step(SETTLE_MS)
                assertEquals(1, ctx.listener.completed, "unity=$unity the premise: the race end was reported once\n${ctx.native.log.tail(12)}")
                assertTrue(
                    ctx.native.log.entries.drop(mark).any { it.call == NativeCall.PAUSE },
                    "unity=$unity the race end was reported as Completed but JavaFX was never paused\n${ctx.native.log.tail(12)}",
                )
            }
        }
    }

    @Test
    fun aPauseInTheGapOfARaceEndReportsTheEndAndLeavesNoRestartState() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                var fired = false
                ctx.tap.afterDecision = {
                    if (!fired) {
                        fired = true
                        ctx.engine.setLooping(true)
                        ctx.engine.pause()
                    }
                }
                startNearTheEnd(ctx, looping = false, speed = 2f)
                ctx.step(SETTLE_MS)
                assertEquals(1, ctx.listener.completed, "unity=$unity the end was not reported once\n${ctx.native.log.tail(12)}")
                assertFalse(atLoopRestart(ctx.engine), "unity=$unity a race end with a pause in the gap set the loop-restart state")
                // The next play starts over (the end stands) and loops at the next end.
                val mark: Int = ctx.native.log.size
                ctx.engine.start()
                ctx.step(30L)
                assertEquals(listOf(0.0), ctx.native.log.entries.drop(mark).filter { it.call == NativeCall.SEEK }.map { it.arg }, "unity=$unity the play after the end did not start over")
                ctx.runUntil(MAX_RUN_MS * 2, "unity=$unity the replay never wrapped") { ctx.composed.loop.cycle >= 2 }
                ctx.step(SETTLE_MS)
                assertEquals(1, ctx.listener.completed, "unity=$unity the replay was reported Completed instead of looping")
            }
        }
    }

    @Test
    fun aReleaseInTheGapReportsNothingAndKeepsNoSnapshot() {
        for (unity: Boolean in listOf(true, false)) {
            for (loopingBefore: Boolean in listOf(false, true)) {
                withEngine(unity) { ctx: Ctx ->
                    var fired = false
                    ctx.tap.afterDecision = {
                        if (!fired) {
                            fired = true
                            ctx.engine.setLooping(!loopingBefore)
                            ctx.engine.release()
                        }
                    }
                    startNearTheEnd(ctx, looping = loopingBefore, speed = 1f)
                    ctx.step(SETTLE_MS)
                    assertEquals(0, ctx.listener.completed, "unity=$unity looping=$loopingBefore an end was reported after the release\n${ctx.native.log.tail(12)}")
                    assertTrue(fired, "unity=$unity the premise: the release ran in the gap")
                }
            }
        }
    }

    private companion object {
        const val CLIP_MS: Double = 3_000.0
        const val STEP_MS: Long = 5L
        const val MAX_RUN_MS: Long = 3_000L
        const val SETTLE_MS: Long = 500L
    }
}
