package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An accessibility service (or any caller of the semantics action) may hand the seek bar a progress
 * that is not a fraction. The bar's contract: `onSeek` is only ever called with a position in
 * `0..duration`, and nothing throws out of the action.
 */
@OptIn(ExperimentalTestApi::class)
abstract class SeekBarHostileInputAdversarialTests {

    private fun androidx.compose.ui.test.ComposeUiTest.seeksFor(progress: Float, durationMs: Long = 60_000L): List<Long> {
        val seeks: MutableList<Long> = mutableListOf()
        setContent {
            Box(Modifier.size(300.dp, 48.dp)) {
                VideoSeekBar(
                    positionMs = 6_000L,
                    durationMs = durationMs,
                    onSeek = { seeks += it },
                    contentDescription = "Bar",
                )
            }
        }
        onNodeWithContentDescription("Bar").performSemanticsAction(SemanticsActions.SetProgress) { it(progress) }
        waitForIdle()
        return seeks
    }

    @Test
    fun `a progress that is not a number never throws and never seeks outside the source`() {
        val failures: MutableList<String> = mutableListOf()
        for (progress in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.5f, 7f, -0.0f)) {
            try {
                runComposeUiTest {
                    val seeks: List<Long> = seeksFor(progress)
                    if (seeks.any { it !in 0L..60_000L }) failures += "progress $progress sought $seeks"
                }
            } catch (thrown: Throwable) {
                failures += "progress $progress threw ${thrown::class.simpleName}: ${thrown.message}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test
    fun `an enormous duration and playhead leave a drawable bar whose progress stays in range`() = runComposeUiTest {
        setContent {
            Box(Modifier.size(300.dp, 48.dp)) {
                VideoSeekBar(
                    positionMs = Long.MAX_VALUE,
                    durationMs = Long.MAX_VALUE,
                    bufferedPositionMs = Long.MIN_VALUE,
                    onSeek = {},
                    contentDescription = "Bar",
                )
            }
        }
        waitForIdle()
        val info: ProgressBarRangeInfo = onNodeWithContentDescription("Bar")
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1f, info.current)
    }
}
