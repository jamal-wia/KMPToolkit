package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A seeded operation-sequence fuzzer against a plain reference model of the documented coalescing
 * contract (docs 03 and 04): post (with a changing title, button set, tap-target extras and progress),
 * cancel, cancelAll, a restart (a second notifier over the same system tray), the permission and the
 * app-wide switch flipping, and the clock moving by exact multiples around the rate limit.
 *
 * The model knows nothing of tickets, claims or locks: it says what the user must be shown and which
 * answer each post must get. After every step the tray must equal the model, so a leaked, missing or
 * stale notification, a wrong answer, or a channel written twice is caught at the step that caused it.
 * The seed is in every failure message.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotifierContractModelFuzzAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val foreground = ForegroundNotificationId("fg", 77)

    @Before
    fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    /** What the user sees of one notification; the model and the tray are compared as these. */
    private data class Shown(
        val title: String,
        val body: String,
        val buttons: List<String>,
        val progress: Int?,
        val indeterminate: Boolean,
    )

    private data class Baseline(val bucket: Int, val content: Any, val at: Duration)

    private class Frame(
        val title: String,
        val body: String,
        val buttons: List<NotificationAction>,
        val extras: Map<String, String>?,
        val progress: NotificationProgress?,
        val channel: NotificationChannelSpec,
    )

    private fun newNotifier(clock: TestTimeSource): Notifier = AndroidNotifier(
        context = context,
        permissions = permissions,
        renderer = NotificationRenderer(context, "test.fuzz.ACTION"),
        coalescer = ProgressCoalescer(bucketPercent = BUCKET, minInterval = MIN_INTERVAL, timeSource = clock),
        foregroundIds = setOf(foreground),
    )

    private fun onTray(id: String): Notification? =
        if (id == "fg") shadowOf(manager).getNotification(null, 77) else shadowOf(manager).getNotification(id, 1)

    private fun read(n: Notification): Shown = Shown(
        title = n.extras.getString(Notification.EXTRA_TITLE).orEmpty(),
        body = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
        buttons = n.actions.orEmpty().map { it.title.toString() },
        progress = if (n.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 && !n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) {
            n.extras.getInt(Notification.EXTRA_PROGRESS)
        } else {
            null
        },
        indeterminate = n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
    )

    @Test
    fun `no operation sequence makes the tray or an answer disagree with the documented contract`() {
        repeat(SEEDS) { seed: Int -> runSeed(seed) }
    }

    private fun runSeed(seed: Int) {
        val random = Random(seed)
        manager.cancelAll()
        manager.notificationChannels.toList().forEach { manager.deleteNotificationChannel(it.id) }
        shadowOf(manager).setNotificationsEnabled(true)
        permissions.setStatus(io.github.jamal_wia.kmptoolkit.permission.CommonPermission.Notifications, PermissionStatus.Granted)
        val clock = TestTimeSource()
        var notifier: Notifier = newNotifier(clock)
        val ids: List<String> = listOf("a", "b", "fg")
        val specs: List<NotificationChannelSpec> = listOf(
            NotificationChannelSpec("c0", "C0"),
            NotificationChannelSpec("c1", "C1", importance = NotificationImportance.High),
        )
        val tray: MutableMap<String, Shown> = mutableMapOf()
        val baselines: MutableMap<String, Baseline> = mutableMapOf()
        val usedChannels: MutableSet<String> = mutableSetOf()
        var now: Duration = Duration.ZERO
        var granted = true
        var enabled = true
        val log = StringBuilder()

        repeat(STEPS) { step: Int ->
            fun fail(what: String): Nothing = error("seed $seed step $step: $what. Steps: $log")
            when (random.nextInt(14)) {
                0 -> {
                    val id: String = ids.random(random)
                    notifier.cancel(id)
                    tray.remove(id)
                    baselines.remove(id)
                    log.append("cancel($id); ")
                }

                1 -> if (random.nextInt(3) == 0) {
                    notifier.cancelAll()
                    tray.clear()
                    baselines.clear()
                    log.append("cancelAll; ")
                } else {
                    notifier = newNotifier(clock)
                    baselines.clear() // a restarted process forgets what it was holding back (docs 04)
                    log.append("restart; ")
                }

                2 -> {
                    granted = !granted
                    permissions.setStatus(
                        io.github.jamal_wia.kmptoolkit.permission.CommonPermission.Notifications,
                        if (granted) PermissionStatus.Granted else PermissionStatus.Denied(),
                    )
                    log.append("permission=$granted; ")
                }

                3 -> {
                    enabled = !enabled
                    shadowOf(manager).setNotificationsEnabled(enabled)
                    log.append("switch=$enabled; ")
                }

                4, 5 -> {
                    val by: Duration = TICKS.random(random)
                    clock += by
                    now += by
                    log.append("+${by.inWholeMilliseconds}ms; ")
                }

                else -> {
                    val id: String = ids.random(random)
                    val progress: NotificationProgress? = when (random.nextInt(8)) {
                        0 -> null
                        1 -> NotificationProgress.Indeterminate
                        2 -> NotificationProgress.Determinate(PERCENTS_EDGE.random(random))
                        else -> NotificationProgress.Determinate(random.nextInt(0, 101))
                    }
                    val frame = Frame(
                        title = "t${random.nextInt(2)}",
                        body = "b${random.nextInt(2)}",
                        buttons = BUTTONS.random(random),
                        extras = EXTRAS.random(random),
                        progress = progress,
                        channel = specs.random(random),
                    )
                    val result: NotificationResult = runBlocking {
                        notifier.post(
                            id,
                            LocalNotification(
                                title = frame.title, body = frame.body, channel = frame.channel, progress = frame.progress,
                                actions = frame.buttons, contentExtras = frame.extras,
                            ),
                        )
                    }
                    log.append("post($id,${frame.title}/${frame.body}/${frame.buttons.map { it.label }}/${frame.extras},$progress,${frame.channel.id})=${result::class.simpleName}; ")
                    val expected: NotificationResult = when {
                        !granted -> NotificationResult.PermissionDenied
                        !enabled -> NotificationResult.NotificationsDisabled
                        else -> decide(frame, baselines[id], now)
                    }
                    assertEquals(expected, result, "seed $seed step $step: answer differs from the contract. Steps: $log")
                    if (granted && enabled) usedChannels += frame.channel.id
                    if (result == NotificationResult.Posted) {
                        tray[id] = expectedShown(frame)
                        val determinate: Int? = (frame.progress as? NotificationProgress.Determinate)?.percent?.coerceIn(0, 100)
                        if (determinate == null || determinate == 100) {
                            baselines.remove(id)
                        } else {
                            baselines[id] = Baseline((determinate / BUCKET) * BUCKET, key(frame), now)
                        }
                        val extras: Map<String, String>? = frame.extras
                        if (extras != null) {
                            val intent: Intent = shadowOf(requireNotNull(onTray(id)?.contentIntent) { "no tap target at step $step ($log)" }).savedIntent
                            extras.forEach { (k: String, v: String) ->
                                assertEquals(v, intent.getStringExtra(k), "seed $seed step $step: tap target extra '$k'. Steps: $log")
                            }
                        }
                    }
                }
            }
            val actual: Map<String, Shown> = ids.mapNotNull { id: String -> onTray(id)?.let { id to read(it) } }.toMap()
            if (actual != tray) fail("the tray differs from the model.\n  model=$tray\n  tray =$actual")
            val total: Int = shadowOf(manager).allNotifications.size
            if (total != tray.size) fail("$total notifications on the tray, the model holds ${tray.size}")
            val channels: Set<String> = shadowOf(manager).notificationChannels.map { (it as android.app.NotificationChannel).id }.toSet()
            if (channels != usedChannels) fail("channels $channels, the model says $usedChannels")
        }
    }

    private fun key(frame: Frame): Any = listOf(frame.title, frame.body, frame.buttons, frame.extras, frame.channel)

    private fun decide(frame: Frame, baseline: Baseline?, now: Duration): NotificationResult {
        val progress: NotificationProgress? = frame.progress
        if (progress !is NotificationProgress.Determinate) return NotificationResult.Posted
        val percent: Int = progress.percent.coerceIn(0, 100)
        if (percent == 100) return NotificationResult.Posted
        val previous: Baseline = baseline ?: return NotificationResult.Posted
        if (now - previous.at < MIN_INTERVAL) return NotificationResult.Coalesced
        val same: Boolean = previous.bucket == (percent / BUCKET) * BUCKET && previous.content == key(frame)
        return if (same) NotificationResult.Coalesced else NotificationResult.Posted
    }

    private fun expectedShown(frame: Frame): Shown {
        val progress: NotificationProgress? = frame.progress
        return Shown(
            title = frame.title,
            body = frame.body,
            buttons = frame.buttons.map { it.label },
            progress = (progress as? NotificationProgress.Determinate)?.percent?.coerceIn(0, 100),
            indeterminate = progress is NotificationProgress.Indeterminate,
        )
    }

    private companion object {
        const val SEEDS: Int = 120
        const val STEPS: Int = 60
        const val BUCKET: Int = 10
        val MIN_INTERVAL: Duration = 500.milliseconds

        /** Around the rate limit on both sides, exactly on it, and a coarse step. */
        val TICKS: List<Duration> = listOf(0, 1, 499, 500, 501, 2_000).map { it.milliseconds }
        val PERCENTS_EDGE: List<Int> = listOf(-5, 0, 9, 10, 99, 100, 101, 140, Int.MAX_VALUE, Int.MIN_VALUE)
        val BUTTONS: List<List<NotificationAction>> = listOf(
            emptyList(),
            listOf(NotificationAction("p", "Pause")),
            listOf(NotificationAction("r", "Resume")),
            listOf(NotificationAction("p", "Pause"), NotificationAction("c", "Cancel")),
        )
        val EXTRAS: List<Map<String, String>?> = listOf(null, mapOf("s" to "a"), mapOf("s" to "b"))
    }
}
