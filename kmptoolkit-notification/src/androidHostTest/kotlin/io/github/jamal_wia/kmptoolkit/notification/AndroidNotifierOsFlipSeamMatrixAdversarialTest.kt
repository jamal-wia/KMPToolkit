package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Round 19 (OS-state flips through the seams): the user flips one setting (app notifications off, the channel muted,
 * the channel deleted) at every seam of a post (just after the permission answer, after the app-wide toggle was
 * read, after the channel was read, after it was created), starting from every base state of permission, toggle and
 * channel. 04-api-reference, "Ordering": "the checks run permission -> app-level toggle -> channel -> icon ->
 * coalescing, so the result names the first reason the user would not have seen the notification". Each gate
 * judges the state at the moment it looks; a flip after a gate has looked cannot change that gate's answer. The
 * oracle is that sequential reading. Nothing may throw, a post answered Posted is on the tray, and a gate
 * rejection leaves no baseline: after healing the same frame posts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], shadows = [AndroidNotifierOsFlipSeamMatrixAdversarialTest.SeamShadowNotificationManager::class])
class AndroidNotifierOsFlipSeamMatrixAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private var spec = NotificationChannelSpec(id = "flip", name = "Flip")

    enum class Seam { PERMISSION, TOGGLE, CHANNEL_READ, CHANNEL_CREATE }
    enum class Flip { TOGGLE_OFF, MUTE, DELETE }
    enum class ChannelState { ABSENT, OK, MUTED }

    /** [remembered]: what the platform keeps of a deleted channel; creating it again restores the user's settings. */
    private data class State(var permitted: Boolean, var enabled: Boolean, var channel: ChannelState, var remembered: ChannelState? = null)

    @After
    fun heal() {
        SeamShadowNotificationManager.hook.set(null)
    }

    private class FlippingPermissions(private val granted: () -> Boolean, private val after: () -> Unit) : PermissionController {
        override suspend fun check(permission: Permission): PermissionStatus {
            val answer: PermissionStatus = if (granted()) PermissionStatus.Granted else PermissionStatus.PermanentlyDenied
            after()
            return answer
        }

        override suspend fun request(permission: Permission): PermissionStatus = check(permission)
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { PermissionStatus.Granted }

        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    private fun applyToPlatform(flip: Flip) {
        when (flip) {
            Flip.TOGGLE_OFF -> shadowOf(manager).setNotificationsEnabled(false)
            Flip.MUTE -> manager.getNotificationChannel(spec.id)?.importance = NotificationManager.IMPORTANCE_NONE
            Flip.DELETE -> manager.deleteNotificationChannel(spec.id)
        }
    }

    private fun applyToModel(state: State, flip: Flip) {
        when (flip) {
            Flip.TOGGLE_OFF -> state.enabled = false
            Flip.MUTE -> if (state.channel != ChannelState.ABSENT) state.channel = ChannelState.MUTED
            Flip.DELETE -> {
                if (state.channel != ChannelState.ABSENT) state.remembered = state.channel
                state.channel = ChannelState.ABSENT
            }
        }
    }

    /** The documented ordering read one gate at a time, each gate seeing the state at the moment it looks. */
    private fun oracle(base: State, seam: Seam?, flip: Flip?): NotificationResult {
        val state: State = base.copy()
        fun at(point: Seam) {
            if (point == seam && flip != null) applyToModel(state, flip)
        }
        val permitted: Boolean = state.permitted
        at(Seam.PERMISSION)
        if (!permitted) return NotificationResult.PermissionDenied
        val enabled: Boolean = state.enabled
        at(Seam.TOGGLE)
        if (!enabled) return NotificationResult.NotificationsDisabled
        var read: ChannelState = state.channel
        at(Seam.CHANNEL_READ)
        if (read == ChannelState.ABSENT) {
            state.channel = state.remembered ?: ChannelState.OK
            at(Seam.CHANNEL_CREATE)
            read = state.channel
        }
        return if (read == ChannelState.MUTED) NotificationResult.ChannelBlocked(spec.id) else NotificationResult.Posted
    }

    private fun frame(percent: Int): LocalNotification = LocalNotification(
        title = "t",
        body = "b",
        channel = spec,
        progress = NotificationProgress.Determinate(percent),
    )

    @Test
    fun `every flip at every seam from every base state is read by the gates in the documented order`() {
        val seamsAndFlips: List<Pair<Seam?, Flip?>> =
            listOf<Pair<Seam?, Flip?>>(null to null) + Seam.entries.flatMap { seam -> Flip.entries.map { flip -> seam to flip } }
                // Not in the matrix: a mute right after the channel was read. The real platform hands out a copy of the channel,
                // so the notifier would never see it; the Robolectric shadow hands out its stored object, which the mute changes.
                .filterNot { (seam: Seam?, flip: Flip?) -> seam == Seam.CHANNEL_READ && flip == Flip.MUTE }
        var cases = 0
        for (permitted: Boolean in listOf(true, false)) {
            for (enabled: Boolean in listOf(true, false)) {
                for (channel: ChannelState in ChannelState.entries) {
                    for ((seam: Seam?, flip: Flip?) in seamsAndFlips) {
                        cases++
                        // A channel of its own per case: a deleted channel is remembered by the platform with its settings.
                        spec = NotificationChannelSpec(id = "flip-$cases", name = "Flip")
                        val base = State(permitted, enabled, channel, remembered = if (channel == ChannelState.ABSENT) ChannelState.OK else null)
                        val label = "base=$base seam=$seam flip=$flip"

                        // A clean platform and a warmed notifier: the cache holds the spec, as after any earlier post.
                        SeamShadowNotificationManager.hook.set(null)
                        shadowOf(manager).setNotificationsEnabled(true)
                        manager.cancelAll()
                        shadowOf(manager).notificationChannels.toList().forEach { manager.deleteNotificationChannel((it as NotificationChannel).id) }
                        var granted: Boolean = true
                        var armed = false
                        val notifier: Notifier = AndroidNotifier(
                            context,
                            FlippingPermissions({ granted }) { if (armed && seam == Seam.PERMISSION && flip != null) applyToPlatform(flip) },
                            NotificationRenderer(context, "test.ACTION"),
                            ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
                        )
                        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("warm", frame(5)) }, "$label: warm-up")
                        manager.cancelAll()

                        // Base state of the platform and of the permission.
                        granted = permitted
                        armed = true
                        SeamShadowNotificationManager.reading.set(false)
                        shadowOf(manager).setNotificationsEnabled(enabled)
                        when (channel) {
                            ChannelState.ABSENT -> manager.deleteNotificationChannel(spec.id)
                            ChannelState.OK -> Unit
                            ChannelState.MUTED -> manager.getNotificationChannel(spec.id).importance = NotificationManager.IMPORTANCE_NONE
                        }

                        val after: NotificationManager.() -> Unit = { if (flip != null) applyToPlatform(flip) }
                        SeamShadowNotificationManager.hook.set(
                            if (seam == null || seam == Seam.PERMISSION) null else seam to after,
                        )
                        val result: NotificationResult = runCatching { runBlocking { notifier.post("p", frame(50)) } }
                            .getOrElse { throw AssertionError("$label: post threw $it", it) }
                        SeamShadowNotificationManager.hook.set(null)

                        assertEquals(oracle(base, seam, flip), result, label)
                        if (result == NotificationResult.Posted) {
                            assertNotNull(shadowOf(manager).getNotification("p", 1), "$label: a Posted frame must be on the tray")
                        } else {
                            // A gate rejection leaves no baseline: with everything healed the same frame posts.
                            armed = false
                            granted = true
                            shadowOf(manager).setNotificationsEnabled(true)
                            NotificationChannels.ensure(context, spec)
                            manager.getNotificationChannel(spec.id).importance = NotificationManager.IMPORTANCE_DEFAULT
                            assertEquals(NotificationResult.Posted, runBlocking { notifier.post("p", frame(50)) }, "$label: retry after healing")
                        }
                    }
                }
            }
        }
        assertTrue(cases > 100, "matrix size $cases")
    }

    /** Runs the configured flip right after the real platform call of one seam. */
    @Implements(NotificationManager::class)
    class SeamShadowNotificationManager : ShadowNotificationManager() {

        @Implementation
        public override fun areNotificationsEnabled(): Boolean {
            val answer: Boolean = super.areNotificationsEnabled()
            fire(Seam.TOGGLE)
            return answer
        }

        @Implementation
        public override fun getNotificationChannel(channelId: String?): NotificationChannel? {
            val answer: NotificationChannel? = super.getNotificationChannel(channelId)
            if (!reading.get()) {
                reading.set(true)
                fire(Seam.CHANNEL_READ)
            }
            return answer
        }

        @Implementation
        public override fun createNotificationChannel(channel: NotificationChannel) {
            super.createNotificationChannel(channel)
            fire(Seam.CHANNEL_CREATE)
        }

        private fun fire(at: Seam) {
            val pending: Pair<Seam, NotificationManager.() -> Unit>? = hook.get()
            if (pending != null && pending.first == at) {
                hook.set(null)
                pending.second.invoke(this.realManager())
            }
        }

        private fun realManager(): NotificationManager =
            org.robolectric.RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)

        companion object {
            val hook: AtomicReference<Pair<Seam, NotificationManager.() -> Unit>?> = AtomicReference(null)
            val reading: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
        }
    }
}
