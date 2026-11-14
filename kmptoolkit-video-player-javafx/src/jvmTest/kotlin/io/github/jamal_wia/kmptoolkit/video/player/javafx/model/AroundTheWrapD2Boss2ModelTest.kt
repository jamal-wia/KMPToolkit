package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 boss 2: the orderings around a loop wrap that the B6 fix (a start within 500 ms of a wrap takes the settle path) did not
 * pin by itself. A looping 3 s clip is paused the instant it wraps; between that pause and the next start, and right after the
 * start, the caller does one more thing: seeks, changes the speed, switches looping off, pauses and starts again, or gives the
 * speed only after a plain play at 1x (the FRESH branch of setSpeed right after a wrap). Every ordering must keep every fragile
 * window of the hazard model clear, never freeze the playhead, move it on, and end with the asked speed written. A release at
 * the wrap must leave no native call after the dispose. Both decoder variants of the model are run.
 */
class AroundTheWrapD2Boss2ModelTest {

    private class Ctx(val engine: JavaFxVideoEngine, val native: ScriptedNativePlayer, val step: (Long) -> Unit)

    private class Seen(
        val label: String,
        val calls: List<NativeCallEntry>,
        val violations: List<String>,
        val frozen: Boolean,
        val furthestNativeMs: Long,
        val nativeAtStartMs: Long,
        val trace: String,
    ) {
        override fun toString(): String =
            "$label frozen=$frozen violations=$violations furthest=${furthestNativeMs}ms atStart=${nativeAtStartMs}ms\n" +
                calls.joinToString("\n") { "    $it" } + "\n    trace=$trace"
    }

