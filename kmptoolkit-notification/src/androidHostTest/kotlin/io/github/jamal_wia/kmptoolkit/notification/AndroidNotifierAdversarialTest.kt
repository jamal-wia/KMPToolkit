package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Races, process death and failure churn of the Android notifier against a real `NotificationManager`.
 *
 * Every wait is bounded: a parked build gives up after a few seconds and every thread is joined with
 * a timeout, so a deadlock fails the test instead of the Gradle executor.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val shown: List<Notification>
        get() = shadowOf(manager).allNotifications

    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)

    private val channel = NotificationChannelSpec(id = "adversarial", name = "Adversarial")

    private fun frame(title: String, percent: Int? = null): LocalNotification = LocalNotification(
        title = title,
        body = "body",
        channel = channel,
        progress = percent?.let { NotificationProgress.Determinate(it) },
    )

    private fun titles(): List<String> =
        shown.map { notification: Notification -> notification.extras.getString(Notification.EXTRA_TITLE).orEmpty() }.sorted()

    private fun notifier(
        renderer: NotificationRenderer = NotificationRenderer(context, "test.ACTION"),
        coalescer: ProgressCoalescer = ProgressCoalescer(minInterval = Duration.ZERO),
        foregroundIds: Set<ForegroundNotificationId> = emptySet(),
    ): AndroidNotifier = AndroidNotifier(context, permissions, renderer, coalescer, foregroundIds)

    private fun post(notifier: Notifier, id: String, notification: LocalNotification): NotificationResult =
        runBlocking { notifier.post(id, notification) }

    /** A renderer that runs a per-title behaviour inside every build, then renders normally. */
    private class ScriptedRenderer(context: Context) : NotificationRenderer(context, "test.ACTION") {
        val behaviours: MutableMap<String, () -> Unit> = ConcurrentHashMap()

        override fun build(
            id: String,
            notification: LocalNotification,
            options: NotificationOptions,
            iconResId: Int,
        ): Notification {
            behaviours[notification.title]?.invoke()
            return super.build(id, notification, options, iconResId)
        }
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        /** Parks the calling build until [release] is counted down, or fails after five seconds. */
        fun park() {
            entered.countDown()
            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the gate was never released" }
        }
    }

    private fun postOnThread(notifier: Notifier, id: String, notification: LocalNotification): Pair<Thread, AtomicReference<Any?>> {
        val outcome: AtomicReference<Any?> = AtomicReference(null)
        val worker: Thread = thread(name = "kmptoolkit-adversarial-post") {
            outcome.set(runCatching { post(notifier, id, notification) }.let { it.getOrNull() ?: it.exceptionOrNull() })
        }
        return worker to outcome
    }

    private fun Thread.joinBounded() {
        join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertTrue(!isAlive, "thread $name is stuck:\n" + threadDump())
    }

    private fun threadDump(): String = Thread.getAllStackTraces().entries
        .filter { (thread: Thread, _) -> thread.name.startsWith("kmptoolkit-") || thread.name == "main" }
        .joinToString("\n") { (thread: Thread, stack: Array<StackTraceElement>) -> "${thread.name}: ${stack.take(8).joinToString(" <- ")}" }

    // --- cancel against a post at every stage -------------------------------------------------

    @Test
    fun `a cancel, a cancelAll or a foreground cancel landing mid-build is never followed by the notification`() {
        data class Stage(val name: String, val id: String, val progress: Int?, val foreground: Boolean, val cancel: (Notifier) -> Unit)
        val stages: List<Stage> = listOf(
            Stage("cancel of a progress frame", "d", 40, false) { it.cancel("d") },
            Stage("cancel of a terminal frame", "d", null, false) { it.cancel("d") },
            Stage("cancelAll of a progress frame", "d", 40, false) { it.cancelAll() },
            Stage("cancelAll of a terminal frame", "d", null, false) { it.cancelAll() },
            Stage("cancel of a foreground id", "fg", null, true) { it.cancel("fg") },
            Stage("cancel of a foreground progress frame", "fg", 30, true) { it.cancel("fg") },
        )
        stages.forEach { stage: Stage ->
            val renderer = ScriptedRenderer(context)
            val coalescer = ProgressCoalescer(minInterval = Duration.ZERO)
            val foreground: Set<ForegroundNotificationId> =
                if (stage.foreground) setOf(ForegroundNotificationId("fg", 77)) else emptySet()
            val notifier: AndroidNotifier = notifier(renderer, coalescer, foreground)
            renderer.behaviours["victim"] = { stage.cancel(notifier) }

            val result: NotificationResult = post(notifier, stage.id, frame("victim", stage.progress))

            assertEquals(NotificationResult.Coalesced, result, stage.name)
            assertEquals(emptyList(), titles(), "${stage.name}: the notification came back after the cancel returned")
            assertEquals(0, coalescer.latestCountForTest(), "${stage.name}: newest-serial entry left behind")
            assertEquals(0, coalescer.postedCountForTest(), "${stage.name}: baseline left behind")
            assertEquals(NotificationResult.Posted, post(notifier, stage.id, frame("after", stage.progress)), "${stage.name}: a fresh post after it")
            notifier.cancelAll()
        }
    }

    @Test
    fun `a cancel landing while a newer frame is built takes the older notification and the newer frame with it`() {
        val renderer = ScriptedRenderer(context)
        val notifier: AndroidNotifier = notifier(renderer)
        assertEquals(NotificationResult.Posted, post(notifier, "d", frame("p10", 10)))
        renderer.behaviours["p50"] = { notifier.cancel("d") }

        assertEquals(NotificationResult.Coalesced, post(notifier, "d", frame("p50", 50)))

        assertEquals(emptyList(), titles())
    }

    @Test
    fun `two hundred rounds of a post racing a cancel end with an empty tray after the final cancelAll`() {
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val notifier: AndroidNotifier = notifier(coalescer = coalescer)
        val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        repeat(ROUNDS) { round: Int ->
            val random = Random(SEED + round)
            val barrier = CyclicBarrier(2)
            val poster: Thread = thread(name = "kmptoolkit-adversarial-poster") {
                runCatching {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    listOf(10, 40, 70, 100).forEach { percent: Int ->
                        val result: NotificationResult = post(notifier, "race", frame("r$round-$percent", percent))
                        if (result !is NotificationResult.Posted && result !is NotificationResult.Coalesced) {
                            failures += "round=$round seed=${SEED + round}: $result"
                        }
                    }
                }.onFailure { failures += "round=$round poster: $it" }
            }
            val canceller: Thread = thread(name = "kmptoolkit-adversarial-canceller") {
                runCatching {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    repeat(random.nextInt(1, 4)) {
                        notifier.cancel("race")
                        Thread.yield()
                    }
                }.onFailure { failures += "round=$round canceller: $it" }
            }
            poster.joinBounded()
            canceller.joinBounded()
            assertTrue(shown.size <= 1, "round=$round: ${shown.size} notifications for one id")
        }

        notifier.cancelAll()

        assertEquals(emptyList(), failures.toList())
        assertEquals(emptyList(), titles(), "the tray is not empty after the final cancelAll")
        assertEquals(0, coalescer.postedCountForTest())
        assertEquals(0, coalescer.latestCountForTest())
    }

    // --- stale frames --------------------------------------------------------------------------

    @Test
    fun `an older frame parked on another thread is dropped once a newer frame was shown`() {
        val renderer = ScriptedRenderer(context)
        val notifier: AndroidNotifier = notifier(renderer)
        val gate = Gate()
        renderer.behaviours["old"] = { gate.park() }
        val (worker: Thread, outcome: AtomicReference<Any?>) = postOnThread(notifier, "d", frame("old", 10))
        assertTrue(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the old frame never reached its build")

        assertEquals(NotificationResult.Posted, post(notifier, "d", frame("new", 50)))
        gate.release.countDown()
        worker.joinBounded()

        assertEquals(NotificationResult.Coalesced, outcome.get())
        assertEquals(listOf("new"), titles(), "an older frame is the one shown last")
    }

    @Test
    fun `a progress frame parked on another thread never lands on top of a terminal frame`() {
        val renderer = ScriptedRenderer(context)
        val notifier: AndroidNotifier = notifier(renderer)
        val gate = Gate()
        renderer.behaviours["progress"] = { gate.park() }
        val (worker: Thread, outcome: AtomicReference<Any?>) = postOnThread(notifier, "d", frame("progress", 90))
        assertTrue(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))

        assertEquals(NotificationResult.Posted, post(notifier, "d", frame("done")))
        gate.release.countDown()
        worker.joinBounded()

        assertEquals(NotificationResult.Coalesced, outcome.get())
        assertEquals(listOf("done"), titles())
    }

    // --- process death -------------------------------------------------------------------------

    @Test
    fun `a notifier created after process death cancels and updates what the dead one posted`() {
        val dead: Notifier = notifier()
        val collidingA = "Aa"
        val collidingB = "BB"
        assertEquals(collidingA.hashCode(), collidingB.hashCode())
        post(dead, "a", frame("dead-a", 40))
        post(dead, collidingA, frame("dead-Aa"))
        post(dead, collidingB, frame("dead-BB"))
        val reborn: Notifier = notifier()

        // The same progress as the dead instance's last frame: nothing shown yet by this instance.
        assertEquals(NotificationResult.Posted, post(reborn, "a", frame("reborn-a", 40)), "the first frame of the new instance was suppressed")
        assertEquals(listOf("dead-Aa", "dead-BB", "reborn-a"), titles(), "the update must replace, not stack")
        reborn.cancel(collidingA)
        assertEquals(listOf("dead-BB", "reborn-a"), titles(), "cancelling one of a colliding pair removed the wrong one")
        reborn.cancel("a")
        reborn.cancel(collidingB)
        assertEquals(emptyList(), titles())
    }

    @Test
    fun `a notification the legacy 2x library left untagged is swept by a post and by a cancel of a new instance`() {
        val renderer = NotificationRenderer(context, "test.ACTION")
        val iconResId: Int = renderer.smallIconRes(NotificationIcon.Default)
        fun postLegacy(id: String, title: String) {
            val built: Notification = renderer.build(id, frame(title), NotificationOptions.DEFAULT, iconResId)
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(legacyPlatformNotificationId(id), built)
        }
        val reborn: Notifier = notifier()

        postLegacy("a", "legacy-a")
        postLegacy("b", "legacy-b")
        assertEquals(listOf("legacy-a", "legacy-b"), titles())
        post(reborn, "a", frame("new-a"))
        assertEquals(listOf("legacy-b", "new-a"), titles(), "the post must sweep exactly its own legacy twin")
        reborn.cancel("b")
        assertEquals(listOf("new-a"), titles(), "the cancel must reach the legacy notification")
        reborn.cancel("a")
        assertEquals(emptyList(), titles())
    }

    // --- failure churn and bounds --------------------------------------------------------------

    @Test
    fun `hundreds of posts whose build throws leave neither a baseline nor a newest serial behind`() {
        val renderer = ScriptedRenderer(context)
        val coalescer = ProgressCoalescer(minInterval = Duration.ZERO)
        val notifier: AndroidNotifier = notifier(renderer, coalescer)
        repeat(FAILING_IDS) { index: Int ->
            val title = "boom-$index"
            renderer.behaviours[title] = {
                when (index % 3) {
                    0 -> throw IllegalStateException("framework refused")
                    1 -> throw CancellationException("cancelled mid-build")
                    else -> throw AssertionError("an Error, not an Exception")
                }
            }
            val progress: Int? = if (index % 2 == 0) 10 + index % 80 else null
            when (index % 3) {
                0 -> assertIs<NotificationResult.Failed>(post(notifier, "id-$index", frame(title, progress)))
                1 -> assertFailsWith<CancellationException> { post(notifier, "id-$index", frame(title, progress)) }
                else -> assertFailsWith<AssertionError> { post(notifier, "id-$index", frame(title, progress)) }
            }
        }

        assertEquals(0, coalescer.postedCountForTest(), "baselines left behind by failed posts")
        assertEquals(0, coalescer.latestCountForTest(), "newest serials left behind by failed posts")
        assertEquals(emptyList(), titles())
    }

    @Test
    fun `a post cancelled mid-build can be retried with the same progress`() {
        val renderer = ScriptedRenderer(context)
        val notifier: AndroidNotifier = notifier(renderer)
        renderer.behaviours["once"] = {
            renderer.behaviours.remove("once")
            throw CancellationException("cancelled mid-build")
        }

        assertFailsWith<CancellationException> { post(notifier, "d", frame("once", 40)) }

        assertEquals(NotificationResult.Posted, post(notifier, "d", frame("once", 40)))
        assertEquals(listOf("once"), titles())
    }

    @Test
    fun `the swept legacy cache stays bounded for a fresh id per message and cancelAll empties it`() {
        val notifier: AndroidNotifier = notifier()
        repeat(SWEEP_IDS) { index: Int -> post(notifier, "message-$index", frame("m$index")) }

        assertTrue(notifier.sweptLegacyCountForTest() <= 256, "swept cache holds ${notifier.sweptLegacyCountForTest()} ids")
        notifier.cancelAll()
        assertEquals(0, notifier.sweptLegacyCountForTest())
        assertEquals(emptyList(), titles())
    }

    @Test
    fun `cancelling an id that was never posted, or twice, is inert and leaves the others alone`() {
        val notifier: AndroidNotifier = notifier()
        post(notifier, "keep", frame("keep"))

        repeat(50) { notifier.cancel("ghost") }
        notifier.cancel("keep")
        notifier.cancel("keep")

        assertEquals(emptyList(), titles())
        assertEquals(NotificationResult.Posted, post(notifier, "keep", frame("again")))
    }

    private companion object {
        const val WAIT_SECONDS: Long = 5L
        const val ROUNDS: Int = 200
        const val SEED: Int = 20_261_107
        const val FAILING_IDS: Int = 300
        const val SWEEP_IDS: Int = 600
    }
}
