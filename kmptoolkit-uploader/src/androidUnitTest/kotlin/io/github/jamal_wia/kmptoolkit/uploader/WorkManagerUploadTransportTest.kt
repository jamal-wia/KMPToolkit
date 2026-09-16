package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerUploadTransportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `leaseMillis reflects the config`() {
        val transport = createWorkManagerUploadTransport(context, UploadTransportConfig(leaseMillis = 12_345))

        assertEquals(12_345L, transport.leaseMillis)
    }

    @Test
    fun `launching and cancelling with an uninitialized WorkManager does not throw into the caller`() {
        // Robolectric gives no initialized WorkManager, so getInstance throws. That is exactly the
        // shape of a real degradation (a process with no provider), and the contract says a launch
        // that could not be enqueued must not break the handler that asked for it.
        val transport = createWorkManagerUploadTransport(context)
        val request = UploadRequest(url = "https://example.com", fields = emptyList())

        transport.launch("item-1", request)
        transport.cancelAll()
    }

    @Test
    fun `creating a transport registers its classify function for the worker to find`() = runTest {
        val classify: (UploadResult) -> SettleResult = { SettleResult.Park("test") }

        createWorkManagerUploadTransport(context, classify = classify)

        assertSame(classify, UploadClassifierRegistry.await(1_000))
    }

    @Test
    fun `creating a second transport replaces the registered classify`() = runTest {
        createWorkManagerUploadTransport(context, classify = { SettleResult.Delivered })
        val second: (UploadResult) -> SettleResult = { SettleResult.Drop("test") }

        createWorkManagerUploadTransport(context, classify = second)

        assertSame(second, UploadClassifierRegistry.await(1_000))
    }
}
