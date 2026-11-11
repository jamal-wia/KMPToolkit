package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `03-guide.md`: "A post counts as in flight from the moment `post` is called, including while it
 * still waits for the permission check." The in-flight claim of the iOS notifier must be gone on
 * every way out that happens before the notification centre is reached (it cannot be reached from a
 * unit test): a refusal, a post cancelled while the controller is still answering, and a blank id
 * (which must be rejected before anything is registered, even by a controller that would throw).
 */
class IosNotifierClaimLifecycleAdversarialTest {

    private val notification = LocalNotification(
        title = "T",
        body = "B",
        channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
    )

    private class ScriptedController(private val onCheck: suspend () -> PermissionStatus) : PermissionController {
        override suspend fun check(permission: Permission): PermissionStatus = onCheck()
        override suspend fun request(permission: Permission): PermissionStatus = onCheck()
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { onCheck() }
        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Denied())
        override fun openSettings(permission: Permission): Boolean = false
    }

    @Test
    fun `a refused post leaves no claim behind`() {
        val coalescer = ProgressCoalescer()
        val notifier = IosNotifier(ScriptedController { PermissionStatus.Denied() }, coalescer)

        val result: NotificationResult = runBlocking { withTimeout(TIMEOUT_MILLIS) { notifier.post("a", notification) } }

        assertEquals(NotificationResult.PermissionDenied, result)
        assertEquals(0, coalescer.claimCountForTest())
    }

    @Test
    fun `a post cancelled while the controller is still answering releases its claim and rethrows the cancellation`() {
        val coalescer = ProgressCoalescer()
        val gate: CompletableDeferred<PermissionStatus> = CompletableDeferred()
        val notifier = IosNotifier(ScriptedController { gate.await() }, coalescer)

        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                val post = async(start = CoroutineStart.UNDISPATCHED) { notifier.post("a", notification) }
                yield()
                assertEquals(1, coalescer.claimCountForTest())
                post.cancelAndJoin()
                assertTrue(post.isCancelled)
                assertFailsWith<CancellationException> { post.await() }
            }
        }
        assertEquals(0, coalescer.claimCountForTest())
    }

    @Test
    fun `a blank id is rejected before a claim exists and before the controller is asked`() {
        val coalescer = ProgressCoalescer()
        var asked = 0
        val notifier = IosNotifier(ScriptedController { asked++; throw IllegalStateException("must not be asked") }, coalescer)

        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                assertFailsWith<IllegalArgumentException> { notifier.post("", notification) }
                assertFailsWith<IllegalArgumentException> { notifier.post("  ", notification) }
            }
        }
        assertEquals(0, asked)
        assertEquals(0, coalescer.claimCountForTest())
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 10_000L
    }
}
