package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The stability and accounting promises of the Android notifier, taken literally: nothing a process
 * cannot keep fixed (locale, time zone) takes part in a `PendingIntent` identity, a restarted
 * process starts from nothing, `Determinate(100)` and a frame without a bar are never held back,
 * out-of-range percentages behave as the documented clamp says, channels do not multiply, a
 * `ForegroundNotificationId` may use any non-zero `Int`, and `cancel` / `cancelAll` hold their
 * promises for ids and instances they never saw.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationUpgradeContractAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "upgrade", name = "Upgrade")
    private val savedLocale: Locale = Locale.getDefault()
    private val savedZone: TimeZone = TimeZone.getDefault()

    @Before
    fun registerLauncher() {
        // Without a launcher activity the app has no tap target at all.
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @After
    fun restore() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
        manager.cancelAll()
    }

    private fun notifier(
        config: NotificationConfig = NotificationConfig(),
        foreground: Set<ForegroundNotificationId> = emptySet(),
    ): Notifier = createNotifier(context, permissions, config, foreground)

    private fun note(
        title: String = "t",
        progress: NotificationProgress? = null,
        extras: Map<String, String>? = null,
        actions: List<NotificationAction> = emptyList(),
    ): LocalNotification = LocalNotification(
        title = title, body = "b", channel = channel, progress = progress, contentExtras = extras, actions = actions,
    )

    private fun post(notifier: Notifier, id: String, notification: LocalNotification, options: NotificationOptions = NotificationOptions.DEFAULT): NotificationResult =
        runBlocking { notifier.post(id, notification, options) }

    private fun tagged(id: String): Notification? = shadowOf(manager).getNotification(id, 1)

    @Test
    fun `locale and time zone never take part in a PendingIntent key, so a restarted process finds the tap target and buttons of the tray frame`() {
        val ids: List<String> = listOf("plain", "\uD800", "a\uDC00b", "😀\uD800", "ÀÉ ünï")
        val buttons: List<NotificationAction> = listOf(NotificationAction("stop", "Stop"), NotificationAction("pause\uD800", "Pause"))
        val options = NotificationOptions(dismissAction = NotificationAction("dismiss\uDFFF", "x"))
        Locale.setDefault(Locale.ROOT)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val first: Notifier = notifier()
        ids.forEach { id: String -> assertEquals(NotificationResult.Posted, post(first, id, note("one", extras = mapOf("n" to "1"), actions = buttons), options)) }
        val before: Map<String, Notification> = ids.associateWith { tagged(it)!! }

        val locales: List<Locale> = listOf(
            Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ar-SA-u-nu-arab"), Locale.forLanguageTag("hi-IN-u-nu-deva"),
            Locale.forLanguageTag("th-TH-u-nu-thai"), Locale.forLanguageTag("lt-LT"),
        )
        val zones: List<String> = listOf("Pacific/Kiritimati", "America/St_Johns", "Europe/Istanbul", "Australia/Lord_Howe", "Pacific/Apia")
        locales.forEachIndexed { index: Int, locale: Locale ->
            Locale.setDefault(locale)
            TimeZone.setDefault(TimeZone.getTimeZone(zones[index]))
            val restarted: Notifier = notifier()
            ids.forEach { id: String ->
                assertEquals(NotificationResult.Posted, post(restarted, id, note("round-$index", extras = mapOf("n" to "${index + 2}"), actions = buttons), options))
                val old: Notification = before.getValue(id)
                val now: Notification = tagged(id)!!
                // The very same PendingIntent objects came back: the key did not move with the locale.
                assertEquals(old.contentIntent, now.contentIntent, "tap target of '$id' under $locale")
                assertEquals(old.deleteIntent, now.deleteIntent, "dismissal of '$id' under $locale")
                old.actions.indices.forEach { i: Int -> assertEquals(old.actions[i].actionIntent, now.actions[i].actionIntent, "button $i of '$id' under $locale") }
                // ... and the update reached the one object the first frame holds.
                assertEquals("${index + 2}", shadowOf(old.contentIntent).savedIntent.getStringExtra("n"), "tap extras of '$id' under $locale")
            }
        }
    }

    @Test
    fun `a restarted process forgets the progress baseline, as documented, and its first frame posts`() {
        val first: Notifier = notifier()
        assertEquals(NotificationResult.Posted, post(first, "d", note(progress = NotificationProgress.Determinate(50))))
        assertEquals(NotificationResult.Coalesced, post(first, "d", note(progress = NotificationProgress.Determinate(51))))
        val restarted: Notifier = notifier()
        assertEquals(NotificationResult.Posted, post(restarted, "d", note(progress = NotificationProgress.Determinate(51))))
        assertEquals(NotificationResult.Coalesced, post(restarted, "d", note(progress = NotificationProgress.Determinate(52))))
    }

    @Test
    fun `a full bar and a frame without a bar are never held back, however fast and however often`() {
        val n: Notifier = notifier() // default 500 ms rate limit, default bucket
        repeat(5) { assertEquals(NotificationResult.Posted, post(n, "d", note(progress = NotificationProgress.Determinate(100))), "Determinate(100) #$it") }
        repeat(5) { assertEquals(NotificationResult.Posted, post(n, "d", note(progress = NotificationProgress.Indeterminate)), "Indeterminate #$it") }
        repeat(5) { assertEquals(NotificationResult.Posted, post(n, "d", note(progress = null)), "no bar #$it") }
        // Each of them reset the id: the next run starts fresh even inside the rate-limit window.
        assertEquals(NotificationResult.Posted, post(n, "d", note(progress = NotificationProgress.Determinate(10))))
        assertEquals(NotificationResult.Coalesced, post(n, "d", note(progress = NotificationProgress.Determinate(15))))
        assertEquals(NotificationResult.Posted, post(n, "d", note(progress = NotificationProgress.Determinate(100))))
        assertEquals(NotificationResult.Posted, post(n, "d", note(progress = NotificationProgress.Determinate(30))))
    }

    @Test
    fun `percentages outside 0 to 100 are clamped before bucketing and when drawing`() {
        val n: Notifier = notifier(NotificationConfig(minProgressInterval = Duration.ZERO))
        fun barOf(id: String): Int = tagged(id)!!.extras.getInt(NotificationCompat.EXTRA_PROGRESS, -1)

        listOf(Int.MAX_VALUE, 1000, 101).forEach { over: Int ->
            // Above 100 is a full bar: never suppressed, drawn as 100.
            repeat(3) { assertEquals(NotificationResult.Posted, post(n, "over", note(progress = NotificationProgress.Determinate(over))), "$over #$it") }
            assertEquals(100, barOf("over"), "$over")
        }
        listOf(Int.MIN_VALUE, -1, -100).forEach { under: Int ->
            n.cancel("under")
            assertEquals(NotificationResult.Posted, post(n, "under", note(progress = NotificationProgress.Determinate(under))), "$under")
            assertEquals(0, barOf("under"), "$under")
            // The same bucket as 0, with nothing else changed: redundant.
            assertEquals(NotificationResult.Coalesced, post(n, "under", note(progress = NotificationProgress.Determinate(0))), "$under then 0")
            assertEquals(NotificationResult.Coalesced, post(n, "under", note(progress = NotificationProgress.Determinate(Int.MIN_VALUE))), "$under then MIN")
            assertEquals(NotificationResult.Posted, post(n, "under", note(progress = NotificationProgress.Determinate(10))), "$under then 10")
        }
    }

    @Test
    fun `hundreds of posts over many ids on one channel spec leave one channel, ensure and delete are idempotent and a post re-creates a deleted one`() {
        val n: Notifier = notifier(NotificationConfig(minProgressInterval = Duration.ZERO))
        val before: Int = manager.notificationChannels.size
        repeat(300) { index: Int -> post(n, "id-${index % 6}", note("t$index")) }
        assertEquals(before + 1, manager.notificationChannels.size)
        repeat(50) { NotificationChannels.ensure(context, channel) }
        assertEquals(before + 1, manager.notificationChannels.size)
        val other = NotificationChannelSpec(id = "upgrade-two", name = "Two")
        post(n, "x", LocalNotification("t", "b", other))
        assertEquals(before + 2, manager.notificationChannels.size)
        NotificationChannels.delete(context, "upgrade-two")
        NotificationChannels.delete(context, "upgrade-two")
        NotificationChannels.delete(context, "never-existed")
        assertEquals(before + 1, manager.notificationChannels.size)
        assertEquals(NotificationResult.Posted, post(n, "x", LocalNotification("t2", "b", other)))
        assertEquals(before + 2, manager.notificationChannels.size)
        NotificationChannels.delete(context, "upgrade")
        assertNull(manager.getNotificationChannel("upgrade"))
        assertEquals(NotificationResult.Posted, post(n, "id-0", note("again")))
        assertNotNull(manager.getNotificationChannel("upgrade"))
    }

    @Test
    fun `cancelAll of an instance that posted nothing clears what other instances, other code and a foreground service left`() {
        val previousProcess: Notifier = notifier(foreground = setOf(ForegroundNotificationId("svc", 77)))
        post(previousProcess, "a", note())
        post(previousProcess, "svc", note())
        manager.notify(5, NotificationCompat.Builder(context, "upgrade").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("plain").build())
        manager.notify("own-tag", 9, NotificationCompat.Builder(context, "upgrade").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("tagged").build())
        assertEquals(4, shadowOf(manager).allNotifications.size)

        notifier().cancelAll()

        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `cancel never throws for an id it never saw, for a blank id, or twice, and leaves the others`() {
        val n: Notifier = notifier()
        post(n, "keep", note())
        listOf("", " ", " ", "never-posted", "\uD800", "x".repeat(10_000)).forEach { id: String ->
            n.cancel(id)
            n.cancel(id)
        }
        assertNotNull(tagged("keep"))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertFailsWith<IllegalArgumentException> { post(n, " ", note()) }
        assertFailsWith<IllegalArgumentException> { post(n, " ", note()) }
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `any non-zero Int is a usable foreground number, including the extremes that are the 2 x number of other ids`() {
        // Equal hashes after the 2.x mask: -1 and Int.MAX_VALUE both became Int.MAX_VALUE.
        val minusOne = "8suk633ga"
        val maxValue = "ep68pm8K\\"
        assertEquals(-1, minusOne.hashCode())
        assertEquals(Int.MAX_VALUE, maxValue.hashCode())
        val numbers: List<Int> = listOf(Int.MAX_VALUE, Int.MIN_VALUE, -1, 1, 2)
        numbers.forEachIndexed { index: Int, number: Int ->
            val svc = ForegroundNotificationId("svc", number)
            val n: Notifier = notifier(foreground = setOf(svc))
            manager.cancelAll()
            assertEquals(NotificationResult.Posted, post(n, "svc", note("fg-$index")))
            assertNotNull(shadowOf(manager).getNotification(null, number), "service notification under $number")
            // Ids whose 2.x number is this one neither remove the service's notification nor are removed by it.
            listOf(minusOne, maxValue).forEach { other: String ->
                assertEquals(NotificationResult.Posted, post(n, other, note("other")))
                n.cancel(other)
                assertNotNull(shadowOf(manager).getNotification(null, number), "'$other' removed the service notification under $number")
            }
            post(n, "svc", note("fg-again-$index"))
            assertEquals("fg-again-$index", shadowOf(manager).getNotification(null, number).extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString())
            n.cancel("svc")
            assertNull(shadowOf(manager).getNotification(null, number), "cancel of the service id left its notification under $number")
        }
        assertFailsWith<IllegalArgumentException> { ForegroundNotificationId("svc", 0) }
        assertFailsWith<IllegalArgumentException> { ForegroundNotificationId(" ", 5) }
    }
}
