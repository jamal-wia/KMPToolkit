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
 * D2 boss 3, R1 (ruling reviews/d2-boss3-ruling-opus.md, decision D-303): JavaFX decides loop-or-end in one FX task and posts the end
 * handler as a later one, so a `setLooping(true)` that lands between the two must not turn a real end into a loop restart. The model
 * puts the end of a cycle in the same two steps as JavaFX: the decoder delivers `onCycleDecision` (JavaFX's `currentCount` change, inside
 * the deciding task) and only then the `onEndOfMedia` task. A tap on the player's listener runs a hook right behind the decision,
 * where the engine's own `setLooping(true)` takes the FX queue's inline path (nothing is queued yet), as it does in the real
 * reproduction `JavaFxRepeatToggleAtEndD2Boss3Test`.
 *
 * Contract: core 03-guide "Looping" (`One` on a Completed player stays there until the next play), javafx 05 "Looping". Both decoder
 * variants of the model, speed 2 so that a wrap branch is visible as a native rate write of 1.0.
 */
class CycleDecisionD2Boss3FixModelTest {

    private class Completions : VideoPlaybackEngineListener {
        var completed: Int = 0
        override fun onCompleted() {
            completed++
        }

        override fun onFailed(cause: Throwable) = Unit
        override fun onBufferingChanged(isBuffering: Boolean) = Unit
        override fun onVideoSizeChanged(size: VideoSize?) = Unit
    }

    /** The player's listener with a hook behind `onCycleDecision`; the decision itself can be held back, as if the property never changed. */
    private class DecisionTap(private val real: NativeEvents) : NativeEvents by real {
        var swallow: Boolean = false
        var decisions: Int = 0
        var afterDecision: (() -> Unit)? = null

