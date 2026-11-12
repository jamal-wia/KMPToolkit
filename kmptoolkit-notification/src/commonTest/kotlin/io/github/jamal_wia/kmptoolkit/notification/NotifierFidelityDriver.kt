package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.notification.testing.RecordingNotifier
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * Runs one seeded script through a real notifier (over a [NotifierFuzzHost]) and through
 * [RecordingNotifier] side by side. Before each post the double is told what the real notifier
 * answered, as a consumer scripting the double from observed behaviour would; the two must then
 * agree on what the user is shown, after every step, and the double's log must say what was asked.
 * Differences the testing docs state (nothing is coalesced, the double clears only its own posts,
 * a restart is invisible to it) are not exercised as differences.
 */
internal object NotifierFidelityDriver {

    private val IDS: List<String> = listOf("a", "b", "c")
    private val STATUSES: List<PermissionStatus> = listOf(
        PermissionStatus.Granted,
        PermissionStatus.Limited,
        PermissionStatus.Denied(),
        PermissionStatus.PermanentlyDenied,
        PermissionStatus.Restricted,
    )
    private val CHANNELS: List<NotificationChannelSpec> = listOf(
        NotificationChannelSpec("c0", "C0"),
        NotificationChannelSpec("c1", "C1", importance = NotificationImportance.High),
    )
    private val OPTIONS: List<NotificationOptions> = listOf(
        NotificationOptions.DEFAULT,
        NotificationOptions(alertOnce = false),
        NotificationOptions(dismissAction = NotificationAction("d", "Dismiss"), mediaStyle = true),
    )

    suspend fun run(host: NotifierFuzzHost, seeds: Int, steps: Int) {
        repeat(seeds) { seed: Int -> runSeed(host, seed, steps) }
    }

    private suspend fun runSeed(host: NotifierFuzzHost, seed: Int, steps: Int) {
        val random = Random(seed * 7919 + 13)
        host.reset()
        val clock = TestTimeSource()
        var real: Notifier = host.newNotifier(clock)
        val fake = RecordingNotifier()
        val askedPosts: MutableList<Pair<String, LocalNotification>> = mutableListOf()
        val askedCancels: MutableList<String> = mutableListOf()
        var askedCancelAll = 0
        val log = StringBuilder()

        repeat(steps) { step: Int ->
            val where = "seed $seed step $step"
            when (random.nextInt(10)) {
                0 -> {
                    val id: String = IDS.random(random)
                    real.cancel(id)
                    fake.cancel(id)
                    askedCancels += id
                    log.append("cancel($id); ")
                }

                1 -> if (random.nextInt(3) == 0) {
                    real.cancelAll()
                    fake.cancelAll()
                    askedCancelAll++
                    log.append("cancelAll; ")
                } else {
                    real = host.newNotifier(clock)
                    log.append("restart; ")
                }

                2 -> {
                    val status: PermissionStatus = STATUSES.random(random)
                    host.setPermission(status)
                    log.append("permission=$status; ")
                }

                3 -> if (host.hasAppSwitch) {
                    host.setAppSwitch(random.nextBoolean())
                    log.append("switch; ")
                }

                4 -> {
                    clock += listOf(0, 499, 500, 2_000).random(random).milliseconds
                    log.append("tick; ")
                }

                else -> {
                    val id: String = IDS.random(random)
                    val progress: NotificationProgress? = when (random.nextInt(5)) {
                        0 -> null
                        1 -> NotificationProgress.Indeterminate
                        else -> NotificationProgress.Determinate(random.nextInt(0, 101))
                    }
                    val frame = LocalNotification(
                        title = "t${random.nextInt(3)}",
                        body = "b${random.nextInt(2)}",
                        channel = CHANNELS.random(random),
                        progress = progress,
                        actions = if (random.nextBoolean()) emptyList() else listOf(NotificationAction("p", "Pause")),
                        contentExtras = if (random.nextBoolean()) null else mapOf("s" to "x"),
                    )
                    val options: NotificationOptions = OPTIONS.random(random)
                    val answered: NotificationResult = real.post(id, frame, options)
                    fake.result = answered
                    val recorded: NotificationResult = fake.post(id, frame, options)
                    askedPosts += id to frame
                    log.append("post($id,${frame.title}/${frame.body}/${frame.channel.id},$progress)=${answered::class.simpleName}; ")
                    assertEquals(answered, recorded, "$where: the double answers what it was told. Steps: $log")
                    assertEquals(id to frame, fake.posted.last().let { it.id to it.notification }, "$where: the log holds the post. Steps: $log")
                    assertEquals(options, fake.posted.last().options, "$where: the log holds the options. Steps: $log")
                    if (options === NotificationOptions.DEFAULT) {
                        // The double's doc: DEFAULT is recorded as itself.
                        assertSame(NotificationOptions.DEFAULT, fake.posted.last().options, "$where: DEFAULT is recorded as itself. Steps: $log")
                    }
                }
            }
            val onScreen: Map<String, Pair<String, String>> = host.shown(IDS)
            val fakeScreen: Map<String, Pair<String, String>> =
                fake.showing.mapValues { (_, n: LocalNotification) -> n.title to n.body }
            assertEquals(onScreen, fakeScreen, "$where: the double and the real tray disagree on what is shown. Steps: $log")
            assertEquals(askedPosts, fake.posted.map { it.id to it.notification }, "$where: the post log. Steps: $log")
            assertEquals(askedCancels, fake.cancelled, "$where: the cancel log. Steps: $log")
            assertEquals(askedCancelAll, fake.cancelAllCount, "$where: the cancelAll count. Steps: $log")
        }
    }

    /** Both reject a blank id the same way, on both overloads, without recording a post of it. */
    suspend fun blankIdRejectedAlike(real: Notifier) {
        val fake = RecordingNotifier()
        val frame = LocalNotification("t", "b", NotificationChannelSpec("c0", "C0"))
        for (blank: String in listOf("", " ", "\t", "\n ", " ")) {
            val onReal: Throwable = assertFailsWith<IllegalArgumentException> { real.post(blank, frame) }
            val onFake: Throwable = assertFailsWith<IllegalArgumentException> { fake.post(blank, frame) }
            assertEquals(onReal.message, onFake.message, "same message for '${blank.length}' blank chars")
            assertFailsWith<IllegalArgumentException> { real.post(blank, frame, NotificationOptions.DEFAULT) }
            assertFailsWith<IllegalArgumentException> { fake.post(blank, frame, NotificationOptions.DEFAULT) }
        }
        assertEquals(emptyList(), fake.posted)
    }
}
