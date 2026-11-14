package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 boss 2, W2 and W1 (ruling reviews/d2-boss2-ruling-opus.md): the settle path of a resume from a loop restart is keyed on
 * STATE, not on a clock. A looping clip at a speed other than 1 that is paused where its loop restarted (the native playhead
 * stands at the start JavaFX's own restart seek put it at) resumes with a plain play, no native seek of its own, and the speed
 * given after 200 ms of real movement, however long the pause lasted. On the real macOS decoder the seek path from that state
 * left the player PLAYING with the playhead standing at 0 in about one resume in ten, 0.6 s and 3 s after the wrap alike
 * (adversarial/D2/boss-2-w2-ab.md: the engine as it was 5 of 60, 5 of 30 and 6 of 40 loaded; the state flag 0 of 60 at every gap
 * and 0 of 40 loaded).
 *
 * The seek path stays for every other near-start resume: the first play, a replay after a caller's seek, a play after the end, and
 * a resume near the start after a caller's seek made since the wrap (that seek moved the playhead on its own, so nothing is left
 * of the restart state). Both decoder variants of the model are run: one that starts every play at normal speed (macOS) and one
 * that honours the rate property (the assumed Windows and Linux behaviour).
 */
class LoopRestartStateD2Boss2FixModelTest {

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

    /**
     * W2: paused where the loop restarted, a start after 0.55 s, 1.5 s or 3 s (all past the old 500 ms window) takes the settle path:
     * no native seek, one play, the rate put back to 1.0 and the speed given after the playhead has really moved 200 ms, no
     * hazard window violated, the playhead not frozen and moving on.
     */
    @Test
    fun aStartAfterAPauseAtTheLoopRestartTakesTheSettlePathHoweverLongTheGapWas() {
        for (unity: Boolean in listOf(true, false)) {
            for (gapMs: Long in listOf(550L, 1_500L, 3_000L)) {
                withEngine(unity) { ctx: Ctx ->
                    runToTheWrap(ctx)
                    ctx.engine.pause()
                    val nativeAtPause: Double = ctx.native.positionMs
                    ctx.step(gapMs)
                    val before: Int = ctx.native.log.size
                    ctx.engine.start()
                    val seen: Seen = observe(ctx, "unity=$unity gap=${gapMs}ms nativeAtPause=${nativeAtPause}ms", before, OBSERVE_MS)
                    println("BOSS2-FIX-SETTLE $seen")
                    assertTrue(nativeAtPause <= NEAR_START_MS, "the premise: the native playhead stands near 0 at the pause: $seen")
                    assertEquals(0, seen.seeks.size, "the start took the seek path: $seen")
                    assertEquals(1, seen.plays.size, "the start did not issue exactly one play: $seen")
                    val speedAfter: Double? = seen.speedGivenAfterPlayMs
                    assertTrue(speedAfter != null, "the speed was never given: $seen")
                    assertTrue(speedAfter >= SETTLE_MS, "the speed was given before the playhead had moved 200 ms: $seen")
                    assertTrue(speedAfter <= SETTLE_LATEST_MS, "the speed was given far too late: $seen")
                    assertEquals(listOf(1.0, 2.0), seen.rateWrites.map { it.arg }, "the speed was not given as one away-and-back: $seen")
                    assertTrue(seen.rateWrites.last().positionMs >= SETTLE_MS, "the speed was written before the native playhead had moved 200 ms: $seen")
                    assertEquals(emptyList(), seen.violations, "a fragile window was violated: $seen")
                    assertTrue(!seen.frozen, "the playhead froze: $seen")
                    assertTrue(seen.furthestNativeMs > MOVED_MS, "the playhead did not move on after the start: $seen")
                }
            }
        }
    }

