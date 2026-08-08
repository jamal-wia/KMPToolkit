package io.github.jamal_wia.kmptoolkit.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.runner.RunWith

/**
 * The Android wake configuration: its validation, and the identifier it derives when you do not
 * name one.
 *
 * The scheduler's interaction with WorkManager itself is not asserted here — that needs
 * `androidx.work:work-testing` and a real initialized `WorkManager`, which is an instrumentation
 * concern rather than a unit-test one. What is asserted is everything a consumer can get wrong:
 * the derived unique-work name, the bounds, and that a failure to schedule cannot escape into their
 * enqueue.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerWakeConfigTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the work name defaults to the application id plus a suffix`() {
        val resolved: String = WorkManagerWakeConfig().resolveWorkName("com.example.app")

        assertEquals("com.example.app.outbox.wake", resolved)
    }

    @Test
    fun `two applications derive different work names`() {
        val config = WorkManagerWakeConfig()

        assertTrue(
            config.resolveWorkName("com.example.one") != config.resolveWorkName("com.example.two"),
            "a hardcoded name would collide in WorkManager's global namespace",
        )
    }

    @Test
    fun `an explicit work name wins over the derived one`() {
        val resolved: String =
            WorkManagerWakeConfig(uniqueWorkName = "my_queue").resolveWorkName("com.example.app")

        assertEquals("my_queue", resolved)
    }

    @Test
    fun `a blank work name is rejected`() {
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(uniqueWorkName = "") }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(uniqueWorkName = "  ") }
    }

    @Test
    fun `a backoff below WorkManager's floor is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            WorkManagerWakeConfig(initialBackoff = 9.seconds)
        }
        WorkManagerWakeConfig(initialBackoff = 10.seconds) // the floor itself is fine
    }

    @Test
    fun `non-positive durations are rejected`() {
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(drainBudget = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(engineWait = Duration.ZERO) }
    }

    @Test
    fun `the defaults are the documented ones`() {
        val config = WorkManagerWakeConfig()

        assertEquals(30.seconds, config.initialBackoff)
        assertEquals(1.minutes, config.drainBudget)
        assertEquals(5.seconds, config.engineWait)
        assertTrue(config.requiresNetwork)
    }

    @Test
    fun `scheduling with an uninitialized WorkManager does not throw into the caller`() {
        // Robolectric gives no initialized WorkManager, so getInstance throws. That is exactly the
        // shape of a real degradation (a process with no provider), and the contract says a wake
        // that cannot be armed must not break the enqueue that asked for it.
        val scheduler = createWorkManagerWakeScheduler(context)

        scheduler.scheduleWake()
        scheduler.cancelWake()
    }
}