        override fun onCycleDecision() {
            decisions++
            if (!swallow) real.onCycleDecision()
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
        /** Runs the virtual clock in 5 ms steps until [done] holds, failing with [what] after [boundMs] of virtual time. */
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

    private fun atLoopRestart(engine: JavaFxVideoEngine): Boolean {
        val field: Field = FxPlayback::class.java.getDeclaredField("atLoopRestart").apply { isAccessible = true }
        return field.get(playbackOf(engine)) as Boolean
    }

    /** Plays from 2.3 s at speed 2 with the looping setting [looping] until the end of the first cycle is reached by the decoder. */
    private fun startNearTheEnd(ctx: Ctx, looping: Boolean) {
        ctx.engine.setLooping(looping)
        ctx.engine.setSpeed(2f)
        ctx.engine.seekTo(2_300L)
        ctx.engine.start()
        ctx.runUntil(MAX_RUN_MS, "the decoder never reached the end of the first cycle") { ctx.time.endsReached > 0 || ctx.tap.decisions > 0 }
    }

    private val Ctx.time: TimeModel get() = composed.time

    private fun settleAfterTheEnd(ctx: Ctx) = ctx.step(SETTLE_MS)

    private fun ratesWrittenAsOneAfter(ctx: Ctx, mark: Int): List<NativeCallEntry> =
        ctx.native.log.entries.drop(mark).filter { it.call == NativeCall.SET_RATE && it.arg == 1.0 }

    @Test
    fun aLoopingSwitchOnBetweenTheDecisionAndTheEndHandlerEndsInCompletedAndTheNextStartLoops() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                var mark = -1
                ctx.tap.afterDecision = {
                    if (mark < 0) {
                        mark = ctx.native.log.size
                        ctx.engine.setLooping(true)
                    }
                }
                startNearTheEnd(ctx, looping = false)
                settleAfterTheEnd(ctx)
                val label = "unity=$unity"
                assertTrue(mark >= 0, "$label the premise: the hook ran behind the decision")
                assertEquals(1, ctx.listener.completed, "$label the end that JavaFX decided was not reported as Completed once\n${ctx.native.log.tail(12)}")
                assertEquals(emptyList(), ratesWrittenAsOneAfter(ctx, mark), "$label the wrap branch ran (a native rate of 1.0 was written after the decision)\n${ctx.native.log.tail(12)}")
                assertFalse(atLoopRestart(ctx.engine), "$label the loop-restart state was set by an end JavaFX did not loop")
                assertEquals(0, ctx.native.log.entries.drop(mark).count { it.call == NativeCall.SEEK }, "$label a native seek followed the end\n${ctx.native.log.tail(12)}")

                val beforeReplay: Int = ctx.native.log.size
                ctx.engine.start()
                ctx.step(30L)
                val replay: List<NativeCallEntry> = ctx.native.log.entries.drop(beforeReplay)
                assertEquals(listOf(0.0), replay.filter { it.call == NativeCall.SEEK }.map { it.arg }, "$label the next start did not restart from the start with one seek: $replay")
                assertTrue(replay.any { it.call == NativeCall.PLAY }, "$label the next start issued no play: $replay")
                ctx.runUntil(MAX_RUN_MS, "$label the replay never wrapped") { ctx.composed.loop.cycle >= 2 }
                ctx.step(SETTLE_MS)
                assertEquals(1, ctx.listener.completed, "$label the replay completed again instead of looping")
                assertTrue(atLoopRestart(ctx.engine), "$label the replay did not take the loop branch at its end")
            }
        }
    }

    @Test
    fun aLoopingSwitchOffBetweenTheLoopDecisionAndTheEndHandlerReportsCompleted() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                ctx.engine.setLooping(true)
                ctx.engine.start()
                ctx.step(200L)
                ctx.native.let { native: ScriptedNativePlayer ->
                    // JavaFX decided to loop (looping was on at the decision) and has restarted the clip; the setting is switched off, and the
                    // end handler runs: the committed real pin of the same gap (JavaFxRepeatToggleAtEndD2Boss3Test) reports Completed.
                    onFxTask(ctx) { native.deliverCycleDecision() }
                    ctx.engine.setLooping(false)
                    ctx.step(1L)
                    onFxTask(ctx) { native.deliverEndOfMedia() }
                }
                ctx.step(SETTLE_MS)
                assertEquals(1, ctx.listener.completed, "unity=$unity the end handler of a loop that was switched off did not report Completed once\n${ctx.native.log.tail(12)}")
                assertFalse(atLoopRestart(ctx.engine), "unity=$unity the loop-restart state was set with looping off")
            }
        }
    }

    private fun onFxTask(ctx: Ctx, block: () -> Unit) {
        // The decoder keeps running meanwhile; the call is the one the FX thread would make.
        ctx.native.host.onFx(block)
    }

    @Test
    fun aNormalLoopStillLoopsAtEveryEnd() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                ctx.engine.setLooping(true)
                ctx.engine.setSpeed(2f)
                ctx.engine.seekTo(2_300L)
                ctx.engine.start()
                ctx.runUntil(MAX_RUN_MS, "unity=$unity the clip never wrapped") { ctx.composed.loop.cycle >= 2 }
                assertTrue(atLoopRestart(ctx.engine), "unity=$unity the loop branch did not run at the first wrap")
                ctx.runUntil(MAX_RUN_MS * 2, "unity=$unity the clip never wrapped a second time") { ctx.composed.loop.cycle >= 3 }
                ctx.step(SETTLE_MS)
                assertEquals(0, ctx.listener.completed, "unity=$unity a looping clip was reported Completed")
                assertTrue(ctx.tap.decisions >= 2, "unity=$unity the premise: a decision was delivered at every end, ${ctx.tap.decisions}")
                assertTrue(ctx.native.log.entries.any { it.call == NativeCall.SET_RATE && it.arg == 1.0 }, "unity=$unity the wrap gave no native rate of 1.0\n${ctx.native.log.tail(12)}")
            }
        }
    }

    @Test
    fun aNormalEndStillCompletesAndEveryReplayCompletesAgain() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                startNearTheEnd(ctx, looping = false)
                settleAfterTheEnd(ctx)
                assertEquals(1, ctx.listener.completed, "unity=$unity the end was not reported once\n${ctx.native.log.tail(12)}")
                assertFalse(atLoopRestart(ctx.engine), "unity=$unity the loop-restart state was set at a plain end")
                ctx.engine.seekTo(2_300L)
                ctx.step(60L)
                ctx.engine.start()
                ctx.runUntil(MAX_RUN_MS, "unity=$unity the replay never reached its end") { ctx.tap.decisions >= 2 }
                settleAfterTheEnd(ctx)
                assertEquals(2, ctx.listener.completed, "unity=$unity the replayed end was not reported\n${ctx.native.log.tail(12)}")
                assertFalse(atLoopRestart(ctx.engine), "unity=$unity the loop-restart state was set at a plain end")
            }
        }
    }

    @Test
    fun aMissedDecisionEventFallsBackToTheCurrentSettingAtTheEnd() {
        for (unity: Boolean in listOf(true, false)) {
            // looping on: the end handler loops, as it did before the decision event existed
            withEngine(unity) { ctx: Ctx ->
                ctx.tap.swallow = true
                ctx.engine.setLooping(true)
                ctx.engine.setSpeed(2f)
                ctx.engine.seekTo(2_300L)
                ctx.engine.start()
                ctx.runUntil(MAX_RUN_MS, "unity=$unity the clip never wrapped") { ctx.composed.loop.cycle >= 2 }
                ctx.step(SETTLE_MS)
                assertEquals(0, ctx.listener.completed, "unity=$unity with no decision event a looping clip was reported Completed")
                assertTrue(atLoopRestart(ctx.engine), "unity=$unity with no decision event the loop branch did not run")
            }
            // looping off: the end handler completes
            withEngine(unity) { ctx: Ctx ->
                ctx.tap.swallow = true
                startNearTheEnd(ctx, looping = false)
                settleAfterTheEnd(ctx)
                assertEquals(1, ctx.listener.completed, "unity=$unity with no decision event the end was not reported once")
            }
        }
    }

    @Test
    fun aRaceEndThenAReplayWithLoopingOnLoopsEvenIfTheNextDecisionEventIsMissed() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                var fired = false
                ctx.tap.afterDecision = {
                    if (!fired) {
                        fired = true
                        ctx.engine.setLooping(true)
                    }
                }
                startNearTheEnd(ctx, looping = false)
                settleAfterTheEnd(ctx)
                assertEquals(1, ctx.listener.completed, "unity=$unity the premise: the race end was reported as Completed\n${ctx.native.log.tail(12)}")
                // The snapshot of the race end (looping was off then) must not outlive its end handler: the replay's end sees no decision event.
                ctx.tap.swallow = true
                ctx.engine.start()
                ctx.runUntil(MAX_RUN_MS, "unity=$unity the replay never wrapped") { ctx.composed.loop.cycle >= 2 }
                ctx.step(SETTLE_MS)
                assertEquals(1, ctx.listener.completed, "unity=$unity the replay was reported Completed: a stale snapshot ended a loop\n${ctx.native.log.tail(12)}")
                assertTrue(atLoopRestart(ctx.engine), "unity=$unity the replay did not take the loop branch")
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
