package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [ScriptedBackend] and [ClipModel] on their own; the player they build is checked in [ScriptedNativePlayerTest]. */
class ScriptedBackendTest {

    @Test
    fun probeRecordsTheUriAndReturnsAnOpaqueSource() = fxModel {
        val backend = ScriptedBackend(host)

        val source: NativeSource = backend.probe("file:///a.mp4")

        assertEquals(listOf("file:///a.mp4"), backend.probed)
        assertTrue(source is ScriptedNativeSource && source.uri == "file:///a.mp4")
    }

    @Test
    fun createBuildsAPlayerOnTheBackendsHostAndClipAndRecordsIt() = fxModel {
        val clip = ClipModel(durationMs = 500.0)
        val backend = ScriptedBackend(host, clip)
        allowUnwatched(backend, "test of the bare scripted backend: no hazard part is wanted")

        val player = backend.create(backend.probe("x")) as ScriptedNativePlayer

        assertSame(host, player.host)
        assertSame(clip, player.clip)
        assertSame(player, backend.last)
        assertEquals(listOf(player), backend.players)
    }

    @Test
    fun theBackendExposesItsHostAsTheNativeBackendHost() = fxModel {
        val backend = ScriptedBackend(host)

        assertSame(host, backend.host)
    }

    @Test
    fun everyPlayerGetsItsOwnCopyOfTheScriptTakenAtCreation() = fxModel {
        val backend = ScriptedBackend(host, script = StatusScript().apply { readyLatencyMs = 80.0 })
        allowUnwatched(backend, "test of the bare scripted backend: no hazard part is wanted")
        val first = backend.create(backend.probe("a")) as ScriptedNativePlayer

        backend.script.readyLatencyMs = 10.0
        val second = backend.create(backend.probe("b")) as ScriptedNativePlayer

        assertNotSame(backend.script, first.script)
        assertEquals(80.0, first.script.readyLatencyMs)
        assertEquals(10.0, second.script.readyLatencyMs)
        advanceBy(10)
        assertEquals(NativeStatus.UNKNOWN, first.status)
        assertEquals(NativeStatus.READY, second.status)
    }

    @Test
    fun aScriptCopyKeepsEverySettingAndIsIndependentOfTheOriginal() {
        val cause = IllegalStateException("x")
        val original: StatusScript = StatusScript()
            .neverReady()
            .failAt(5.0, cause, FailureVia.MEDIA)
            .statusLatency(NativeCall.PLAY, 2, 77.0)
        original.readyLatencyMs = 9.0
        original.statusLatencyMs = 4.0

        val copy: StatusScript = original.copy()
        original.failAt(6.0, cause)

        assertTrue(copy.neverReady)
        assertEquals(9.0, copy.readyLatencyMs)
        assertEquals(4.0, copy.statusLatencyMs)
        assertEquals(77.0, copy.statusLatencyFor(NativeCall.PLAY, 2))
        assertEquals(4.0, copy.statusLatencyFor(NativeCall.PLAY, 1))
        assertEquals(1, copy.failures.size)
        assertEquals(2, original.failures.size)
    }

    @Test
    fun aProbeFailureIsThrownAndRecordsNothing() = fxModel {
        val backend = ScriptedBackend(host)
        val boom = IllegalArgumentException("cannot open")
        backend.probeFailure = boom

        val thrown: IllegalArgumentException = assertFailsWith { backend.probe("bad") }

        assertSame(boom, thrown)
        assertTrue(backend.probed.isEmpty())
        backend.probeFailure = null
        backend.probe("good")
        assertEquals(listOf("good"), backend.probed)
    }

    @Test
    fun createRefusesASourceThatIsNotItsOwn() = fxModel {
        val backend = ScriptedBackend(host)

        assertFailsWith<IllegalStateException> { backend.create(object : NativeSource {}) }
        assertTrue(backend.players.isEmpty())
    }

    @Test
    fun lastFailsWhileNoPlayerWasCreated() = fxModel {
        val backend = ScriptedBackend(host)

        val failure: IllegalStateException = assertFailsWith { backend.last }

        assertTrue(failure.message.orEmpty().contains("no player"), failure.message)
    }

    @Test
    fun probeIsSafeToCallFromManyThreadsAsTheIoDispatcherDoes() = fxModel {
        val backend = ScriptedBackend(host)
        val workers: List<Thread> = List(8) { index ->
            thread(name = "probe-$index") { repeat(50) { backend.probe("u$index-$it") } }
        }
        for (worker in workers) worker.join(TimeUnit.SECONDS.toMillis(10))

        assertTrue(workers.none { it.isAlive }, "a probe thread did not finish within the 10 s hang bound")
        assertEquals(400, backend.probed.size)
        assertEquals(400, backend.probed.toSet().size)
    }

    @Test
    fun withAJitterOnTheHostTheStatusLatencyGrowsByAtMostTheJitter() = fxModel(jitterMaxMs = 17.0) {
        val backend = ScriptedBackend(host)
        allowUnwatched(backend, "test of the bare scripted backend: no hazard part is wanted")
        val player = backend.create(backend.probe("x")) as ScriptedNativePlayer
        advanceBy(50 + 17)
        assertEquals(NativeStatus.READY, player.status)

        onFx { player.play() }
        advanceBy(30 - 1)
        assertEquals(NativeStatus.READY, player.status)
        advanceBy(1 + 17)

        assertEquals(NativeStatus.PLAYING, player.status)
    }

    // -- the clip --

    @Test
    fun theDefaultClipIsTheCheckedInTestClip() {
        val clip = ClipModel()

        assertEquals(3_000.0, clip.durationMs)
        assertEquals(1_000.0, clip.keyFrameIntervalMs)
        assertEquals(64, clip.width)
        assertEquals(48, clip.height)
        assertEquals(40.0, clip.firstPictureAfterReadyMs)
    }

    @Test
    fun aClipRefusesValuesThatCannotDescribeASource() {
        assertFailsWith<IllegalArgumentException> { ClipModel(durationMs = 0.0) }
        assertFailsWith<IllegalArgumentException> { ClipModel(durationMs = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { ClipModel(keyFrameIntervalMs = -1.0) }
        assertFailsWith<IllegalArgumentException> { ClipModel(width = 0) }
        assertFailsWith<IllegalArgumentException> { ClipModel(height = -3) }
        assertFailsWith<IllegalArgumentException> { ClipModel(firstPictureAfterReadyMs = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun aScriptRefusesAnInvalidLatencyOrFailureTime() {
        assertFailsWith<IllegalArgumentException> { StatusScript().statusLatency(NativeCall.SEEK, 1, 5.0) }
        assertFailsWith<IllegalArgumentException> { StatusScript().statusLatency(NativeCall.PLAY, 0, 5.0) }
        assertFailsWith<IllegalArgumentException> { StatusScript().statusLatency(NativeCall.PLAY, 1, -5.0) }
        assertFailsWith<IllegalArgumentException> { StatusScript().failAt(-1.0, IllegalStateException()) }
    }
}
