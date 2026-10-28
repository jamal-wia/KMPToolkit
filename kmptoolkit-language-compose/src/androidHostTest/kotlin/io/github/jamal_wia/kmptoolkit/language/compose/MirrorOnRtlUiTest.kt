package io.github.jamal_wia.kmptoolkit.language.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class MirrorOnRtlUiTest {

    @Test
    fun `mirrorOnRtl leaves the modifier unchanged under Ltr`() = runComposeUiTest {
        var result: Modifier? = null
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                result = Modifier.mirrorOnRtl()
            }
        }
        waitForIdle()

        assertSame(Modifier, result)
    }

    @Test
    fun `mirrorOnRtl appends a flip under Rtl`() = runComposeUiTest {
        var result: Modifier? = null
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                result = Modifier.mirrorOnRtl()
            }
        }
        waitForIdle()

        assertNotEquals<Modifier>(Modifier, result!!)
    }

    @Test
    fun `mirrorOnLtr appends a flip under Ltr`() = runComposeUiTest {
        var result: Modifier? = null
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                result = Modifier.mirrorOnLtr()
            }
        }
        waitForIdle()

        assertNotEquals<Modifier>(Modifier, result!!)
    }

    @Test
    fun `mirrorOnLtr leaves the modifier unchanged under Rtl`() = runComposeUiTest {
        var result: Modifier? = null
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                result = Modifier.mirrorOnLtr()
            }
        }
        waitForIdle()

        assertSame(Modifier, result)
    }
}
