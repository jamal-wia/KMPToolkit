package io.github.jamal_wia.kmptoolkit.logging.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Random sequences of records (with and without a stack trace), clear, taps on rows and swapping the
 * state under the panel: a stack trace is shown for exactly the row that was tapped last, only while
 * that very record is still in the buffer, never for an unrelated record that reuses its id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class LogOverlayPanelModelFuzzTest {

    @Test
    fun `random records clears taps and state swaps show a trace only for the tapped record`() {
        for (seed in 1..25) {
            runComposeUiTest {
                val random = Random(seed)
                val states: List<LogOverlayState> = List(2) { LogOverlayState(maxRecords = 3) }
                var active by mutableStateOf(0)
                setContent { LogOverlayPanel(state = states[active]) }
                waitForIdle()
                var counter = 0
                var expanded: String? = null // message of the record whose row is expanded
                val log = StringBuilder()
                repeat(40) { step ->
                    when (random.nextInt(6)) {
                        0, 1, 2 -> {
                            val n = ++counter
                            states[active].record(LogLevel.INFO, "T", "msg$n", if (n % 3 != 0) IllegalStateException("boom$n").apply { stackTrace = emptyArray() } else null)
                            log.append("rec$n ")
                        }
                        3 -> { states[active].clear(); log.append("clear ") }
                        4 -> {
                            val rows: List<LogRecord> = states[active].records.value
                            if (rows.isNotEmpty()) {
                                val target: LogRecord = rows[random.nextInt(rows.size)]
                                waitForIdle()
                                try { onNodeWithText("[T] ${target.message}").performClick() } catch (e: AssertionError) { throw AssertionError("seed $seed step $step ($log) rows=${rows.map { it.message }} active=$active target=${target.message} texts=" + onRoot().printToString().lines().filter { "Text = " in it || "Node #" in it || "VerticalScroll" in it || "CollectionInfo" in it }.joinToString("|") { it.trim().take(80) }, e) }
                                expanded = if (expanded == target.message) null else target.message
                                log.append("tap(${target.message}) ")
                            }
                        }
                        else -> { active = 1 - active; log.append("swap ") }
                    }
                    waitForIdle()
                    val present: Boolean = states[active].records.value.any { it.message == expanded }
                    // a swapped state is a different buffer: the tapped record is not in it
                    val shownTraces: Int = onAllNodesWithText("boom", substring = true).fetchSemanticsNodes().size
                    val expectedTraces: Int =
                        if (present && expanded != null && expanded.removePrefix("msg").toInt() % 3 != 0) 1 else 0
                    assertEquals(expectedTraces, shownTraces, "seed $seed step $step ($log): expanded=$expanded")
                }
            }
        }
    }
}
