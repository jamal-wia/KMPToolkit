package io.github.jamal_wia.kmptoolkit.audio.player

import io.github.jamal_wia.kmptoolkit.audio.player.testing.FakePlaybackEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 10 differential for the player: one scenario table (every public call in every state, completion and
 * failure from the engine, extreme seeks, speeds) runs through `createAudioPlayer` over [FakePlaybackEngine]
 * (the published double) and over the module's own [RecordingPlaybackEngine]. The player must answer both alike:
 * the same state, position and speed after every step and the same state history. A divergence means the
 * published double behaves differently from the engine contract the player core was tested against.
 *
 * Only the engine double is the difference between the two runs; the real engines need real media (Android
 * shadows, an iOS player) and are covered by their own platform tests. Virtual time only; the same class runs on
 * the Android host and on the iOS simulator.
 */
class PlayerFakeVersusRecordingR10Test {

    private val track: AudioSource = AudioSource.Remote("https://example.test/a.mp3")
    private val other: AudioSource = AudioSource.File("/music/b.mp3")

    private val table: List<List<String>> = listOf(
        listOf("play"),
        listOf("pause"),
        listOf("stop"),
        listOf("seek:5000"),
        listOf("fwd"),
        listOf("back"),
        listOf("replay"),
        listOf("speed:2"),
        listOf("unload"),
        listOf("release", "prepare", "play", "seek:1", "release"),
        listOf("prepare", "play", "adv:3000", "pause", "play", "adv:2000", "stop"),
        listOf("prepare", "seek:4000", "play", "adv:1000", "seek:99999", "adv:100"),
        listOf("prepare", "seek:-5", "play", "adv:50"),
        listOf("prepare", "fwd", "fwd", "back", "back", "back"),
        listOf("prepare", "fwd:9223372036854775807", "back:9223372036854775807", "fwd:-5", "back:-5"),
        listOf("prepare", "play", "adv:9000", "complete", "play", "adv:1000"),
        listOf("prepare", "play", "complete", "replay", "adv:500", "pause"),
        listOf("prepare", "play", "complete", "seek:2000", "play", "adv:300"),
        listOf("prepare:fail", "play", "pause", "seek:1", "prepare", "play", "adv:10"),
        listOf("prepare", "play", "engine:fail", "play", "pause", "prepare"),
        listOf("prepare", "play", "speed:1.5", "adv:1000", "speed:0.5", "adv:1000", "pause"),
        listOf("prepare", "speed:3", "play", "unload", "play", "prepare:other", "play"),
        listOf("prepare", "prepare:other", "play", "adv:200"),
        listOf("prepare", "play", "prepare", "play", "adv:200"),
        listOf("prepare:zero", "play", "seek:100", "complete", "replay"),
        listOf("prepare", "stop", "play", "adv:100", "stop", "stop"),
        listOf("prepare", "play", "release", "play", "seek:1", "prepare"),
        listOf("prepare", "pause", "stop", "replay", "adv:700", "pause"),
        listOf("prepare", "play", "adv:4000", "fwd", "adv:100", "back", "adv:100"),
        listOf("prepare", "play", "adv:2000", "unload", "prepare", "play", "adv:100"),
        listOf("prepare", "complete"),
        listOf("prepare", "engine:fail"),
        listOf("prepare", "play", "pause", "complete"),
        listOf("prepare", "play", "engine:fail", "release", "play"),
        listOf("prepare", "speed:0", "speed:-1", "speed:NaN", "speed:Infinity", "play"),
        listOf("prepare", "play", "seek:10000", "adv:100", "seek:0", "adv:100"),
        listOf("prepare", "seek:5000", "fwd:9223372036854775807", "back:9223372036854775807", "fwd:9223372036854775807"),
        listOf("prepare", "play", "adv:3000", "back:-9223372036854775808", "fwd:-9223372036854775808"),
        listOf("prepare:zero", "fwd", "back", "seek:0", "play", "adv:100"),
    )

    @Test
    fun `the table has at least thirty rows over every public call`() {
        assertTrue(table.size >= 30, "rows: ${table.size}")
        val used: Set<String> = table.flatten().map { it.substringBefore(':') }.toSet()
        listOf("prepare", "play", "pause", "stop", "seek", "fwd", "back", "replay", "speed", "unload", "release", "complete", "engine")
            .forEach { assertTrue(it in used, "no row uses $it") }
    }

    @Test
    fun `the player answers every scenario row alike over the published fake and the recording engine`() {
        val mismatches: MutableList<String> = mutableListOf()
        table.forEachIndexed { index: Int, row: List<String> ->
            var fake: List<String> = emptyList()
            var recording: List<String> = emptyList()
            runTest { fake = play(row, useFake = true) }
            runTest { recording = play(row, useFake = false) }
            if (fake != recording) mismatches += "row $index $row\n" + diff(recording, fake)
        }
        assertEquals(emptyList(), mismatches, "fake and recording engine differ:\n" + mismatches.joinToString("\n\n"))
    }

