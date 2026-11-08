package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * The whole Android notifier against a reference written from the published rules alone (docs 04
 * "Coalescing" and "Ordering"), single threaded on a virtual clock: look-alike and foreground ids,
 * process restarts (a fresh notifier, empty bookkeeping, tray kept), cancel, cancelAll, refused notify,
 * denied permission, notifications switched off, content changes, clamped percentages, terminal frames.
 * Every result and the whole tray are compared after every step.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 29, 32, 34], shadows = [AndroidNotifierDifferentialAdversarialTest.RefusingShadowNotificationManager::class])
class AndroidNotifierDifferentialAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "diff", name = "Diff")

    private enum class Mood { NORMAL, REFUSE, PERMISSION_DENIED, DISABLED }

    private class Shown(val bucket: Int?, val content: String, val at: Duration, val title: String, val progress: Int?)

    private fun label(id: String): String = if (id.all { it.code in 32..126 && it != '?' }) id else id.map { it.code.toString(16) }.joinToString("+", "u")

    private fun bucketOf(percent: Int, bucketPercent: Int): Int = (percent.coerceIn(0, 100) / bucketPercent) * bucketPercent

    private fun run(seed: Int): String? {
        val random = Random(seed)
        val bucketPercent: Int = listOf(1, 10, 25)[random.nextInt(3)]
        val minInterval: Duration = listOf(Duration.ZERO, 500.milliseconds, 3.seconds)[random.nextInt(3)]
        val clock = TestTimeSource()
        var elapsed: Duration = Duration.ZERO
        val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
        val foreground: Set<ForegroundNotificationId> = setOf(ForegroundNotificationId("svc", 1001))
        var coalescer: ProgressCoalescer = ProgressCoalescer(bucketPercent, minInterval, clock)
        fun dump(id: String): String = listOf("posted", "latest", "lastShown", "inFlight").joinToString { name ->
            val field = ProgressCoalescer::class.java.getDeclaredField(name).also { it.isAccessible = true }
            val value = (field.get(coalescer) as Map<*, *>)[id]
            "$name=" + when (value) {
                null -> "-"
                is ProgressCoalescer.Post -> "Post(bucket=${value.bucket},shown=${value.shown},refused=${value.refused})"
                is List<*> -> "list(${value.size})"
                else -> "ticket"
            }
        }
        fun newNotifier(): Notifier {
            coalescer = ProgressCoalescer(bucketPercent, minInterval, clock)
            return AndroidNotifier(context, permissions, NotificationRenderer(context, "test.ACTION"), coalescer, foreground)
        }
        shadowOf(manager).setNotificationsEnabled(true)
        manager.cancelAll()
        RefusingShadowNotificationManager.refusing.set(false)
        var notifier: Notifier = newNotifier()
        val ids: List<String> = listOf("a", "\uD800", "?", "%uD800", "svc")
        // The reference: what the coalescing baseline of each id is, and what the tray holds.
        val baseline: MutableMap<String, Shown?> = mutableMapOf()
        val unknownBaseline: MutableSet<String> = mutableSetOf() // after a refused terminal frame the rules are silent
        val tray: MutableMap<String, Shown> = mutableMapOf()
        val log = StringBuilder("bucket=$bucketPercent min=$minInterval: ")

        fun trayOf(id: String): Notification? =
            if (id == "svc") shadowOf(manager).getNotification(null, 1001) else shadowOf(manager).getNotification(id, 1)

        repeat(60) { step ->
            if (random.nextInt(3) != 0) { val advance: Duration = random.nextInt(0, 1_500).milliseconds; clock += advance; elapsed += advance }
            val id: String = ids[random.nextInt(ids.size)]
            when (val dice: Int = random.nextInt(100)) {
                in 0..4 -> { log.append("cancel(${label(id)}) "); notifier.cancel(id); baseline.remove(id); unknownBaseline.remove(id); tray.remove(id) }
                in 5..6 -> { log.append("cancelAll "); notifier.cancelAll(); baseline.clear(); unknownBaseline.clear(); tray.clear() }
                in 7..9 -> { log.append("RESTART "); notifier = newNotifier(); baseline.clear(); unknownBaseline.clear() }
                else -> {
                    val rawMood: Mood = when (random.nextInt(20)) { 0, 1 -> Mood.REFUSE; 2 -> Mood.PERMISSION_DENIED; 3 -> Mood.DISABLED; else -> Mood.NORMAL }
                    // Below API 33 there is no runtime permission to lack: the answer of the controller is ignored.
                    val mood: Mood = if (rawMood == Mood.PERMISSION_DENIED && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Mood.NORMAL else rawMood
                    val raw: Int? = listOf(null, -5, 0, 3, 9, 10, 11, 24, 25, 49, 50, 99, 100, 140)[random.nextInt(14)]
                    val indeterminate: Boolean = raw == null && random.nextBoolean()
                    val progress: NotificationProgress? = when {
                        raw != null -> NotificationProgress.Determinate(raw)
                        indeterminate -> NotificationProgress.Indeterminate
                        else -> null
                    }
                    val title: String = listOf("t1", "t2")[random.nextInt(2)]
                    val alertOnce: Boolean = random.nextInt(4) != 0
                    val content = "$title/$alertOnce"
                    val determinate: Boolean = raw != null && raw.coerceIn(0, 100) < 100
                    val frameBucket: Int? = if (determinate) bucketOf(raw!!, bucketPercent) else null
                    permissions.setStatus(
                        CommonPermission.Notifications,
                        if (rawMood == Mood.PERMISSION_DENIED) PermissionStatus.PermanentlyDenied else PermissionStatus.Granted,
                    )
                    shadowOf(manager).setNotificationsEnabled(mood != Mood.DISABLED)
                    RefusingShadowNotificationManager.refusing.set(mood == Mood.REFUSE)
                    log.append("post(${label(id)},$raw/${if (indeterminate) "indet" else "-"},$title,alert=$alertOnce,$mood,+$elapsed) ")

                    val before: String = dump(id)
                    val result: NotificationResult = runBlocking {
                        notifier.post(
                            id,
                            LocalNotification(title = title, body = "b", channel = channel, progress = progress),
                            NotificationOptions(alertOnce = alertOnce),
                        )
                    }
                    RefusingShadowNotificationManager.refusing.set(false)
                    shadowOf(manager).setNotificationsEnabled(true)
                    log.append("=>${result::class.simpleName} ")

                    val now: Duration = elapsed
                    val slackBefore: Boolean = id in unknownBaseline
                    // after a refused terminal frame the rules are silent: the id may count as having no baseline or
                    // as still holding what the tray shows (which is what the coalescer does when a later frame is refused)
                    val previous: Shown? = if (slackBefore) tray[id]?.takeIf { it.bucket != null } else baseline[id]
                    val wouldCoalesce: Boolean = determinate && previous != null && previous.bucket != null && (
                        (now - previous.at) < minInterval || (previous.bucket == frameBucket && previous.content == content)
                        )
                    val slack: Boolean = id in unknownBaseline
                    log.append("[prev=${previous?.bucket}@${previous?.at} wc=$wouldCoalesce slack=$slack] ")
                    val expectation: String = when (mood) {
                        Mood.PERMISSION_DENIED -> if (result == NotificationResult.PermissionDenied) "" else "expected PermissionDenied"
                        Mood.DISABLED -> if (result == NotificationResult.NotificationsDisabled) "" else "expected NotificationsDisabled"
                        Mood.REFUSE -> when {
                            wouldCoalesce && result == NotificationResult.Coalesced -> ""
                            wouldCoalesce && slack -> if (result is NotificationResult.Failed) "" else "expected Coalesced or Failed"
                            wouldCoalesce -> "expected Coalesced"
                            result is NotificationResult.Failed -> ""
                            slack && result == NotificationResult.Coalesced -> ""
                            else -> "expected Failed"
                        }
                        Mood.NORMAL -> when {
                            wouldCoalesce && result == NotificationResult.Coalesced -> ""
                            wouldCoalesce && slack && result == NotificationResult.Posted -> ""
                            wouldCoalesce -> "expected Coalesced"
                            result == NotificationResult.Posted -> ""
                            else -> "expected Posted"
                        }
                    }
                    if (expectation.isNotEmpty()) return "seed $seed step $step: $expectation, got $result; state before: $before; baseline=${previous?.let { "${it.bucket}@${it.at}/${it.content}" }} unknown=$slack; $log"
                    val gated: Boolean = mood == Mood.PERMISSION_DENIED || mood == Mood.DISABLED
                    val accepted: Boolean = result == NotificationResult.Posted
                    if (gated) {
                        // the gates run before coalescing: no state of any kind may change
                    } else if (accepted) {
                        val frame = Shown(frameBucket, content, now, title, raw?.coerceIn(0, 100))
                        tray[id] = frame
                        baseline[id] = if (determinate) frame else null
                        unknownBaseline.remove(id)
                    } else if (result is NotificationResult.Failed && !determinate) {
                        // a refused terminal frame: the id's baseline is no longer decided by the rules
                        if (previous != null) unknownBaseline += id
                        baseline.remove(id)
                    }
                }
            }
            check(tray, ::trayOf, ids, step, seed, log)?.let { return it }
        }
        return null
    }

    private fun check(
        tray: Map<String, Shown>,
        trayOf: (String) -> Notification?,
        ids: List<String>,
        step: Int,
        seed: Int,
        log: StringBuilder,
    ): String? {
        for (id in ids) {
            val shown: Notification? = trayOf(id)
            val expected: Shown? = tray[id]
            if (expected == null && shown != null) return "seed $seed step $step: '$id' is on the tray but should not be; $log"
            if (expected != null && shown == null) return "seed $seed step $step: '$id' is missing from the tray; $log"
            if (expected != null && shown != null) {
                val title: String = shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
                val max: Int = shown.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
                val progress: Int? = if (max > 0 && !shown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) shown.extras.getInt(Notification.EXTRA_PROGRESS) else null
                if (title != expected.title) return "seed $seed step $step: '$id' shows title $title, expected ${expected.title}; $log"
                if (progress != expected.progress) return "seed $seed step $step: '$id' shows progress $progress, expected ${expected.progress}; $log"
            }
        }
        return null
    }

    @Test
    fun `results and tray follow the published coalescing rules over random lifecycles`() {
        val failures: List<String> = (0 until 1500).mapNotNull { run(it) }
        assertEquals(emptyList(), failures.take(3), "${failures.size} of 1500 seeds diverged")
    }

    /** While [refusing], `notify` throws. */
    @Implements(NotificationManager::class)
    class RefusingShadowNotificationManager : ShadowNotificationManager() {

        @Implementation
        public override fun notify(tag: String?, id: Int, notification: Notification?) {
            if (refusing.get()) throw SecurityException("notify refused")
            super.notify(tag, id, notification)
        }

        companion object {
            val refusing: AtomicBoolean = AtomicBoolean(false)
        }
    }
}
