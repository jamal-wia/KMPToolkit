package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 round 13, Q3: the accessibility sentences of `kmptoolkit-video-player-compose` that no earlier test pinned:
 * `03-guide.md` "Accessibility labels" ("With `null`, TalkBack and VoiceOver announce 'button' and nothing more") and
 * `04-api-reference.md` ("`null` -> role only"; `BufferingIndicator`: "indeterminate progress semantics").
 */
@OptIn(ExperimentalTestApi::class)
abstract class ControlsSemanticsD1R13Tests {

    private val buttonRole: SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private val noDescription: SemanticsMatcher = SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)

    @Test
    fun `with every label null each control is a button with a click action and no description`() = runComposeUiTest {
        val tp = TestPlayer().paused(5_000L)
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                    DefaultVideoControls(onFullscreenClick = {})
                }
            }
        }
        waitForIdle()

        val buttons = onAllNodes(hasClickAction() and buttonRole and noDescription).fetchSemanticsNodes()
        // Play, backward, forward, mute, speed and fullscreen: six controls named by role only.
        assertEquals(6, buttons.size, "controls announced as a bare button: $buttons")
        for (node in buttons) {
            assertTrue(SemanticsProperties.ContentDescription !in node.config, "a control carries a description although every label is null: ${node.config}")
        }
    }

    @Test
    fun `with every label given each control is a button named after what it does now`() = runComposeUiTest {
        val tp = TestPlayer().paused(5_000L)
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                    DefaultVideoControls(labels = LABELS, onFullscreenClick = {})
                }
            }
        }
        waitForIdle()

        for (label: String? in listOf(LABELS.play, LABELS.seekBackward, LABELS.seekForward, LABELS.mute, LABELS.playbackSpeed, LABELS.enterFullscreen)) {
            onNodeWithContentDescription(label!!).assertExists("no control named $label")
            onAllNodes(hasClickAction() and buttonRole and SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label)))
                .fetchSemanticsNodes().also { assertEquals(1, it.size, "exactly one button named $label, was ${it.size}") }
        }
        // The names follow the state: the labels of the states the player is not in are absent.
        for (absent: String? in listOf(LABELS.pause, LABELS.unmute, LABELS.exitFullscreen, LABELS.replay)) {
            onNodeWithContentDescription(absent!!).assertDoesNotExist()
        }
    }

    @Test
    fun `the buffering spinner announces itself as an indeterminate progress with the buffering label`() = runComposeUiTest {
        val tp = TestPlayer().playing(1_000L)
        setContent {
            TestHost { VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) } }
        }
        waitForIdle()
        onNodeWithContentDescription(LABELS.buffering!!).assertDoesNotExist()

        tp.fake.reportBuffering(true)
        waitForIdle()
        onNodeWithContentDescription(LABELS.buffering!!).assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)

        tp.fake.reportBuffering(false)
        waitForIdle()
        onNodeWithContentDescription(LABELS.buffering!!).assertDoesNotExist()
    }
}
