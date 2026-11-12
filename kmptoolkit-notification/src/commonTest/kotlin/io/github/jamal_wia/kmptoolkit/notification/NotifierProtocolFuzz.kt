package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.isUsable
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A permission controller whose answer a test changes between calls. */
internal class MutablePermissions(var status: PermissionStatus = PermissionStatus.Granted) : PermissionController {
    override suspend fun check(permission: Permission): PermissionStatus = status
    override suspend fun request(permission: Permission): PermissionStatus = status
    override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
        permissions.associateWith { status }

    override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(status)
    override fun openSettings(permission: Permission): Boolean = false
}

/**
 * What a platform has to offer the shared protocol fuzzer: a notifier built over one shared "system"
 * state (so a restart is a second notifier over the same tray), the permission the notifier sees, the
 * app-wide switch where the platform has one, and a read of what the user is shown.
 */
internal interface NotifierFuzzHost {

    /** False where the platform never asks the permission controller (Android before 13). */
    val permissionGate: Boolean

    /** True where the user can switch all notifications of the app off (Android); iOS folds it into permission. */
    val hasAppSwitch: Boolean

    /** Clears the system state: nothing shown, switch on, permission granted. */
    fun reset()

    fun newNotifier(clock: TestTimeSource): Notifier

    fun setPermission(status: PermissionStatus)

    fun setAppSwitch(enabled: Boolean)

    /** `id` to (title, body) of what the system shows for the ids the fuzzer uses. */
    fun shown(ids: List<String>): Map<String, Pair<String, String>>
}

/**
 * One reference model of the documented posting contract (docs 03 and 04), shared by both platforms:
 * the same seeded operation sequences must give the same answers and leave the same notifications
 * showing on Android and on iOS. The model knows nothing of tickets, claims or locks; it holds the
 * last posted frame per id and when it was posted. The seed is in every failure message.
 */
internal object NotifierProtocolFuzz {

    private val IDS: List<String> = listOf("a", "b", "c")
    private val BUCKET: Int = 10
    private val MIN_INTERVAL: Duration = 500.milliseconds
    private val TICKS: List<Duration> = listOf(0, 1, 499, 500, 501, 2_000).map { it.milliseconds }
    private val PERCENTS: List<Int> = listOf(-5, 0, 9, 10, 19, 20, 99, 100, 101, Int.MAX_VALUE, Int.MIN_VALUE)
    private val STATUSES: List<PermissionStatus> = listOf(
        PermissionStatus.Granted,
        PermissionStatus.Limited,
        PermissionStatus.Denied(),
        PermissionStatus.Denied(explainFirst = true),
        PermissionStatus.PermanentlyDenied,
        PermissionStatus.NotDetermined,
        PermissionStatus.Restricted,
        PermissionStatus.Unavailable,
    )
    private val CHANNELS: List<NotificationChannelSpec> = listOf(
        NotificationChannelSpec("c0", "C0"),
        NotificationChannelSpec("c1", "C1", importance = NotificationImportance.High),
    )
    private val ACTIONS: List<List<NotificationAction>> = listOf(
        emptyList(),
        listOf(NotificationAction("p", "Pause")),
        listOf(NotificationAction("p", "Pause"), NotificationAction("c", "Cancel")),
    )
    private val EXTRAS: List<Map<String, String>?> = listOf(null, mapOf("s" to "a"), mapOf("s" to "b"))

    private data class Baseline(val bucket: Int, val content: LocalNotification, val at: Duration)

    suspend fun run(host: NotifierFuzzHost, seeds: Int, steps: Int) {
        repeat(seeds) { seed: Int -> runSeed(host, seed, steps) }
    }