    /**
     * A caller's seekTo(0) after a wrap clears the restart state: that seek, then a start, plays; the player is paused again
     * while its playhead is still near the start and started once more, and that second resume takes the seek path (one seek
     * of its own to where the playhead stands), not the settle path.
     */
    @Test
    fun aCallersSeekToTheStartAfterAWrapTakesTheSeekPathOnTheNextResumeNearTheStart() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                runToTheWrap(ctx)
                ctx.engine.pause()
                ctx.step(1_000L)
                val beforeSeek: Int = ctx.native.log.size
                ctx.engine.seekTo(0L)
                ctx.engine.start()
                ctx.step(30L)
                val first: Seen = observe(ctx, "unity=$unity first start after the seek", beforeSeek, 0L)
                ctx.engine.pause()
                ctx.step(60L)
                val atResume: Double = ctx.native.positionMs
                val beforeResume: Int = ctx.native.log.size
                ctx.engine.start()
                val second: Seen = observe(ctx, "unity=$unity second start atResume=${atResume}ms", beforeResume, OBSERVE_MS)
                println("BOSS2-FIX-CALLERSEEK $first\n$second")
                assertEquals(1, first.seeks.size, "the caller's seek was not the one and only seek of the first start: $first")
                assertTrue(atResume <= NEAR_START_MS, "the premise: the playhead stands near the start at the second resume: $second")
                assertEquals(1, second.seeks.size, "the resume near the start after the caller's seek did not take the seek path: $second")
                assertEquals(1, second.plays.size, "the second start did not issue exactly one play: $second")
                assertEquals(emptyList(), second.violations, "a fragile window was violated: $second")
                assertTrue(!second.frozen, "the playhead froze: $second")
            }
        }
    }

    /**
     * No wrap, no restart state: the first play of a clip at a speed other than 1 takes the seek path (one seek to where the
     * playhead stands, a play, the speed given right after the play, long before 200 ms of movement), and so does a replay
     * after a caller's seekTo(0) from the middle of the clip.
     */
    @Test
    fun aFirstPlayAndAReplayNearTheStartTakeTheSeekPath() {
        for (unity: Boolean in listOf(true, false)) {
            withEngine(unity) { ctx: Ctx ->
                ctx.engine.setSpeed(2f)
                val beforeFirst: Int = ctx.native.log.size
                ctx.engine.start()
                val first: Seen = observe(ctx, "unity=$unity first play", beforeFirst, OBSERVE_MS)
                println("BOSS2-FIX-FIRSTPLAY $first")
                assertEquals(1, first.seeks.size, "the first play did not take the seek path: $first")
                assertEquals(1, first.plays.size, "the first play did not issue exactly one play: $first")
                val firstSpeedAfter: Double? = first.speedGivenAfterPlayMs
                assertTrue(firstSpeedAfter != null && firstSpeedAfter < SETTLE_MS, "the first play's speed was not given right after the play: $first")
                assertEquals(emptyList(), first.violations, "a fragile window was violated: $first")
                ctx.engine.seekTo(1_500L)
                ctx.step(300L)
                ctx.engine.pause()
                ctx.step(100L)
                val beforeReplay: Int = ctx.native.log.size
                ctx.engine.seekTo(0L)
                ctx.engine.start()
                val replay: Seen = observe(ctx, "unity=$unity replay", beforeReplay, OBSERVE_MS)
                println("BOSS2-FIX-REPLAY $replay")
                assertEquals(1, replay.seeks.size, "the replay was not the one seek of the caller: $replay")
                assertEquals(1, replay.plays.size, "the replay did not issue exactly one play: $replay")
                val replaySpeedAfter: Double? = replay.speedGivenAfterPlayMs
                assertTrue(replaySpeedAfter != null && replaySpeedAfter < SETTLE_MS, "the replay's speed was not given right after the play: $replay")
                assertEquals(emptyList(), replay.violations, "a fragile window was violated: $replay")
                assertTrue(!replay.frozen, "the playhead froze: $replay")
            }
        }
    }

    private companion object {
        const val CLIP_MS: Double = 3_000.0
        const val OBSERVE_MS: Long = 1_200L
        const val MAX_STEPS: Int = 100_000
        const val TRACE_STEP_MS: Long = 100L
        const val NEAR_START_MS: Double = 100.0
        const val SETTLE_MS: Double = 200.0
        const val SETTLE_LATEST_MS: Double = 700.0
        const val MOVED_MS: Long = 300L
    }
}
