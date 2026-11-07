package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTimeInterval
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.runMode

/**
 * A load whose item really fails must leave no AVPlayer behind, which is the case that used to leak.
 *
 * The item's `status` and the engine's main-thread step are delivered through the main run loop,
 * so the test thread must pump it: the load runs on [Dispatchers.Default] and this (main) thread
 * only spins the run loop until the load ends, never waiting on work it has itself queued. The real
 * `Dispatchers.Main` is used, with no `setMain`.
 */
class AvPlayerEngineFailedLoadTest {

    private companion object {
        /** Every wait is bounded, so a hang fails the test in seconds instead of stalling the run. */
        const val BOUND_SECONDS: Double = 20.0
        const val SLICE_SECONDS: Double = 0.05
    }

    private fun engine(): AvPlayerEngine = AvPlayerEngine(
        assetBundle = NSBundle.mainBundle,
        assetSubdirectories = emptyList(),
        managesAudioSession = false,
    )

    @Test
    fun aLoadWhoseItemFailsHoldsNoPlayer() {
        val engine: AvPlayerEngine = engine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val load: Deferred<Result<Unit>> = scope.async {
                runCatching { engine.load(AudioSource.File("/nonexistent/missing.m4a")) }
            }
            val deadline: NSTimeInterval = NSDate().timeIntervalSince1970 + BOUND_SECONDS
            while (!load.isCompleted && NSDate().timeIntervalSince1970 < deadline) {
                NSRunLoop.currentRunLoop.runMode(
                    NSDefaultRunLoopMode,
                    beforeDate = NSDate.dateWithTimeIntervalSinceNow(SLICE_SECONDS),
                )
            }

            assertTrue(load.isCompleted, "the item neither failed nor became ready within the bound")
            val outcome: Result<Unit> = load.getCompleted()
            assertNotNull(outcome.exceptionOrNull(), "a missing file must fail the load")
            assertFalse(engine.hasPlayer, "the failed item's player must be released by the load itself")
        } finally {
            scope.cancel()
        }
    }
}
