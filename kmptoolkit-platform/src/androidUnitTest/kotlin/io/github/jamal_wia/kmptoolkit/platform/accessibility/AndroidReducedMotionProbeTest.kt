package io.github.jamal_wia.kmptoolkit.platform.accessibility

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidReducedMotionProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val probe: ReducedMotionProbe = createReducedMotionProbe(context)

    private fun setAnimatorDurationScale(scale: Float) {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            scale,
        )
    }

    @Test
    fun `reports reduced motion when the animation scale is zero`() {
        setAnimatorDurationScale(0f)

        assertTrue(probe.isReducedMotionEnabled())
    }

    @Test
    fun `reports normal motion at the default scale`() {
        setAnimatorDurationScale(1f)

        assertFalse(probe.isReducedMotionEnabled())
    }

    @Test
    fun `a slowed-down scale is not reduced motion`() {
        // The developer-options 0.5x and 10x settings write the same global; only zero means the
        // user asked for animations to be removed.
        setAnimatorDurationScale(0.5f)

        assertFalse(probe.isReducedMotionEnabled())
    }

    @Test
    fun `assumes motion is fine when the setting has never been written`() {
        assertFalse(
            createReducedMotionProbe(context).isReducedMotionEnabled(),
            "an unset value must fail open, not disable animation for everyone",
        )
    }

    @Test
    fun `reads the setting live rather than caching it`() {
        setAnimatorDurationScale(1f)
        assertFalse(probe.isReducedMotionEnabled())

        setAnimatorDurationScale(0f)

        assertTrue(
            probe.isReducedMotionEnabled(),
            "the user can change the setting while the app is running",
        )
    }
}
