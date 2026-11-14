package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.audio.player.testing.FakePlaybackEngine
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** A `MediaPlayer` that records every playback rate it is given, as a device that accepts them all. */
@Implements(MediaPlayer::class)
class SpeedRecordingShadowMediaPlayer : ShadowMediaPlayer() {

    @Implementation
    fun getPlaybackParams(): PlaybackParams = PlaybackParams()

    @Implementation
    fun setPlaybackParams(params: PlaybackParams) {
        speeds += params.speed
    }

    companion object {
        val speeds: CopyOnWriteArrayList<Float> = CopyOnWriteArrayList()
    }
}

/**
 * Round 11, Q1: `FakePlaybackEngine` against the real [MediaPlayerEngine] over Robolectric's
 * `ShadowMediaPlayer` (the strictest stand-in: it enforces the `MediaPlayer` state diagram). The same
 * script of engine calls runs against both; after every step the observable engine state (length,
 * playhead, whether output is running, the rate last applied, the callbacks received, the outcome of a
 * load) must be equal. Every row that differs is listed with the step and both values.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], shadows = [SpeedRecordingShadowMediaPlayer::class])
class EngineFakeVersusRealR11Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val created: CopyOnWriteArrayList<Pair<MediaPlayer, ShadowMediaPlayer>> = CopyOnWriteArrayList()
    private var counter: Int = 0

    @Before
    fun setUp() {
        SpeedRecordingShadowMediaPlayer.speeds.clear()
        ShadowMediaPlayer.setCreateListener { player: MediaPlayer, shadow: ShadowMediaPlayer ->
            created += player to shadow
        }
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        ShadowMediaPlayer.setCreateListener(null)
    }

    private interface Subject {
        fun step(step: String)
        fun snapshot(): Map<String, String>
    }

    private val loops: Looper get() = Looper.getMainLooper()

    private fun <T> bounded(block: suspend CoroutineScope.() -> T): T =
        runBlocking { withTimeout(60_000) { block() } }

    private fun pumpUntil(what: String, condition: () -> Boolean) {
        // timing-hygiene-ignore: TH2 bound hang deadline of a wait for a worker thread, never asserted as a time
        val deadline = TimeSource.Monotonic.markNow() + 15.seconds
        while (true) {
            shadowOf(loops).idle()
            if (condition()) return
            if (deadline.hasPassedNow()) fail("timed out waiting for: $what")
            Thread.yield()
        }
    }

    private fun source(): AudioSource.Remote = AudioSource.Remote("https://example.test/r11-${counter++}.mp3")

    private fun runLoad(load: suspend () -> Unit, outcomes: MutableList<String>) {
        var outcome: String? = null
        val job: Job = CoroutineScope(Dispatchers.Default).launch {
            outcome = try {
                load()
                "ok"
            } catch (failure: Throwable) {
                "threw ${failure::class.simpleName}"
            }
        }
        pumpUntil("a load returns") { job.isCompleted }
        outcomes += "load:$outcome"
    }

    private fun runCancelledLoad(
        load: suspend () -> Unit,
        started: () -> Boolean,
        outcomes: MutableList<String>,
    ) {
        val job: Job = CoroutineScope(Dispatchers.Default).launch { load() }
        pumpUntil("the load to begin") { started() }
        job.cancel()
        pumpUntil("the cancelled load to unwind") { job.isCompleted }
        outcomes += "load:cancelled=${job.isCancelled}"
    }

    private inner class FakeSubject : Subject {
        val engine: FakePlaybackEngine = FakePlaybackEngine(DURATION)
        val events: MutableList<String> = mutableListOf()
        val outcomes: MutableList<String> = mutableListOf()

        init {
            engine.setListener(listener(events))
        }

        override fun step(step: String) {
            val (name: String, arg: String?) = step.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
            when (name) {
                "load" -> { engine.loadFailure = null; runLoad({ engine.load(source()) }, outcomes) }
                "loadFail" -> {
                    engine.loadFailure = IOException("no route")
                    runLoad({ engine.load(source()) }, outcomes)
                    engine.loadFailure = null
                }
                "loadCancel" -> {
                    engine.loadFailure = null
                    engine.loadDelayMs = Long.MAX_VALUE / 4
                    val before: Int = engine.loadedSources.size
                    runCancelledLoad({ engine.load(source()) }, { engine.loadedSources.size > before }, outcomes)
                    engine.loadDelayMs = 0L
                }
                "start" -> engine.start()
                "pause" -> engine.pause()
                "seek" -> engine.seekTo(arg!!.toLong())
                "speed" -> engine.setSpeed(arg!!.toFloat())
                "advance" -> if (engine.isPlaying) engine.advancePositionBy(arg!!.toLong())
                "complete" -> engine.completePlayback()
                "error" -> engine.failPlayback(IOException("MediaPlayer failed: what=1 extra=0"))
                "release" -> engine.release()
                "detach" -> engine.setListener(null)
                else -> error("unknown step $step")
            }
        }

        override fun snapshot(): Map<String, String> = linkedMapOf(
            "durationMs" to engine.durationMs().toString(),
            "positionMs" to engine.positionMs().toString(),
            "playing" to engine.isPlaying.toString(),
            "speed" to engine.appliedSpeed.toString(),
            "events" to events.toString(),
            "loads" to outcomes.toString(),
        )
    }

    private inner class RealSubject : Subject {
        val engine: MediaPlayerEngine = MediaPlayerEngine(context)
        val events: MutableList<String> = mutableListOf()
        val outcomes: MutableList<String> = mutableListOf()

        init {
            engine.setListener(listener(events))
            SpeedRecordingShadowMediaPlayer.speeds.clear()
        }

        private fun held(): MediaPlayer? =
            MediaPlayerEngine::class.java.getDeclaredField("mediaPlayer")
                .also { it.isAccessible = true }
                .get(engine)
                .let { holder -> (holder as MutableStateFlow<*>).value as MediaPlayer? }

        private fun playing(): Boolean = try {
            held()?.isPlaying ?: false
        } catch (_: IllegalStateException) {
            false
        }

        private fun registered(): AudioSource.Remote {
            val src: AudioSource.Remote = source()
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(src.url), ShadowMediaPlayer.MediaInfo(DURATION.toInt(), 0))
            return src
        }

        override fun step(step: String) {
            val (name: String, arg: String?) = step.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
            when (name) {
                "load" -> runLoad({ engine.load(registered()) }, outcomes)
                "loadFail" -> {
                    val src: AudioSource.Remote = source()
                    ShadowMediaPlayer.addException(DataSource.toDataSource(src.url), IOException("no route"))
                    runLoad({ engine.load(src) }, outcomes)
                }
                "loadCancel" -> {
                    val src: AudioSource.Remote = source()
                    ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(src.url), ShadowMediaPlayer.MediaInfo(DURATION.toInt(), Int.MAX_VALUE))
                    val before: Int = created.size
                    runCancelledLoad({ engine.load(src) }, { created.size > before && held() != null }, outcomes)
                }
                "start" -> engine.start()
                "pause" -> engine.pause()
                "seek" -> engine.seekTo(arg!!.toLong())
                "speed" -> engine.setSpeed(arg!!.toFloat())
                "advance" -> if (playing()) shadowOf(loops).idleFor(arg!!.toLong().milliseconds.toJavaDuration())
                "complete" -> {
                    if (playing()) {
                        val remaining: Long = DURATION - engine.positionMs()
                        shadowOf(loops).idleFor(remaining.milliseconds.toJavaDuration())
                    } else {
                        poked += created.last().second
                        created.last().second.invokeCompletionListener()
                    }
                }
                "error" -> {
                    poked += created.last().second
                    created.last().second.invokeErrorListener(1, 0)
                }
                "release" -> engine.release()
                "detach" -> engine.setListener(null)
                else -> error("unknown step $step")
            }
            shadowOf(loops).idle()
        }

        override fun snapshot(): Map<String, String> = linkedMapOf(
            "durationMs" to engine.durationMs().toString(),
            "positionMs" to engine.positionMs().toString(),
            "playing" to playing().toString(),
            "speed" to (SpeedRecordingShadowMediaPlayer.speeds.lastOrNull() ?: 1.0f).toString(),
            "events" to events.toString(),
            "loads" to outcomes.toString(),
        )

        // Shadows this driver poked after the engine let go of them: poking moves their state, so they say nothing.
        val poked: MutableSet<ShadowMediaPlayer> = mutableSetOf()
        val firstCreated: Int = created.size

        fun openHandles(): List<Int> = created.mapIndexedNotNull { index: Int, (_, shadow: ShadowMediaPlayer) ->
            if (index >= firstCreated && shadow !in poked && shadow.state != ShadowMediaPlayer.State.END) index else null
        }
    }

    private fun listener(events: MutableList<String>): PlaybackEngineListener = object : PlaybackEngineListener {
        override fun onCompleted() {
            events += "completed"
        }

        override fun onFailed(cause: Throwable) {
            events += "failed(${cause::class.simpleName})"
        }
    }

    private val table: List<Pair<String, List<String>>> = listOf(
        "unloaded: every transport call" to listOf("start", "pause", "seek:5000", "speed:2", "release"),
        "unloaded: seek then load" to listOf("seek:5000", "load"),
        "load ok" to listOf("load"),
        "load then start then 3 s" to listOf("load", "start", "advance:3000"),
        "load start pause" to listOf("load", "start", "advance:1000", "pause"),
        "seek then start then 2 s" to listOf("load", "seek:10000", "start", "advance:2000"),
        "seek beyond the length" to listOf("load", "seek:70000"),
        "seek to zero" to listOf("load", "seek:5000", "seek:0"),
        "seek beyond an int" to listOf("load", "seek:3000000000"),
        "play to the end" to listOf("load", "start", "advance:1000", "complete"),
        "restart after completion" to listOf("load", "start", "complete", "start"),
        "rewind after completion then start" to listOf("load", "start", "complete", "seek:0", "start"),
        "speed while paused" to listOf("load", "speed:2"),
        "speed while playing" to listOf("load", "start", "speed:2"),
        "speed paused then resumed" to listOf("load", "start", "speed:2", "pause", "speed:1.5", "start"),
        "error while playing" to listOf("load", "start", "advance:500", "error"),
        "error while ready" to listOf("load", "error"),
        "every call after an error" to listOf("load", "start", "error", "start", "pause", "seek:1000", "speed:2", "release"),
        "load failure" to listOf("loadFail"),
        "load failure then every call" to listOf("loadFail", "start", "pause", "seek:1000", "speed:2", "release"),
        "failed load replacing a good one" to listOf("load", "start", "advance:1000", "loadFail", "start"),
        "second load replaces the first" to listOf("load", "seek:4000", "load"),
        "load while playing" to listOf("load", "start", "advance:2000", "load"),
        "release twice" to listOf("load", "release", "release"),
        "calls after release" to listOf("load", "start", "release", "start", "seek:1000", "pause", "speed:3"),
        "load after release" to listOf("load", "release", "load", "start", "advance:500"),
        "load after a failed load and release" to listOf("loadFail", "release", "load"),
        "cancelled load" to listOf("loadCancel", "start", "pause"),
        "cancelled load discards the previous source" to listOf("load", "start", "advance:1000", "loadCancel"),
        "cancelled load then a good one" to listOf("loadCancel", "load", "start"),
        "detached listener hears no completion" to listOf("load", "start", "detach", "complete"),
        "completion after release" to listOf("load", "start", "release", "complete"),
        "error after release" to listOf("load", "start", "release", "error"),
        "release while playing then load again" to listOf("load", "start", "advance:2000", "release", "load"),
    )

    /** One observed difference between the fake and the real engine. */
    private data class Difference(val row: String, val step: String, val field: String, val fake: String, val real: String?)

    /**
     * The differences the `-testing` docs do not state (report R11-01, low, documentation): they are
     * pinned by category so that every OTHER disagreement, and any change in these, fails. `null` = not a known category.
     */
    private fun category(d: Difference): String? = when {
        d.field == "durationMs" && d.real == "0" && d.fake == DURATION.toString() -> "length of an engine with nothing loaded"
        d.field == "playing" && d.fake == "true" && d.real == "false" -> "start on an engine with nothing loaded"
        d.field == "positionMs" && d.real == "0" && d.fake == DURATION.toString() && "complete" in d.step -> "start after completion restarts"
        d.field == "positionMs" && d.real == "0" && d.fake != "0" && "seek" in d.step -> "seek on an engine with nothing loaded"
        d.field == "positionMs" && d.real == DURATION.toString() && d.fake.toLong() > DURATION -> "seek beyond the length"
        d.field == "speed" -> "rate kept while not playing"
        d.field == "events" && d.real == "[]" && d.fake != "[]" -> "callback after the engine was released"
        else -> null
    }

    @Test
    fun `the fake and the real engine agree on every row of the table except the pinned categories`() {
        val all: MutableList<Difference> = mutableListOf()
        val leaks: MutableList<String> = mutableListOf()
        for ((name: String, steps: List<String>) in table) {
            val fake: FakeSubject = FakeSubject()
            val real: RealSubject = RealSubject()
            val performed: MutableList<String> = mutableListOf()
            for (step in steps) {
                performed += step
                fake.step(step)
                real.step(step)
                val realSnapshot: Map<String, String> = real.snapshot()
                for ((field: String, fakeValue: String) in fake.snapshot()) {
                    val realValue: String? = realSnapshot[field]
                    if (fakeValue != realValue) all += Difference(name, performed.joinToString(" "), field, fakeValue, realValue)
                }
            }
            real.engine.release()
            shadowOf(loops).idle()
            if (real.openHandles().isNotEmpty()) leaks += "[$name] media players ${real.openHandles()} were never released"
        }
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n"))
        val unknown: List<Difference> = all.filter { category(it) == null }
        assertTrue(unknown.isEmpty(), "unexplained differences (${unknown.size}):\n" + unknown.joinToString("\n"))
        val hit: Set<String?> = all.map { category(it) }.toSet()
        val expected: Set<String> = setOf(
            "length of an engine with nothing loaded", "start on an engine with nothing loaded",
            "seek on an engine with nothing loaded", "seek beyond the length", "start after completion restarts",
            "rate kept while not playing", "callback after the engine was released",
        )
        assertTrue(hit == expected, "categories now hit: $hit")
    }

    @Test
    fun `the real engine hands a rate to the platform only while playing and a refused call before a load is inert`() {
        val real: RealSubject = RealSubject()
        real.step("speed:2")
        real.step("load")
        real.step("speed:2")
        assertTrue(SpeedRecordingShadowMediaPlayer.speeds.isEmpty(), "a rate was pushed to a paused platform player: ${SpeedRecordingShadowMediaPlayer.speeds}")
        real.step("start")
        real.step("speed:2")
        assertTrue(SpeedRecordingShadowMediaPlayer.speeds.toList() == listOf(2f), "${SpeedRecordingShadowMediaPlayer.speeds}")
        real.step("pause")
        real.step("speed:3")
        assertTrue(SpeedRecordingShadowMediaPlayer.speeds.toList() == listOf(2f), "${SpeedRecordingShadowMediaPlayer.speeds}")
        real.engine.release()
        shadowOf(loops).idle()
    }

    private companion object {
        const val DURATION: Long = 60_000L
    }
}