    private fun diff(recording: List<String>, fake: List<String>): String = buildString {
        for (i: Int in 0 until maxOf(recording.size, fake.size)) {
            val r: String = recording.getOrElse(i) { "<none>" }
            val f: String = fake.getOrElse(i) { "<none>" }
            if (r != f) append("  step $i\n    recording: $r\n    fake:      $f\n")
        }
    }

    private suspend fun TestScope.play(row: List<String>, useFake: Boolean): List<String> {
        val fakeEngine = FakePlaybackEngine(durationMs = 10_000L)
        val recordingEngine = RecordingPlaybackEngine(duration = 10_000L)
        val engine: PlaybackEngine = if (useFake) fakeEngine else recordingEngine
        val player: AudioPlayer = createAudioPlayer(engine = engine, coroutineContext = StandardTestDispatcher(testScheduler))
        val history: MutableList<PlayerState> = mutableListOf()
        val log: MutableList<String> = mutableListOf()
        try {
            for (step: String in row) {
                val outcome: String = attempt {
                    when {
                        step == "prepare" -> { setLength(useFake, fakeEngine, recordingEngine, 10_000L, null); player.prepare(track) }
                        step == "prepare:other" -> player.prepare(other)
                        step == "prepare:zero" -> { setLength(useFake, fakeEngine, recordingEngine, 0L, null); player.prepare(track) }
                        step == "prepare:fail" -> {
                            setLength(useFake, fakeEngine, recordingEngine, 10_000L, IllegalStateException("load failed"))
                            player.prepare(track)
                            setLength(useFake, fakeEngine, recordingEngine, 10_000L, null)
                        }
                        step == "play" -> player.play()
                        step == "pause" -> player.pause()
                        step == "stop" -> player.stop()
                        step == "unload" -> player.unload()
                        step == "replay" -> player.replay()
                        step == "release" -> player.release()
                        step.startsWith("seek:") -> player.seekTo(step.removePrefix("seek:").toLong())
                        step == "fwd" -> player.seekForward()
                        step == "back" -> player.seekBackward()
                        step.startsWith("fwd:") -> player.seekForward(step.removePrefix("fwd:").toLong())
                        step.startsWith("back:") -> player.seekBackward(step.removePrefix("back:").toLong())
                        step.startsWith("speed:") -> player.setPlaybackSpeed(step.removePrefix("speed:").toFloat())
                        step.startsWith("adv:") -> {
                            val by: Long = step.removePrefix("adv:").toLong()
                            if (useFake) fakeEngine.advancePositionBy(by)
                            else {
                                val moved: Long = recordingEngine.position + by
                                // a source whose length is unknown (0) has no end to stop at
                                recordingEngine.position =
                                    if (recordingEngine.duration > 0L) minOf(moved, recordingEngine.duration) else moved
                            }
                            advanceTimeBy(by)
                        }
                        step == "complete" -> if (useFake) fakeEngine.completePlayback() else {
                            if (recordingEngine.duration > 0L) recordingEngine.position = recordingEngine.duration
                            recordingEngine.listener?.onCompleted()
                        }
                        step == "engine:fail" -> {
                            val cause = IllegalStateException("engine failed")
                            if (useFake) fakeEngine.failPlayback(cause) else recordingEngine.listener?.onFailed(cause)
                        }
                        else -> error("unknown step $step")
                    }
                }
                runCurrent()
                history += player.stateFlow.value
                log += "$step => $outcome | ${describe(player.stateFlow.value)} | pos=${player.playbackPositionFlow.value} | speed=${player.playbackSpeed}"
            }
        } finally {
            player.release()
        }
        log += "history=" + history.map(::describe)
        return log
    }

    private fun setLength(useFake: Boolean, fake: FakePlaybackEngine, rec: RecordingPlaybackEngine, ms: Long, failure: Throwable?) {
        if (useFake) { fake.durationMs = ms; fake.loadFailure = failure } else { rec.duration = ms; rec.loadFailure = failure }
    }

    private suspend fun attempt(block: suspend () -> Unit): String = try {
        block()
        "ok"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "threw ${e::class.simpleName}"
    }

    private fun describe(state: PlayerState): String = when (state) {
        PlayerState.Idle -> "Idle"
        PlayerState.Preparing -> "Preparing"
        is PlayerState.Ready -> "Ready(${state.duration})"
        is PlayerState.Playing -> "Playing(${state.duration},${state.currentPosition})"
        is PlayerState.Paused -> "Paused(${state.duration},${state.currentPosition})"
        is PlayerState.Completed -> "Completed(${state.duration})"
        is PlayerState.Error -> "Error(${state.cause::class.simpleName}:${state.cause.message})"
    }
}
