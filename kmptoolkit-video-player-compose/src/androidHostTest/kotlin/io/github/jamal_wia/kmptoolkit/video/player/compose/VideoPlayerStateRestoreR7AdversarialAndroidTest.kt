package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round 7 (breaker hf), Q1, Android only: StateRestorationTester is not implemented by the Skia desktop test
 * host (NotImplementedError), so the saved-state restore of a composition that owns a player runs here.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class VideoPlayerStateRestoreR7AdversarialAndroidTest {

    @Test
    fun `after a saved state restore the old owned player is released and a new one prepares the source`() =
        runComposeUiTest {
            val factory = RecordingFactory()
            val tester = StateRestorationTester(this)
            tester.setContent {
                TestHost(factory = factory) {
                    VideoPlayer(source = SOURCE_A, autoPlay = true, controlsAutoHideDelayMs = NEVER_HIDE_MS)
                }
            }
            waitForIdle()
            assertEquals(1, factory.created.size)
            assertIs<VideoPlayerState.Playing>(factory.created[0].state)

            repeat(3) {
                tester.emulateSaveAndRestore()
                waitForIdle()
            }
            assertEquals(4, factory.created.size, "one player per composition instance")
            for (index: Int in 0..2) {
                val old: TestPlayer = factory.created[index]
                assertEquals(1, old.fake.disposeCount, "player $index was not released by the restore")
                assertFalse(old.fake.hasListener)
                assertEquals(VideoPlayerState.Idle, old.state)
            }
            val last: TestPlayer = factory.created[3]
            assertEquals(listOf(SOURCE_A), last.fake.loadedSources)
            assertIs<VideoPlayerState.Playing>(last.state)
            assertEquals(0, last.fake.disposeCount)
            last.player.release()
        }
}
