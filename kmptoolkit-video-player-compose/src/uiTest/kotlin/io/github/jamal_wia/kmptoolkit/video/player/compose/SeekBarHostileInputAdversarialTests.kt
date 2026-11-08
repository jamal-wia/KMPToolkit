package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A playhead, buffered position and duration at the edges of Long leave a drawable seek bar whose
 * progress stays a fraction.
 */
@OptIn(ExperimentalTestApi::class)
abstract class SeekBarHostileInputAdversarialTests {

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
