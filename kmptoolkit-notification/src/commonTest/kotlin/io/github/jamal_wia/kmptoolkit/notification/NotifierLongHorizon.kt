package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.isUsable
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Round 24 (docs-only newcomer): a 30-day virtual-time model of the posting contract, written from
 * docs/kmptoolkit-notification/03-guide.md and 04-api-reference.md only, run through whatever
 * [NotifierFuzzHost] a platform offers (the real Android notifier over Robolectric, the real iOS notifier
 * over a fake center). Unlike [NotifierProtocolFuzz], whose clock moves by seconds, the clock here jumps by
 * milliseconds up to three days, so the abandoned-run horizon of the guide ("the larger of 120 times
 * minProgressInterval and ten minutes") is crossed again and again, with permission flips, app switch flips,
 * cancels, cancelAll and process restarts in between.
 *
 * What the docs promise about the horizon is a bound, not an instant: quiet for ten minutes or less is
 * remembered for sure; quiet for longer is forgotten, but the module scans for stale ids at most once per
 * tenth of the horizon, so the exact instant of forgetting is not documented. The model therefore treats the
 * band from ten to eleven minutes of quiet as "either answer allowed, then follow the notifier's answer",
 * and only asserts outside it. A band outcome that differs between remembered and forgotten is counted so
 * the test can insist that the horizon was really exercised. The seed is in every failure message.
 */
internal object NotifierLongHorizon {

    private val IDS: List<String> = listOf("dl-1", "dl-2", "dl-3", "dl-4")
    private const val BUCKET: Int = 10
    private val MIN_INTERVAL: Duration = 500.milliseconds
    private val HORIZON: Duration = 10.minutes
    private val HORIZON_BAND_END: Duration = 11.minutes
    private val TOTAL: Duration = 30.days

    private val TICKS: List<Duration> = listOf(
        0.milliseconds, 0.milliseconds, 0.milliseconds, 1.milliseconds, 1.milliseconds, 499.milliseconds, 500.milliseconds, 501.milliseconds, 2.seconds,
        59.seconds, 61.seconds, 5.minutes, 9.minutes + 59.seconds, 10.minutes, 10.minutes + 1.seconds,
        10.minutes + 30.seconds, 11.minutes, 11.minutes + 1.seconds, 1.days - 1.milliseconds, 1.days, 3.days,
    )
    /** Quiet stretches before the first probe frame: a short pause, a long one, and just inside the horizon. */
    private val LEAD_TICKS: List<Duration> = listOf(1.seconds, 61.seconds, 5.minutes, 9.minutes + 50.seconds)

    /** Quiet stretches before the second probe frame, straddling the ten minute horizon and the eleven minute band end. */
    private val PROBE_TICKS: List<Duration> = listOf(
        8.minutes, 9.minutes, 9.minutes + 59.seconds, 10.minutes, 10.minutes + 30.seconds, 11.minutes,
        11.minutes + 1.seconds, 15.minutes, 20.minutes,
    )
    private val STATUSES: List<PermissionStatus> = listOf(
        PermissionStatus.Granted,
        PermissionStatus.Limited,
        PermissionStatus.Denied(),
        PermissionStatus.PermanentlyDenied,
        PermissionStatus.NotDetermined,
        PermissionStatus.Restricted,
    )
    private val CHANNELS: List<NotificationChannelSpec> = listOf(
        NotificationChannelSpec("c0", "C0"),
        NotificationChannelSpec("c1", "C1", importance = NotificationImportance.High),
    )

    class Tally {
        var posted: Int = 0
        var coalesced: Int = 0
        var denied: Int = 0
        var disabled: Int = 0
        var horizonDecisive: Int = 0
        var horizonForgotten: Int = 0
        var restarts: Int = 0
        var finalVirtualTime: Duration = Duration.ZERO
    }

    /** The run must really have crossed the horizon, coalesced, been refused and restarted, or it proved nothing. */
    fun assertExercised(tally: Tally, host: NotifierFuzzHost) {
        assertTrue(tally.posted > 100, "posted ${tally.posted}")
        assertTrue(tally.coalesced > 20, "coalesced ${tally.coalesced}")
        assertTrue(tally.restarts > 3, "restarts ${tally.restarts}")
        assertTrue(tally.horizonDecisive > 0, "no frame was ever judged across the abandoned-run horizon")
        assertTrue(tally.finalVirtualTime > 10.days, "the virtual clock only reached ${tally.finalVirtualTime}")
        if (host.permissionGate) assertTrue(tally.denied > 0, "permission flips never refused a post")
        if (host.hasAppSwitch) assertTrue(tally.disabled > 0, "the app switch never refused a post")
    }

    private class Baseline(val bucket: Int, val content: LocalNotification, val at: Duration, var lastSeen: Duration)

    suspend fun run(host: NotifierFuzzHost, seeds: Int, steps: Int): Tally {
        val tally = Tally()
        repeat(seeds) { seed: Int -> runSeed(host, seed, steps, tally) }
        return tally
    }

    private suspend fun runSeed(host: NotifierFuzzHost, seed: Int, steps: Int, tally: Tally) {
        val random = Random(1_000 + seed)
        host.reset()
        val clock = TestTimeSource()
        var notifier: Notifier = host.newNotifier(clock)
        val tray: MutableMap<String, Pair<String, String>> = mutableMapOf()
        val baselines: MutableMap<String, Baseline> = mutableMapOf()
        val lastPercent: MutableMap<String, Int> = mutableMapOf()
        val lastLook: MutableMap<String, Triple<String, String, Int>> = mutableMapOf()
        var now: Duration = Duration.ZERO
        var status: PermissionStatus = PermissionStatus.Granted
        var enabled = true
        val log: ArrayDeque<String> = ArrayDeque()
        fun note(entry: String) {
            log.addLast(entry)
            if (log.size > 40) log.removeFirst()
        }

        var currentStep = 0

        fun advance(requested: Duration) {
            var by: Duration = requested
            if (now + by > TOTAL) by = TOTAL - now
            clock += by
            now += by
            note("+${by.inWholeMilliseconds}ms")
        }

        suspend fun postFrame(id: String, frame: LocalNotification) {
            val where = "seed $seed step $currentStep virtual ${now.inWholeMinutes} min"
            val result: NotificationResult = notifier.post(id, frame)
            note("post($id,${frame.title}/${frame.body}/${frame.channel.id},${frame.progress})=${result::class.simpleName}")
            val context = "$where. Last steps: ${log.joinToString("; ")}"
            when {
                host.permissionGate && !status.isUsable -> {
                    assertEquals(NotificationResult.PermissionDenied, result, "$context: permission is not usable")
                    tally.denied++
                }

                host.hasAppSwitch && !enabled -> {
                    assertEquals(NotificationResult.NotificationsDisabled, result, "$context: app switch is off")
                    tally.disabled++
                }

                else -> judge(frame, id, result, baselines, now, tally, context)
            }
            if (result == NotificationResult.Posted) tray[id] = frame.title to frame.body
        }

        repeat(steps) { step: Int ->
            currentStep = step
            val where = "seed $seed step $step virtual ${now.inWholeMinutes} min"
            when (random.nextInt(18)) {
                0 -> {
                    val id: String = IDS.random(random)
                    notifier.cancel(id)
                    tray.remove(id)
                    baselines.remove(id)
                    note("cancel($id)")
                }

                1 -> if (random.nextInt(3) == 0) {
                    notifier.cancelAll()
                    tray.clear()
                    baselines.clear()
                    note("cancelAll")
                } else {
                    notifier = host.newNotifier(clock)
                    baselines.clear()
                    tally.restarts++
                    note("restart")
                }

                2 -> {
                    status = STATUSES.random(random)
                    host.setPermission(status)
                    note("permission=$status")
                }

                3 -> if (host.hasAppSwitch) {
                    enabled = !enabled
                    host.setAppSwitch(enabled)
                    note("switch=$enabled")
                }

                4, 5, 6 -> advance(TICKS.random(random))

                7, 8 -> {
                    // A scripted probe of the abandoned-run horizon on an id that has a baseline: first a frame inside
                    // the same bucket after a short or long quiet stretch (a suppressed frame is a sign of life), then
                    // another after a stretch around ten minutes, repeating the very same frame both times.
                    val id: String? = IDS.filter { it in baselines && it in lastLook }.randomOrNull(random)
                    if (id != null) {
                        val base: Baseline = baselines.getValue(id)
                        val look: Triple<String, String, Int> = lastLook.getValue(id)
                        val sameBucket = LocalNotification(
                            title = look.first,
                            body = look.second,
                            channel = CHANNELS[look.third],
                            progress = NotificationProgress.Determinate(base.bucket + random.nextInt(0, BUCKET)),
                        )
                        advance(LEAD_TICKS.random(random))
                        postFrame(id, sameBucket)
                        advance(PROBE_TICKS.random(random))
                        postFrame(id, sameBucket)
                    }
                }

                else -> {
                    val id: String = IDS.random(random)
                    val progress: NotificationProgress? = when (random.nextInt(20)) {
                        0 -> null
                        1 -> NotificationProgress.Indeterminate
                        2, 3 -> NotificationProgress.Determinate(100)
                        4, 5, 6 -> NotificationProgress.Determinate(random.nextInt(0, 101))
                        else -> NotificationProgress.Determinate(
                            ((lastPercent[id] ?: 0) + random.nextInt(-3, 8)).coerceIn(0, 99),
                        )
                    }
                    (progress as? NotificationProgress.Determinate)?.let { lastPercent[id] = it.percent }
                    // A run mostly repeats its own text, so frames land in the same bucket with the same content.
                    val look: Triple<String, String, Int> = lastLook[id]?.takeIf { random.nextInt(10) < 8 }
                        ?: Triple("t${random.nextInt(2)}", "b${random.nextInt(2)}", if (random.nextInt(8) == 0) 1 else 0)
                    lastLook[id] = look
                    postFrame(
                        id,
                        LocalNotification(
                            title = look.first,
                            body = look.second,
                            channel = CHANNELS[look.third],
                            progress = progress,
                        ),
                    )
                }
            }
            val actual: Map<String, Pair<String, String>> = host.shown(IDS)
            assertEquals(
                tray.toMap(), actual,
                "$where: what the user sees differs from the model. Last steps: ${log.joinToString("; ")}",
            )
        }
        tally.finalVirtualTime = maxOf(tally.finalVirtualTime, now)
    }

    /** Decides the coalescing answer, accepting both answers inside the undocumented band, and updates the baseline. */
    private fun judge(
        frame: LocalNotification,
        id: String,
        result: NotificationResult,
        baselines: MutableMap<String, Baseline>,
        now: Duration,
        tally: Tally,
        context: String,
    ) {
        val progress: NotificationProgress? = frame.progress
        val percent: Int? = (progress as? NotificationProgress.Determinate)?.percent?.coerceIn(0, 100)
        if (percent == null || percent == 100) {
            assertEquals(NotificationResult.Posted, result, "$context: a terminal or indeterminate frame is never suppressed")
            baselines.remove(id)
            tally.posted++
            return
        }
        val previous: Baseline? = baselines[id]
        val quiet: Duration = if (previous == null) Duration.ZERO else now - previous.lastSeen
        val bucket: Int = (percent / BUCKET) * BUCKET
        val content: LocalNotification = frame.copy(progress = null)

        fun remembered(): NotificationResult {
            val base: Baseline = previous ?: return NotificationResult.Posted
            if (now - base.at < MIN_INTERVAL) return NotificationResult.Coalesced
            return if (base.bucket == bucket && base.content == content) NotificationResult.Coalesced else NotificationResult.Posted
        }

        val expectedRemembered: NotificationResult = remembered()
        val expectedForgotten: NotificationResult = NotificationResult.Posted
        val accepted: Set<NotificationResult> = when {
            previous == null || quiet <= HORIZON -> setOf(expectedRemembered)
            quiet > HORIZON_BAND_END -> setOf(expectedForgotten)
            else -> setOf(expectedRemembered, expectedForgotten)
        }
        if (previous != null && quiet > HORIZON && expectedRemembered != expectedForgotten) tally.horizonDecisive++
        assertTrue(
            result in accepted,
            "$context: answered $result, the contract allows $accepted (quiet ${quiet.inWholeSeconds} s, bucket $bucket)",
        )
        if (previous != null && quiet > HORIZON && result == NotificationResult.Posted && expectedRemembered == NotificationResult.Coalesced) {
            tally.horizonForgotten++
        }
        when (result) {
            NotificationResult.Coalesced -> {
                tally.coalesced++
                previous?.lastSeen = now
            }

            else -> {
                tally.posted++
                baselines[id] = Baseline(bucket, content, now, now)
            }
        }
    }
}
