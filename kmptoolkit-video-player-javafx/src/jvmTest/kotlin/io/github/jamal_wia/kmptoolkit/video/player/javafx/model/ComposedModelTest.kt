package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The guard that every scripted player is watched by default (MINOR-4), the one composition builder (MINOR-6) and the
 * dispose violations in the end-of-test check (MINOR-5). Virtual time only; no JavaFX, no real media.
 */
class ComposedModelTest {

    /** A ready player with its event record, built the way `FxPlayback` builds one, parts NOT yet composed. */
    private fun FxModelScope.barePlayer(events: RecordingNativeEvents): ScriptedNativePlayer {
        val backend = ScriptedBackend(host)
        val player: ScriptedNativePlayer = onFx {
            (backend.create(backend.probe("clip.mp4")) as ScriptedNativePlayer).also { it.setEvents(events) }
        }
        return player
    }

    // -- MINOR-4: watched by default --

    @Test
    fun aBareScriptedBackendEngineTestFailsTheAfterCheckAndNamesAllowUnwatched() {
        val failure: AssertionError = assertFailsWith {
            fxModel {
                val backend = ScriptedBackend(host)
                val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
                load(engine, VideoSource.Asset(TestClip.ASSET))
                engine.release()
                advanceBy(50)
            }
        }

        val message: String = failure.message.orEmpty()
        assertTrue("allowUnwatched" in message, message)
        assertTrue("no watched hazard part" in message, message)
    }

