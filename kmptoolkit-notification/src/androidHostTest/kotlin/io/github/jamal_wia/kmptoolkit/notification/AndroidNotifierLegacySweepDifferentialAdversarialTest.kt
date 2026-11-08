package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * The 2.x migration sweep against a reference, over random lifecycles: untagged notifications left by an older
 * version (planted under the hash-derived Int of every id, under a foreground service's Int, under an unrelated
 * Int), ids with equal hash codes, a foreground id whose Int is the legacy Int of other ids, process restarts,
 * and a platform that refuses notify, cancel or cancelAll. After every step the set of tagged and the set of
 * untagged notifications on the tray must be exactly what the documented rules leave:
 * a post that went through, and a cancel, remove the untagged notification of the id's old number unless that number
 * belongs to a foreground service; a refused removal leaves it; cancelAll leaves nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], shadows = [AndroidNotifierLegacySweepDifferentialAdversarialTest.FaultyShadowNotificationManager::class])
class AndroidNotifierLegacySweepDifferentialAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "legacy", name = "Legacy")

    enum class Fault { NONE, REFUSE_NOTIFY, REFUSE_CANCEL, REFUSE_CANCEL_ALL }

    private fun legacyOf(id: String): Int = (id.hashCode() and 0x7FFFFFFF).coerceAtLeast(1)

    private fun plant(number: Int) {
        val built: Notification = NotificationCompat.Builder(context, "legacy").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("planted").build()
        manager.notify(number, built)
    }

    private fun run(seed: Int): String? {
        val random = Random(seed)
        val foreground: Set<ForegroundNotificationId> = setOf(
            ForegroundNotificationId("svc", 1001),
            ForegroundNotificationId("fg2", legacyOf("Aa")),
        )
        val fgNumbers: Map<String, Int> = foreground.associate { it.id to it.platformId }
        val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
        fun newNotifier(): Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO), foreground)
        manager.cancelAll()
        FaultyShadowNotificationManager.fault.set(Fault.NONE)
        var notifier: Notifier = newNotifier()
        // "ϩ" has hash code 1001, so its legacy number is the number of the service "svc".
        val ids: List<String> = listOf("Aa", "BB", "a", "svc", "fg2", "ϩ", "\uD800")
        val numbers: List<Int> = ids.map(::legacyOf) + listOf(1001, 7)
        val tagged: MutableSet<String> = mutableSetOf()
        val untagged: MutableSet<Int> = mutableSetOf()
        // ids whose sweep this notifier instance already did (the documented "first post of each id" cache)
        val swept: MutableSet<String> = mutableSetOf()
        val log = StringBuilder()

        fun sweep(id: String) {
            val legacy: Int = legacyOf(id)
            if (legacy !in fgNumbers.values) untagged -= legacy
        }

        repeat(40) { step ->
            val id: String = ids[random.nextInt(ids.size)]
            val fault: Fault = when (random.nextInt(12)) { 0 -> Fault.REFUSE_NOTIFY; 1, 2 -> Fault.REFUSE_CANCEL; 3 -> Fault.REFUSE_CANCEL_ALL; else -> Fault.NONE }
            when (random.nextInt(10)) {
                0, 1 -> { val n: Int = numbers[random.nextInt(numbers.size)]; log.append("plant($n) "); plant(n); untagged += n }
                2, 3 -> {
                    FaultyShadowNotificationManager.fault.set(fault)
                    log.append("cancel($id,$fault) ")
                    notifier.cancel(id)
                    FaultyShadowNotificationManager.fault.set(Fault.NONE)
                    if (fault != Fault.REFUSE_CANCEL) {
                        val number: Int? = fgNumbers[id]
                        if (number != null) untagged -= number else tagged -= id
                        sweep(id)
                    }
                    swept -= id
                }
                4 -> {
                    FaultyShadowNotificationManager.fault.set(fault)
                    log.append("cancelAll($fault) ")
                    notifier.cancelAll()
                    FaultyShadowNotificationManager.fault.set(Fault.NONE)
                    if (fault != Fault.REFUSE_CANCEL_ALL) { tagged.clear(); untagged.clear() }
                    swept.clear()
                }
                5 -> { log.append("RESTART "); notifier = newNotifier(); swept.clear() }
                else -> {
                    FaultyShadowNotificationManager.fault.set(fault)
                    log.append("post($id,$fault) ")
                    val result: NotificationResult = runBlocking {
                        notifier.post(id, LocalNotification(title = id, body = "b", channel = channel))
                    }
                    FaultyShadowNotificationManager.fault.set(Fault.NONE)
                    if (fault == Fault.REFUSE_NOTIFY) {
                        if (result !is NotificationResult.Failed) return "seed $seed step $step: expected Failed, got $result; $log"
                    } else {
                        if (result != NotificationResult.Posted) return "seed $seed step $step: expected Posted, got $result; $log"
                        val number: Int? = fgNumbers[id]
                        if (number != null) untagged += number else tagged += id
                        // the sweep is attempted after the platform accepted the frame, once per id and instance; a
                        // refused removal leaves the leftover and is retried by the next post
                        if (id !in swept) {
                            val skipped: Boolean = legacyOf(id) in fgNumbers.values
                            if (skipped || fault != Fault.REFUSE_CANCEL) { sweep(id); swept += id }
                        }
                    }
                }
            }
            val active = manager.activeNotifications
            val actualTagged: Set<String> = active.mapNotNull { it.tag }.toSet()
            val actualUntagged: Set<Int> = active.filter { it.tag == null }.map { it.id }.toSet()
            if (actualTagged != tagged) return "seed $seed step $step: tagged on tray ${actualTagged.map { it.map { c -> c.code } }} expected ${tagged.map { it.map { c -> c.code } }}; $log"
            if (actualUntagged != untagged) return "seed $seed step $step: untagged on tray $actualUntagged expected $untagged; $log"
        }
        return null
    }

    @Test
    fun `the migration sweep leaves exactly what the rules leave`() {
        val failures: List<String> = (0 until 800).mapNotNull { run(it) }
        assertEquals(emptyList(), failures.take(3), "${failures.size} of 800 seeds diverged")
    }

    @Implements(NotificationManager::class)
    class FaultyShadowNotificationManager : ShadowNotificationManager() {

        @Implementation
        public override fun notify(tag: String?, id: Int, notification: Notification?) {
            if (fault.get() == Fault.REFUSE_NOTIFY) throw SecurityException("notify refused")
            super.notify(tag, id, notification)
        }

        @Implementation
        public override fun cancel(tag: String?, id: Int) {
            if (fault.get() == Fault.REFUSE_CANCEL) throw RuntimeException("system server is restarting")
            super.cancel(tag, id)
        }

        @Implementation
        public override fun cancelAll() {
            if (fault.get() == Fault.REFUSE_CANCEL_ALL) throw RuntimeException("system server is restarting")
            super.cancelAll()
        }

        companion object {
            val fault: AtomicReference<Fault> = AtomicReference(Fault.NONE)
        }
    }
}
