package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.notification.AndroidNotifierProcessDeathStepsAdversarialTest.Kill
import io.github.jamal_wia.kmptoolkit.notification.AndroidNotifierProcessDeathStepsAdversarialTest.ProcessKilled
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Round 19 (process death, differential): seeded random sessions of posts, cancels and `cancelAll`, in which the
 * platform kills the process (an `Error` from the platform call, before or after its effect) at a random
 * step, after which either a new notifier takes over the tray (a restart: no baselines, no caches) or, for a
 * death that never reached the tray, the same notifier lives on (an `Error` the host survived). A reference
 * model of the documented rules (bucket, a terminal frame resets, a failed frame is rolled back to the one
 * the tray shows, a cancel forgets, a restart loses every baseline) decides every answer; the tray the
 * platform holds must equal the model's after every step.
 *
 * Contract sentences exercised: 04-api-reference "a post that fails is rolled back: if a post returns
 * `Failed`, retrying the same progress posts it again" and "`cancel` removes the notification ... and
 * forgets its progress-coalescing state"; 05-platform-notes "nothing to restore after a process death".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29, 34], shadows = [AndroidNotifierProcessDeathStepsAdversarialTest.DyingShadowNotificationManager::class])
class AndroidNotifierDeathModelFuzzAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "fuzz", name = "Fuzz")

    private val ids: List<String> = listOf("a", "b", "c")
    private val files: List<String> = listOf("x", "y")
    private val percents: List<Int?> = listOf(0, 5, 9, 10, 15, 25, 39, 40, 50, 95, 99, 100, null)

    @After
    fun heal() {
        AndroidNotifierProcessDeathStepsAdversarialTest.DyingShadowNotificationManager.kill.set(Kill.NONE)
    }

    private data class Base(val bucket: Int, val file: String)

    private data class OnTray(val title: String, val progress: Int?)

    private class Model {
        val tray: MutableMap<String, OnTray> = mutableMapOf()
        val baseline: MutableMap<String, Base> = mutableMapOf()
        val shown: MutableMap<String, Base> = mutableMapOf()

        fun isReset(percent: Int?): Boolean = percent == null || percent >= 100

        /** What a post answers when nothing goes wrong, without applying it. */
        fun answer(id: String, percent: Int?, file: String): NotificationResult {
            if (isReset(percent)) return NotificationResult.Posted
            val b: Base? = baseline[id]
            return if (b != null && b.bucket == (percent!! / 10) * 10 && b.file == file) NotificationResult.Coalesced else NotificationResult.Posted
        }

        fun applyPost(id: String, percent: Int?, file: String) {
            if (answer(id, percent, file) == NotificationResult.Coalesced) return
            tray[id] = OnTray("title-$file", percent?.coerceIn(0, 100))
            if (isReset(percent)) {
                baseline.remove(id)
                shown.remove(id)
            } else {
                val record = Base((percent!! / 10) * 10, file)
                baseline[id] = record
                shown[id] = record
            }
        }

        fun restart() {
            baseline.clear()
            shown.clear()
        }
    }

    private fun notifier(): Notifier = AndroidNotifier(
        context, permissions, NotificationRenderer(context, "test.ACTION"),
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
    )

    private fun frame(file: String, percent: Int?): LocalNotification = LocalNotification(
        title = "title-$file",
        body = "b",
        channel = channel,
        progress = percent?.let { NotificationProgress.Determinate(it) },
    )

    private fun platformTray(): Map<String, OnTray> = ids.mapNotNull { id: String ->
        val n: Notification = shadowOf(manager).getNotification(id, 1) ?: return@mapNotNull null
        id to OnTray(
            n.extras.getString(Notification.EXTRA_TITLE).orEmpty(),
            if (n.extras.containsKey(Notification.EXTRA_PROGRESS_MAX) && n.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0) n.extras.getInt(Notification.EXTRA_PROGRESS) else null,
        )
    }.toMap()

    @Test
    fun `random sessions with deaths and restarts keep the tray and every answer equal to the model`() {
        val kill = AndroidNotifierProcessDeathStepsAdversarialTest.DyingShadowNotificationManager.kill
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed * 7919 + 13)
            shadowOf(manager).notificationChannels.toList().forEach { manager.deleteNotificationChannel(it.id) }
            manager.cancelAll()
            val model = Model()
            var notifier: Notifier = notifier()
            val log = StringBuilder()

            fun check(step: String) {
                assertEquals(model.tray.toMap(), platformTray(), "seed $seed after $step. Steps: $log")
            }

            repeat(STEPS) { step: Int ->
                val id: String = ids[random.nextInt(ids.size)]
                val roll: Int = random.nextInt(100)
                when {
                    roll < 70 -> {
                        val percent: Int? = percents[random.nextInt(percents.size)]
                        val file: String = files[random.nextInt(files.size)]
                        val expectedAnswer: NotificationResult = model.answer(id, percent, file)
                        val answer: NotificationResult = runBlocking { notifier.post(id, frame(file, percent)) }
                        log.append("post($id,$percent,$file)=$answer; ")
                        assertEquals(expectedAnswer, answer, "seed $seed step $step. Steps: $log")
                        model.applyPost(id, percent, file)
                    }
                    roll < 78 -> {
                        notifier.cancel(id)
                        log.append("cancel($id); ")
                        model.tray.remove(id)
                        model.baseline.remove(id)
                        model.shown.remove(id)
                    }
                    roll < 82 -> {
                        notifier.cancelAll()
                        log.append("cancelAll; ")
                        model.tray.clear()
                        model.baseline.clear()
                        model.shown.clear()
                    }
                    roll < 88 -> {
                        notifier = notifier()
                        model.restart()
                        log.append("restart; ")
                    }
                    else -> {
                        // A death in the next platform call of a random operation.
                        val kind: Int = random.nextInt(3)
                        val point: Kill
                        val survivable: Boolean
                        when (kind) {
                            0 -> {
                                point = listOf(Kill.BEFORE_CHANNEL, Kill.BEFORE_NOTIFY, Kill.AFTER_NOTIFY)[random.nextInt(3)]
                                survivable = point != Kill.AFTER_NOTIFY
                                val percent: Int? = percents[random.nextInt(percents.size)]
                                val file: String = files[random.nextInt(files.size)]
                                val reached: Boolean = model.answer(id, percent, file) != NotificationResult.Coalesced
                                kill.set(point)
                                val death: Throwable? = runCatching { runBlocking { notifier.post(id, frame(file, percent)) } }.exceptionOrNull()
                                kill.set(Kill.NONE)
                                log.append("kill@$point post($id,$percent,$file) death=${death?.javaClass?.simpleName}; ")
                                if (death == null) {
                                    // The call that would die was never reached (a coalesced frame, a channel that exists).
                                    assertTrue(point == Kill.BEFORE_CHANNEL || !reached, "seed $seed step $step: no death although the frame reached notify. Steps: $log")
                                    model.applyPost(id, percent, file)
                                } else {
                                    assertTrue(death is ProcessKilled, "seed $seed step $step: $death. Steps: $log")
                                    when (point) {
                                        Kill.BEFORE_CHANNEL -> Unit
                                        Kill.BEFORE_NOTIFY -> if (model.isReset(percent)) {
                                            model.baseline.remove(id)
                                        } else {
                                            val shown: Base? = model.shown[id]
                                            if (shown == null) model.baseline.remove(id) else model.baseline[id] = shown
                                        }
                                        else -> model.tray[id] = OnTray("title-$file", percent?.coerceIn(0, 100))
                                    }
                                    if (!survivable || random.nextBoolean()) {
                                        notifier = notifier()
                                        model.restart()
                                        log.append("restart; ")
                                    }
                                }
                            }
                            1 -> {
                                point = listOf(Kill.BEFORE_CANCEL, Kill.AFTER_CANCEL)[random.nextInt(2)]
                                kill.set(point)
                                val death: Throwable? = runCatching { notifier.cancel(id) }.exceptionOrNull()
                                kill.set(Kill.NONE)
                                log.append("kill@$point cancel($id) death=${death?.javaClass?.simpleName}; ")
                                assertTrue(death is ProcessKilled, "seed $seed step $step: cancel did not die: $death. Steps: $log")
                                model.baseline.remove(id)
                                model.shown.remove(id)
                                if (point == Kill.AFTER_CANCEL) model.tray.remove(id)
                                if (random.nextBoolean()) {
                                    notifier = notifier()
                                    model.restart()
                                    log.append("restart; ")
                                }
                            }
                            else -> {
                                point = listOf(Kill.BEFORE_CANCEL_ALL, Kill.AFTER_CANCEL_ALL)[random.nextInt(2)]
                                kill.set(point)
                                val death: Throwable? = runCatching { notifier.cancelAll() }.exceptionOrNull()
                                kill.set(Kill.NONE)
                                log.append("kill@$point cancelAll death=${death?.javaClass?.simpleName}; ")
                                assertTrue(death is ProcessKilled, "seed $seed step $step: cancelAll did not die: $death. Steps: $log")
                                model.baseline.clear()
                                model.shown.clear()
                                if (point == Kill.AFTER_CANCEL_ALL) model.tray.clear()
                                if (random.nextBoolean()) {
                                    notifier = notifier()
                                    model.restart()
                                    log.append("restart; ")
                                }
                            }
                        }
                    }
                }
                check("step $step")
            }
        }
    }

    private companion object {
        const val SEEDS: Int = 120
        const val STEPS: Int = 45
    }
}
