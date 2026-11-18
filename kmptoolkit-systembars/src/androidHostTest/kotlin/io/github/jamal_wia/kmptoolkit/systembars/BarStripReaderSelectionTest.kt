package io.github.jamal_wia.kmptoolkit.systembars

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which [BarStripReader] each Android API level gets (plan issue-13 section 6.5): the `WebView`
 * guard around the layer reader below API 26, the `PixelCopy` reader from API 26 on.
 *
 * This is the CI-visible guard against "simplifying" Android back to `toImageBitmap`, the offscreen
 * replay that blanks a hosted `WebView`. It pins the choice only: it cannot prove that a `WebView`
 * stays intact, which only a device shows (RELEASING.md, "Systembars device check").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 25, 26, 35])
class BarStripReaderSelectionTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun selectedReader(): BarStripReader {
        var selected: BarStripReader? = null
        composeTestRule.setContent { selected = rememberBarStripReader() }
        composeTestRule.waitForIdle()
        return assertNotNull(selected, "Android always has a reader")
    }

    @Test
    fun `the reader follows the API level`() {
        val reader: BarStripReader = selectedReader()
        val sdk: Int = Build.VERSION.SDK_INT

        if (sdk < Build.VERSION_CODES.O) {
            assertTrue(reader is WebViewGuardedBarStripReader, "API $sdk has no window copy: the guarded layer reader, was $reader")
        } else {
            assertTrue(reader is PixelCopyBarStripReader, "API $sdk reads the window with PixelCopy, was $reader")
        }
    }

    @Test
    fun `a recomposition keeps the same reader`() {
        var generation: Int by mutableIntStateOf(0)
        val seen: MutableList<BarStripReader?> = mutableListOf()
        composeTestRule.setContent {
            generation.let { }
            seen += rememberBarStripReader()
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { generation++ }
        composeTestRule.waitForIdle()

        assertTrue(seen.size >= 2, "control: the composable ran again, ran ${seen.size} time(s)")
        assertTrue(seen.all { it === seen.first() }, "a new reader would restart the sampler on every recomposition")
    }
}