    private suspend fun runSeed(host: NotifierFuzzHost, seed: Int, steps: Int) {
        val random = Random(seed)
        host.reset()
        val clock = TestTimeSource()
        var notifier: Notifier = host.newNotifier(clock)
        val tray: MutableMap<String, Pair<String, String>> = mutableMapOf()
        val baselines: MutableMap<String, Baseline> = mutableMapOf()
        var now: Duration = Duration.ZERO
        var status: PermissionStatus = PermissionStatus.Granted
        var enabled = true
        val log = StringBuilder()

        repeat(steps) { step: Int ->
            val where = "seed $seed step $step"
            when (random.nextInt(14)) {
                0 -> {
                    val id: String = IDS.random(random)
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
                    notifier = host.newNotifier(clock)
                    baselines.clear()
                    log.append("restart; ")
                }

                2 -> {
                    status = STATUSES.random(random)
                    host.setPermission(status)
                    log.append("permission=$status; ")
                }

                3 -> if (host.hasAppSwitch) {
                    enabled = !enabled
                    host.setAppSwitch(enabled)
                    log.append("switch=$enabled; ")
                }

                4, 5 -> {
                    val by: Duration = TICKS.random(random)
                    clock += by
                    now += by
                    log.append("+${by.inWholeMilliseconds}ms; ")
                }

                else -> {
                    val id: String = IDS.random(random)
                    val progress: NotificationProgress? = when (random.nextInt(8)) {
                        0 -> null
                        1 -> NotificationProgress.Indeterminate
                        2 -> NotificationProgress.Determinate(PERCENTS.random(random))
                        else -> NotificationProgress.Determinate(random.nextInt(0, 101))
                    }
                    val frame = LocalNotification(
                        title = "t${random.nextInt(2)}",
                        body = "b${random.nextInt(2)}",
                        channel = CHANNELS.random(random),
                        progress = progress,
                        actions = ACTIONS.random(random),
                        contentExtras = EXTRAS.random(random),
                        ongoing = random.nextInt(4) == 0,
                        iosCategoryId = if (random.nextInt(4) == 0) "cat" else null,
                    )
                    val result: NotificationResult = notifier.post(id, frame)
                    log.append("post($id,${frame.title}/${frame.body}/${frame.channel.id}/${frame.actions.size}/${frame.contentExtras}/${frame.ongoing}/${frame.iosCategoryId},$progress)=${result::class.simpleName}; ")
                    val expected: NotificationResult = when {
                        host.permissionGate && !status.isUsable -> NotificationResult.PermissionDenied
                        host.hasAppSwitch && !enabled -> NotificationResult.NotificationsDisabled
                        else -> decide(frame, baselines[id], now)
                    }
                    assertEquals(expected, result, "$where: the answer differs from the contract. Steps: $log")
                    if (result == NotificationResult.Posted) {
                        tray[id] = frame.title to frame.body
                        val determinate: Int? =
                            (frame.progress as? NotificationProgress.Determinate)?.percent?.coerceIn(0, 100)
                        if (determinate == null || determinate == 100) {
                            baselines.remove(id)
                        } else {
                            baselines[id] = Baseline((determinate / BUCKET) * BUCKET, frame.copy(progress = null), now)
                        }
                    }
                }
            }
            val actual: Map<String, Pair<String, String>> = host.shown(IDS)
            assertEquals(tray.toMap(), actual, "$where: what the user sees differs from the model. Steps: $log")
        }
    }

    private fun decide(frame: LocalNotification, baseline: Baseline?, now: Duration): NotificationResult {
        val progress: NotificationProgress? = frame.progress
        if (progress !is NotificationProgress.Determinate) return NotificationResult.Posted
        val percent: Int = progress.percent.coerceIn(0, 100)
        if (percent == 100) return NotificationResult.Posted
        val previous: Baseline = baseline ?: return NotificationResult.Posted
        if (now - previous.at < MIN_INTERVAL) return NotificationResult.Coalesced
        val same: Boolean =
            previous.bucket == (percent / BUCKET) * BUCKET && previous.content == frame.copy(progress = null)
        return if (same) NotificationResult.Coalesced else NotificationResult.Posted
    }

    fun newCoalescer(clock: TestTimeSource): ProgressCoalescer =
        ProgressCoalescer(bucketPercent = BUCKET, minInterval = MIN_INTERVAL, timeSource = clock)
}