    private fun aroundWrap(
        label: String,
        unity: Boolean,
        speed: Float,
        betweenPauseAndStart: (Ctx) -> Unit,
        afterStart: (Ctx) -> Unit,
    ): Seen {
        lateinit var seen: Seen
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
                val native: ScriptedNativePlayer = composed.player
                val ctx = Ctx(engine, native) { advanceBy(it) }
                engine.setLooping(true)
                if (speed != 1f) engine.setSpeed(speed)
                engine.seekTo(2_300L)
                engine.start()
                var guard = 0
                while (engine.positionMs() < 2_600L) {
                    check(guard++ < MAX_STEPS) { "the clip never passed 2600 ms" }
                    advanceBy(1L)
                }
                while (composed.loop.cycle < 2) {
                    check(guard++ < MAX_STEPS) { "the clip never wrapped" }
                    advanceBy(1L)
                }
                val callsBefore: Int = native.log.size
                engine.pause()
                betweenPauseAndStart(ctx)
                val atStart: Long = native.positionMs.toLong()
                engine.start()
                afterStart(ctx)
                val trace = StringBuilder()
                var furthest = 0L
                var elapsed = 0L
                while (elapsed < OBSERVE_MS) {
                    advanceBy(TRACE_STEP_MS)
                    elapsed += TRACE_STEP_MS
                    furthest = maxOf(furthest, native.positionMs.toLong())
                    trace.append(" $elapsed:${native.positionMs.toLong()}")
                }
                seen = Seen(
                    label = "$label unity=$unity",
                    calls = native.log.entries.drop(callsBefore).filter { it.call != NativeCall.SNAPSHOT },
                    violations = composed.monitor.violations.map { it.describe() },
                    frozen = composed.hazards.frozen,
                    furthestNativeMs = furthest,
                    nativeAtStartMs = atStart,
                    trace = trace.toString(),
                )
            } finally {
                engine.release()
                advanceBy(50)
            }
        }
        return seen
    }

    private fun assertSafeAndMoving(seen: Seen, expectedLastRate: Double, movedPastMs: Long) {
        println("BOSS2-WRAP $seen")
        assertEquals(emptyList(), seen.violations, "a fragile window was violated: $seen")
        assertTrue(!seen.frozen, "the playhead froze: $seen")
        assertTrue(seen.furthestNativeMs > movedPastMs, "the playhead did not move on: $seen")
        // The observation may run into the next wrap, which puts the property to 1.0 again by design: so the asked speed must
        // be among the effective writes after the last play, not necessarily the last of them.
        val lastPlay: Int = seen.calls.indexOfLast { it.call == NativeCall.PLAY }
        assertTrue(lastPlay >= 0, "the start issued no play: $seen")
        val ratesAfterPlay: List<Double> = seen.calls.drop(lastPlay + 1)
            .filter { it.call == NativeCall.SET_RATE && it.effective }
            .mapNotNull { it.arg }
        if (expectedLastRate == 1.0) {
            assertTrue(ratesAfterPlay.all { it == 1.0 }, "a speed other than the asked 1x was written after the start: $seen")
        } else {
            assertTrue(expectedLastRate in ratesAfterPlay, "the asked speed was never written after the start: $seen")
        }
    }

    /** A seek made between the pause at the wrap and the start: the start takes the seek path from the new place. */
    @Test
    fun aSeekBetweenAPauseAtTheWrapAndTheStartKeepsEveryWindowClearAndGivesTheSpeed() {
        for (unity: Boolean in listOf(true, false)) {
            for (gapMs: Long in listOf(0L, 30L, 200L)) {
                val seen: Seen = aroundWrap(
                    "seek-between gap=$gapMs",
                    unity,
                    2f,
                    betweenPauseAndStart = { ctx ->
                        ctx.engine.seekTo(1_500L)
                        ctx.step(gapMs)
                    },
                    afterStart = { },
                )
                assertSafeAndMoving(seen, expectedLastRate = 2.0, movedPastMs = 1_800L)
            }
        }
    }

    /** A new speed (3x, or back to 1x) given while paused at the wrap reaches the decoder after the start. */
    @Test
    fun aSpeedGivenWhilePausedAtTheWrapIsTheOneTheStartGives() {
        for (unity: Boolean in listOf(true, false)) {
            for (newSpeed: Float in listOf(3f, 1f)) {
                for (gapMs: Long in listOf(0L, 100L, 400L)) {
                    val seen: Seen = aroundWrap(
                        "speed-between new=$newSpeed gap=$gapMs",
                        unity,
                        2f,
                        betweenPauseAndStart = { ctx ->
                            ctx.engine.setSpeed(newSpeed)
                            ctx.step(gapMs)
                        },
                        afterStart = { },
                    )
                    assertSafeAndMoving(seen, expectedLastRate = newSpeed.toDouble(), movedPastMs = MOVED_MS)
                }
            }
        }
    }

    /** Looping switched off while paused at the wrap: the start still resumes safely and gives the speed. */
    @Test
    fun loopingSwitchedOffWhilePausedAtTheWrapStillResumesSafely() {
        for (unity: Boolean in listOf(true, false)) {
            for (gapMs: Long in listOf(0L, 250L)) {
                val seen: Seen = aroundWrap(
                    "looping-off gap=$gapMs",
                    unity,
                    2f,
                    betweenPauseAndStart = { ctx ->
                        ctx.engine.setLooping(false)
                        ctx.step(gapMs)
                    },
                    afterStart = { },
                )
                assertSafeAndMoving(seen, expectedLastRate = 2.0, movedPastMs = MOVED_MS)
            }
        }
    }

    /** Start, pause again and start once more, all inside the 500 ms after the wrap. */
    @Test
    fun aSecondPauseAndStartInsideTheWrapWindowKeepsEveryWindowClear() {
        for (unity: Boolean in listOf(true, false)) {
            for (secondPauseMs: Long in listOf(1L, 60L, 150L)) {
                val seen: Seen = aroundWrap(
                    "double-resume pauseAfter=$secondPauseMs",
                    unity,
                    2f,
                    betweenPauseAndStart = { },
                    afterStart = { ctx ->
                        ctx.step(secondPauseMs)
                        ctx.engine.pause()
                        ctx.step(40L)
                        ctx.engine.start()
                    },
                )
                assertSafeAndMoving(seen, expectedLastRate = 2.0, movedPastMs = MOVED_MS)
            }
        }
    }

    /**
     * Playing at 1x, paused at the wrap, started again (a plain play), then the speed given shortly after: setSpeed's seek
     * window and its FRESH branch both meet the stamps the wrap left (LOW-1 of reviews/d2-boss1-b6-fix-opus.md).
     */
    @Test
    fun aSpeedGivenRightAfterAPlainResumeAtTheWrapKeepsEveryWindowClear() {
        for (unity: Boolean in listOf(true, false)) {
            for (gapMs: Long in listOf(0L, 320L)) {
                for (speedAfterMs: Long in listOf(0L, 10L, 120L, 350L)) {
                    val seen: Seen = aroundWrap(
                        "fresh-after-wrap gap=$gapMs speedAfter=$speedAfterMs",
                        unity,
                        1f,
                        betweenPauseAndStart = { ctx -> ctx.step(gapMs) },
                        afterStart = { ctx ->
                            ctx.step(speedAfterMs)
                            ctx.engine.setSpeed(2f)
                        },
                    )
                    assertSafeAndMoving(seen, expectedLastRate = 2.0, movedPastMs = MOVED_MS)
                }
            }
        }
    }

    /** A release the instant the clip wraps, with the wrap's speed still held: nothing reaches the native player after dispose. */
    @Test
    fun aReleaseAtTheWrapLeavesNoNativeCallAfterTheDispose() {
        for (unity: Boolean in listOf(true, false)) {
            fxModel {
                val backend: ComposedBackend = composeAll(
                    ScriptedBackend(host, clip = ClipModel(durationMs = CLIP_MS, keyFrameIntervalMs = 100.0), script = StatusScript()),
                    timeScript = TimeScript(),
                    hazardConfig = HazardConfig(decoderStartsAtUnity = unity),
                )
                val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
                load(engine, VideoSource.Asset(TestClip.ASSET))
                val composed: ComposedPlayer = backend.last
                val native: ScriptedNativePlayer = composed.player
                engine.setLooping(true)
                engine.setSpeed(2f)
                engine.seekTo(2_300L)
                engine.start()
                var guard = 0
                while (composed.loop.cycle < 2) {
                    check(guard++ < MAX_STEPS) { "the clip never wrapped" }
                    advanceBy(1L)
                }
                engine.release()
                advanceBy(1_000L)
                val entries: List<NativeCallEntry> = native.log.entries.filter { it.call != NativeCall.SNAPSHOT }
                val disposeAt: Int = entries.indexOfFirst { it.call == NativeCall.DISPOSE }
                val text: String = entries.takeLast(12).joinToString("\n") { "    $it" }
                assertTrue(disposeAt >= 0, "release did not dispose the native player (unity=$unity):\n$text")
                val after: List<NativeCallEntry> = entries.drop(disposeAt + 1)
                assertEquals(emptyList(), after.map { it.toString() }, "native calls after the dispose (unity=$unity):\n$text")
                assertEquals(emptyList(), composed.monitor.violations.map { it.describe() }, "a fragile window was violated (unity=$unity)")
            }
        }
    }

    /**
     * The wrap window belongs to the wrap alone: a resume near the start shortly after a native seek of the engine's own (the
     * first play's seek) with no loop wrap still takes the near-start seek path of 05 ("it first seeks to where it stands").
     */
    @Test
    fun aResumeNearTheStartShortlyAfterAnOwnSeekWithoutAWrapStillSeeksWhereItStands() {
        for (unity: Boolean in listOf(true, false)) {
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
                    val native: ScriptedNativePlayer = composed.player
                    engine.setSpeed(2f)
                    engine.start()
                    advanceBy(30L)
                    engine.pause()
                    advanceBy(60L)
                    val before: Int = native.log.size
                    val atResume: Double = native.positionMs
                    engine.start()
                    advanceBy(1_000L)
                    val after: List<NativeCallEntry> = native.log.entries.drop(before).filter { it.call != NativeCall.SNAPSHOT }
                    val text: String = "unity=$unity atResume=${atResume}ms\n" + after.joinToString("\n") { "    $it" }
                    assertTrue(atResume <= 100.0, "the premise: the playhead stands near the start at the resume: $text")
                    assertEquals(1, after.count { it.call == NativeCall.SEEK }, "the near-start resume did not seek where it stands: $text")
                    assertEquals(emptyList(), composed.monitor.violations.map { it.describe() }, "a fragile window was violated: $text")
                    assertTrue(!composed.hazards.frozen, "the playhead froze: $text")
                } finally {
                    engine.release()
                    advanceBy(50)
                }
            }
        }
    }

    private companion object {
        const val CLIP_MS: Double = 3_000.0
        const val OBSERVE_MS: Long = 1_500L
        const val MAX_STEPS: Int = 100_000
        const val TRACE_STEP_MS: Long = 100L
        const val MOVED_MS: Long = 300L
    }
}