    @Test
    fun aBareScriptedPlayerFailsTheAfterCheckEvenWhenTheBlockAssertsNothingAboutHazards() {
        val failure: AssertionError = assertFailsWith {
            fxModel {
                onFx { ScriptedNativePlayer(host) }
            }
        }

        assertTrue("allowUnwatched" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun aFailingBlockKeepsItsOwnFailureAndTheUnwatchedPlayerIsAddedAsSuppressed() {
        val failure: IllegalStateException = assertFailsWith {
            fxModel {
                onFx { ScriptedNativePlayer(host) }
                throw IllegalStateException("the block's own failure")
            }
        }

        assertEquals("the block's own failure", failure.message)
        assertTrue("allowUnwatched" in failure.suppressed.single().message.orEmpty())
    }

    @Test
    fun allowUnwatchedForAPlayerPassesTheGuardForThatPlayerOnly() {
        fxModel {
            val player: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
            allowUnwatched(player, "the test is about something else")
        }
        val failure: AssertionError = assertFailsWith {
            fxModel {
                val allowed: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
                allowUnwatched(allowed, "the test is about something else")
                onFx { ScriptedNativePlayer(host) }
            }
        }

        assertTrue("1 scripted player(s)" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun allowUnwatchedForABackendCoversEveryPlayerItCreatesEvenLaterOnes() = fxModel {
        val backend = ScriptedBackend(host)
        allowUnwatched(backend, "the test is about the backend")

        onFx {
            backend.create(backend.probe("a.mp4"))
            backend.create(backend.probe("b.mp4"))
        }

        assertEquals(2, host.players.size)
    }

    @Test
    fun allowUnwatchedNeedsAReason() = fxModel {
        val backend = ScriptedBackend(host)
        val player: ScriptedNativePlayer = onFx { backend.create(backend.probe("a.mp4")) as ScriptedNativePlayer }

        assertFailsWith<IllegalArgumentException> { allowUnwatched(player, " ") }
        assertFailsWith<IllegalArgumentException> { allowUnwatched(backend, "") }
        allowUnwatched(backend, "the test is about the backend")
    }

    @Test
    fun everyPlayerIsRegisteredWithItsHostInCreationOrder() = fxModel {
        val backend = ScriptedBackend(host)
        allowUnwatched(backend, "the test is about the registry")

        val first: ScriptedNativePlayer = onFx { backend.create(backend.probe("a.mp4")) as ScriptedNativePlayer }
        val second: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
        allowUnwatched(second, "the test is about the registry")

        assertEquals(listOf(first, second), host.players)
    }

    @Test
    fun playersOfComposeAllHazardBackendAndWatchHazardsPassTheGuard() = fxModel {
        val composed: ComposedBackend = composeAll(ScriptedBackend(host))
        val hazardOnly: HazardBackend = hazardBackend(ScriptedBackend(host))
        val direct: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
        watchHazards(direct)

        onFx {
            composed.create(composed.probe("a.mp4"))
            hazardOnly.create(hazardOnly.probe("b.mp4"))
        }

        assertEquals(3, host.players.size)
        assertEquals(3, hazardMonitors.size)
    }

    @Test
    fun anUnwiredCompositionIsUnwatchedAndNeedsAllowUnwatched() {
        val failure: AssertionError = assertFailsWith {
            fxModel {
                val backend: ComposedBackend = composeAll(ScriptedBackend(host), watch = false)
                onFx { backend.create(backend.probe("a.mp4")) }
            }
        }
        assertTrue("allowUnwatched" in failure.message.orEmpty(), failure.message)

        fxModel {
            val inner = ScriptedBackend(host)
            allowUnwatched(inner, "the test provokes a violation on purpose")
            val backend: ComposedBackend = composeAll(inner, watch = false)
            onFx { backend.create(backend.probe("a.mp4")) }
        }
    }

    // -- MINOR-6: one builder with every part --

    @Test
    fun composeAllGivesEveryPlayerEveryPartAndTheBackendKeepsThem() = fxModel {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))

        val player: ScriptedNativePlayer = onFx { backend.create(backend.probe("a.mp4")) as ScriptedNativePlayer }
        val composed: ComposedPlayer = backend.last

        assertSame(player, composed.player)
        assertSame(composed.time, player.time)
        assertSame(composed.hazards, player.hazard)
        assertSame(composed.picture, player.picture)
        assertTrue(player.loop is ModelPartGroup)
        assertEquals(listOf(composed), backend.players)
    }

    @Test
    fun composeAllDeliversPulses() = fxModel {
        val events = RecordingNativeEvents(host)
        val player: ScriptedNativePlayer = barePlayer(events)
        composePlayer(player)
        advanceBy(50)
        events.clear()

        onFx { player.startPulse() }
        advanceBy(1_000)

        assertEquals(60, events.names.count { it == "pulse" })
    }

    @Test
    fun composeAllStallsAPlayingPlayerAndResumesIt() = fxModel {
        val events = RecordingNativeEvents(host)
        val player: ScriptedNativePlayer = barePlayer(events)
        val composed: ComposedPlayer = composePlayer(player)
        advanceBy(50)
        onFx { player.play() }
        advanceBy(StatusScript.DEFAULT_STATUS_LATENCY_MS.toLong())
        assertEquals(NativeStatus.PLAYING, player.status)

        composed.stall.stallAt(10.0)
        composed.stall.resumeAt(110.0)
        advanceBy(10)
        assertEquals(NativeStatus.STALLED, player.status)
        assertTrue(composed.stall.isStalled)

        advanceBy(100)
        assertEquals(NativeStatus.PLAYING, player.status)
        assertEquals(1, composed.stall.stalls)
    }

    @Test
    fun composeAllDeliversALateStatusOverDisposed() = fxModel {
        val events = RecordingNativeEvents(host)
        val player: ScriptedNativePlayer = barePlayer(events)
        val composed: ComposedPlayer = composePlayer(player, lateStatus = NativeStatus.PLAYING)
        advanceBy(50)

        onFx { player.dispose() }
        assertEquals(NativeStatus.DISPOSED, player.status)
        advanceBy(DisposeModel.DEFAULT_LATE_DELAY_MS.toLong())

        assertTrue(composed.dispose.lateStatusLanded)
        assertEquals(NativeStatus.PLAYING, player.status)
    }

    // -- MINOR-5: dispose violations join the after-check --

    @Test
    fun aDisposeViolationOfAComposedPlayerFailsTheModelTestAtTheEnd() {
        val failure: AssertionError = assertFailsWith {
            fxModel {
                val events = RecordingNativeEvents(host)
                val player: ScriptedNativePlayer = barePlayer(events)
                composePlayer(player, lateEndOfMedia = true)
                advanceBy(50)
                onFx {
                    player.cycleCount = 2
                    player.dispose()
                }
                advanceBy(DisposeModel.DEFAULT_LATE_DELAY_MS.toLong())
            }
        }

        assertTrue("dispose part recorded 1 violation" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun aComposedPlayerWithoutADisposeViolationPassesTheModelTest() = fxModel {
        val events = RecordingNativeEvents(host)
        val player: ScriptedNativePlayer = barePlayer(events)
        val composed: ComposedPlayer = composePlayer(player, lateEndOfMedia = true)
        advanceBy(50)

        onFx { player.dispose() }
        advanceBy(DisposeModel.DEFAULT_LATE_DELAY_MS.toLong())

        assertTrue(composed.dispose.lateEndRan)
        assertTrue(composed.dispose.violations.isEmpty())
    }
}
