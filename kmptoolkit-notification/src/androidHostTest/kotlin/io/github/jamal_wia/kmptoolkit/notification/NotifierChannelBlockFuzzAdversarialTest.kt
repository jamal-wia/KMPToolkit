package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "A redundant frame can never mask a real failure" (docs 03): the channel is judged on every post,
 * before coalescing is decided. A seeded sequence in which the user mutes and unmutes the channel
 * (importance none and back) between progress posts inside one bucket: while it is muted every post,
 * redundant or not, answers `ChannelBlocked` and puts nothing on the tray; the baseline is untouched by
 * those, so once the channel is unmuted the first frame is judged against the last frame that was
 * really shown. The seed is in every failure message.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotifierChannelBlockFuzzAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    // One channel id per seed: the platform gives a deleted channel back with the user's old settings, a muted one included.
    private var spec = NotificationChannelSpec(id = "muteable", name = "Muteable")

    /** The user's own change: the platform keeps the channel (and its history) and only its importance moves. */
    private fun setUserImportance(importance: Int) {
        manager.getNotificationChannel(spec.id).importance = importance
    }

    @Test
    fun `a muted channel answers ChannelBlocked for redundant and fresh frames alike and leaves the baseline alone`() {
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            spec = NotificationChannelSpec(id = "muteable-$seed", name = "Muteable")
            manager.cancelAll()
            manager.notificationChannels.toList().forEach { manager.deleteNotificationChannel(it.id) }
            val notifier: Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))
            var muted = false
            var shownTitle: String? = null // what the user last really saw
            var baselineBucket: Int? = null
            var baselineTitle: String? = null
            val log = StringBuilder()
            repeat(STEPS) { step: Int ->
                when (random.nextInt(5)) {
                    0 -> {
                        muted = !muted
                        if (shownTitle == null && manager.getNotificationChannel(spec.id) == null) {
                            // The channel does not exist yet: a user can not mute it before the first post made it.
                            muted = false
                        } else {
                            setUserImportance(if (muted) NotificationManager.IMPORTANCE_NONE else NotificationManager.IMPORTANCE_DEFAULT)
                        }
                        log.append("muted=$muted; ")
                    }

                    else -> {
                        val percent: Int = random.nextInt(0, 100)
                        val title = "t${random.nextInt(2)}"
                        val result: NotificationResult = runBlocking {
                            notifier.post(
                                "id",
                                LocalNotification(title = title, body = "b", channel = spec, progress = NotificationProgress.Determinate(percent)),
                            )
                        }
                        log.append("post($title,$percent)=${result::class.simpleName}; ")
                        val bucket: Int = (percent / NotificationConfig.DEFAULT_PROGRESS_BUCKET_PERCENT) * NotificationConfig.DEFAULT_PROGRESS_BUCKET_PERCENT
                        val expected: NotificationResult = when {
                            muted -> NotificationResult.ChannelBlocked(spec.id)
                            baselineBucket == null -> NotificationResult.Posted
                            baselineBucket == bucket && baselineTitle == title -> NotificationResult.Coalesced
                            else -> NotificationResult.Posted
                        }
                        assertEquals(expected, result, "seed $seed step $step: answer. Steps: $log")
                        if (result == NotificationResult.Posted) {
                            shownTitle = title
                            baselineBucket = bucket
                            baselineTitle = title
                        }
                    }
                }
                val onTray: Notification? = shadowOf(manager).getNotification("id", 1)
                assertEquals(
                    shownTitle, onTray?.extras?.getString(Notification.EXTRA_TITLE),
                    "seed $seed step $step: the tray differs from what was really shown. Steps: $log",
                )
            }
        }
    }

    private companion object {
        const val SEEDS: Int = 80
        const val STEPS: Int = 40
    }
}
