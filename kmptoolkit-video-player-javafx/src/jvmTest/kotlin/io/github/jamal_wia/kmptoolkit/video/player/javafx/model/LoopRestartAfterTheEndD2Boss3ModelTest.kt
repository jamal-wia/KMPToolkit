package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 boss 3 (killer of mutant P2, the open LOW-2 of reviews/d2-boss2-fix-opus.md): the restart seek of a `start()` after the end
 * clears the loop-restart state. A looping clip at 2x wraps (the state is set), looping is switched off, the clip plays to its end,
 * `play()` restarts it from the start (the engine's own native seek), and the player is paused again while the playhead is still
 * near the start: the next resume takes the seek path (one seek to where the playhead stands), as any near-start resume with no loop
 * restart behind it does (05 "Playback speed"). With the clear removed the stale state sends it down the settle path (no seek).
 * Both decoder variants of the model.
 */
class LoopRestartAfterTheEndD2Boss3ModelTest {

    private class Ctx(val engine: JavaFxVideoEngine, val native: ScriptedNativePlayer, val composed: ComposedPlayer, val step: (Long) -> Unit)

    /** What one observation saw from a marked call index on. */
    private class Seen(val label: String, val calls: List<NativeCallEntry>, val violations: List<String>, val frozen: Boolean, val furthestNativeMs: Long) {
        val seeks: List<NativeCallEntry> get() = calls.filter { it.call == NativeCall.SEEK }
        val plays: List<NativeCallEntry> get() = calls.filter { it.call == NativeCall.PLAY }
        val rateWrites: List<NativeCallEntry> get() = calls.filter { it.call == NativeCall.SET_RATE }

        /** How long after the first play the speed 2.0 was written, or null when it never was. */
        val speedGivenAfterPlayMs: Double?
            get() {
                val speed: NativeCallEntry? = rateWrites.firstOrNull { it.arg == 2.0 }
                return if (speed != null && plays.isNotEmpty()) speed.tMs - plays.first().tMs else null
            }

        override fun toString(): String =
            "$label seeks=${seeks.size} plays=${plays.size} rates=${rateWrites.map { it.arg }} speedGivenAfterPlay=${speedGivenAfterPlayMs}ms " +
                "frozen=$frozen violations=$violations furthest=${furthestNativeMs}ms\n" + calls.joinToString("\n") { "    $it" }
    }

    private fun withEngine(unity: Boolean, body: (Ctx) -> Unit) {
        fxModel {
            val backend: ComposedBackend = composeAll(
                ScriptedBackend(host, clip = ClipModel(durationMs = CLIP_MS, keyFrameIntervalMs = 100.0), script = StatusScript()),
                timeScript = TimeScript(),
                hazardConfig = HazardConfig(decoderStartsAtUnity = unity),
            )
            val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
            load(engine, VideoSource.Asset(TestClip.ASSET))
            try {
                val composed: ComposedPlayer = backend.last
                body(Ctx(engine, composed.player, composed) { advanceBy(it) })
            } finally {
                engine.release()
                advanceBy(50)
            }
        }
    }

    /** A looping clip at 2x, started at 2.3 s, run until the loop model's cycle count says it wrapped. */
    private fun runToTheWrap(ctx: Ctx) {
        ctx.engine.setLooping(true)
        ctx.engine.setSpeed(2f)
        ctx.engine.seekTo(2_300L)
        ctx.engine.start()
        var guard = 0
        while (ctx.engine.positionMs() < 2_600L) {
            check(guard++ < MAX_STEPS) { "the clip never passed 2600 ms" }
            ctx.step(1L)
        }
        while (ctx.composed.loop.cycle < 2) {
            check(guard++ < MAX_STEPS) { "the clip never wrapped" }
            ctx.step(1L)
        }
    }

    private fun observe(ctx: Ctx, label: String, fromCall: Int, forMs: Long): Seen {
        var elapsed = 0L
        var furthest = 0L
        while (elapsed < forMs) {
            ctx.step(TRACE_STEP_MS)
            elapsed += TRACE_STEP_MS
            furthest = maxOf(furthest, ctx.native.positionMs.toLong())
        }
        return Seen(
            label = label,
            calls = ctx.native.log.entries.drop(fromCall).filter { it.call != NativeCall.SNAPSHOT },
            violations = ctx.composed.monitor.violations.map { it.describe() },
            frozen = ctx.composed.hazards.frozen,
            furthestNativeMs = furthest,
        )
    }

    @Test
    fun aResumeNearTheStartAfterAPlayFromTheEndTakesTheSeekPathEvenIfTheLoopHadWrappedBefore() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                runToTheWrap(ctx)
                ctx.engine.setLooping(false)
                var guard = 0
                while (ctx.engine.positionMs() >= 1_000L) {
                    check(guard++ < MAX_STEPS) { "the published time never showed the new cycle" }
                    ctx.step(1L)
                }
                while (ctx.engine.positionMs() < 2_900L) {
                    check(guard++ < MAX_STEPS) { "the clip never reached its end after looping was switched off" }
                    ctx.step(1L)
                }
                ctx.step(1_000L)
                val beforePlay: Int = ctx.native.log.size
                ctx.engine.start()
                ctx.step(30L)
                val first: Seen = observe(ctx, "unity=$unity play from the end", beforePlay, 0L)
                ctx.engine.pause()
                ctx.step(60L)
                val atResume: Double = ctx.native.positionMs
                val beforeResume: Int = ctx.native.log.size
                ctx.engine.start()
                val second: Seen = observe(ctx, "unity=$unity resume atResume=${atResume}ms", beforeResume, OBSERVE_MS)
                println("BOSS3-P2 $first\n$second")
                assertEquals(1, first.seeks.size, "the play from the end did not restart with one seek of its own: $first")
                assertTrue(atResume <= NEAR_START_MS, "the premise: the playhead stands near the start at the resume: $second")
                assertEquals(1, second.seeks.size, "the resume near the start after the play from the end did not take the seek path: $second")
                assertEquals(1, second.plays.size, "the resume did not issue exactly one play: $second")
                assertEquals(emptyList(), second.violations, "a fragile window was violated: $second")
                assertTrue(!second.frozen, "the playhead froze: $second")
            }
        }
    }

    /**
     * Killer of mutant P14: a loop wrap whose end handler runs after a `pause()` (JavaFX delivered the wrap after the pause, the
     * native playhead already back at the start) still sets the loop-restart state, so a start 1 s later takes the settle path
     * (no seek of its own) like a pause after the wrap does. The pause is made from a watcher of the loop model, which runs at the
     * wrap before `onEndOfMedia` is delivered.
     */
    @Test
    fun aWrapDeliveredAfterAPauseStillSendsTheNextResumeDownTheSettlePath() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                var paused = false
                ctx.composed.loop.onWrap { if (!paused) { paused = true; ctx.engine.pause() } }
                runToTheWrap(ctx)
                ctx.step(1_000L)
                val atResume: Double = ctx.native.positionMs
                val before: Int = ctx.native.log.size
                ctx.engine.start()
                val seen: Seen = observe(ctx, "unity=$unity resume after a wrap delivered after the pause atResume=${atResume}ms", before, OBSERVE_MS)
                println("BOSS3-P14 $seen")
                assertTrue(paused, "the premise: the watcher paused at the wrap")
                assertTrue(atResume <= NEAR_START_MS, "the premise: the native playhead stands near the start: $seen")
                assertEquals(0, seen.seeks.size, "the resume took the seek path from a loop restart: $seen")
                assertEquals(1, seen.plays.size, "the resume did not issue exactly one play: $seen")
                assertEquals(emptyList(), seen.violations, "a fragile window was violated: $seen")
                assertTrue(!seen.frozen, "the playhead froze: $seen")
            }
        }
    }

    private companion object {
        const val CLIP_MS: Double = 3_000.0
        const val OBSERVE_MS: Long = 1_200L
        const val MAX_STEPS: Int = 100_000
        const val TRACE_STEP_MS: Long = 100L
        const val NEAR_START_MS: Double = 100.0
    }
}
