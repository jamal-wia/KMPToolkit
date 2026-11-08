package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
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

/**
 * Random sequences of posts (progress and terminal, some refused by the platform), cancels,
 * cancelAlls and notifier re-creation (process death keeps the tray), checked step by step against a
 * model of the tray: `Posted` replaces the id's frame, `Coalesced` and `Failed` leave it alone,
 * a cancel removes it.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierModelAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "model", name = "Model")

    private class RefusingRenderer(context: Context) : NotificationRenderer(context, "test.ACTION") {
        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            check(!notification.title.startsWith("refuse")) { "the platform refused the frame" }
            return super.build(id, notification, options, iconResId)
        }
    }

    @Test
    fun `no sequence of posts failures cancels and re-creations makes the tray disagree with the results`() {
        val ids: List<String> = listOf("Aa", "BB", "x", "y/z")
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            manager.cancelAll()
            var notifier: Notifier = AndroidNotifier(
                context, permissions, RefusingRenderer(context), ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
            )
            val model: MutableMap<String, String> = mutableMapOf()
            val log = StringBuilder()
            repeat(STEPS) { step: Int ->
                val id: String = ids[random.nextInt(ids.size)]
                when (random.nextInt(10)) {
                    0, 1 -> {
                        notifier.cancel(id)
                        model.remove(id)
                        log.append("cancel($id); ")
                    }

                    2 -> if (random.nextInt(4) == 0) {
                        notifier.cancelAll()
                        model.clear()
                        log.append("cancelAll; ")
                    } else {
                        notifier = AndroidNotifier(
                            context, permissions, RefusingRenderer(context), ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
                        )
                        log.append("recreate; ")
                    }

                    else -> {
                        val refuse: Boolean = random.nextInt(5) == 0
                        val title: String = (if (refuse) "refuse" else "ok") + "|$id|$step"
                        val progress: NotificationProgress? =
                            if (random.nextInt(6) == 0) null else NotificationProgress.Determinate(random.nextInt(0, 101))
                        val result: NotificationResult = runBlocking {
                            notifier.post(id, LocalNotification(title = title, body = "b", channel = channel, progress = progress))
                        }
                        log.append("post($id,$progress,${if (refuse) "refused" else "ok"})=${result::class.simpleName}; ")
                        when (result) {
                            NotificationResult.Posted -> model[id] = title
                            NotificationResult.Coalesced, is NotificationResult.Failed -> Unit
                            else -> error("seed $seed: unexpected $result. Steps: $log")
                        }
                        if (refuse) assertEquals(true, result is NotificationResult.Failed, "seed $seed: a refused frame was $result. Steps: $log")
                    }
                }
                val onTray: Map<String, String> = shadowOf(manager).allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }
                    .associateBy { it.substringAfter('|').substringBefore('|') }
                assertEquals(model.mapValues { it.value }, onTray, "seed $seed step $step: the tray differs from the model. Steps: $log")
            }
        }
    }

    private companion object {
        const val SEEDS: Int = 150
        const val STEPS: Int = 30
    }
}
