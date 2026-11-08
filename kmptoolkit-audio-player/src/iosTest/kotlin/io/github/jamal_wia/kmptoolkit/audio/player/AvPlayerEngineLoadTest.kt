package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import platform.Foundation.NSBundle

/**
 * [PlaybackEngine.load] must leave nothing playable behind when it throws or is cancelled, as the
 * Android engine does: a failed load used to keep its AVPlayer, item and end-of-item observer until
 * the next load, unload or release.
 *
 * Only the cancelled path is driven here, with `Dispatchers.Main` swapped for `Default` so no main
 * run loop is needed. The failed-item path, which needs the real main queue, is
 * [AvPlayerEngineFailedLoadTest]; both go through the same `catch` in `load`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvPlayerEngineLoadTest {

    // The test runner does not pump the main queue, so a real Dispatchers.Main would never run the
    // engine's main-thread step. AVPlayer is created on any thread here; real time still passes.
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private companion object {
        /** Every wait is bounded, so a hang fails the test in seconds instead of stalling the run. */
        const val BOUND_MS: Long = 15_000
    }

    private fun engine(): AvPlayerEngine = AvPlayerEngine(
        assetBundle = NSBundle.mainBundle,
        assetSubdirectories = emptyList(),
        managesAudioSession = false,
    )

    @Test
    fun aCancelledLoadHoldsNoPlayer() = runBlocking { withTimeout(BOUND_MS) {
        val engine: AvPlayerEngine = engine()

        val load = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.load(AudioSource.Remote("http://10.255.255.1/never.m4a"))
        }
        delay(200)
        load.cancelAndJoin()

        assertFalse(engine.hasPlayer)
    } }
}
