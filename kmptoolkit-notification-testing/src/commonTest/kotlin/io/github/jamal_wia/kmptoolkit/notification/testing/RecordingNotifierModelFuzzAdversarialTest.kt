package io.github.jamal_wia.kmptoolkit.notification.testing

import io.github.jamal_wia.kmptoolkit.notification.LocalNotification
import io.github.jamal_wia.kmptoolkit.notification.NotificationAction
import io.github.jamal_wia.kmptoolkit.notification.NotificationChannelSpec
import io.github.jamal_wia.kmptoolkit.notification.NotificationIcon
import io.github.jamal_wia.kmptoolkit.notification.NotificationOptions
import io.github.jamal_wia.kmptoolkit.notification.NotificationResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * A seeded operation-sequence fuzzer for the recording double against a plain model of its documented
 * contract (KDoc of [RecordingNotifier]): every post attempt is recorded in order whatever the result,
 * only Posted changes what is showing, cancel and cancelAll apply to showing and are recorded even for
 * unknown ids, clear drops everything but the result, a blank id is rejected and records nothing, and
 * every recording is a snapshot taken at the call that later edits of the caller's list and maps never
 * reach. The seed is in every failure message.
 */
class RecordingNotifierModelFuzzAdversarialTest {

    private data class Call(val id: String, val notification: LocalNotification, val options: NotificationOptions)

    private val results: List<NotificationResult> = listOf(
        NotificationResult.Posted,
        NotificationResult.Posted,
        NotificationResult.Coalesced,
        NotificationResult.PermissionDenied,
        NotificationResult.NotificationsDisabled,
        NotificationResult.ChannelBlocked("c"),
        NotificationResult.Failed(IllegalStateException("x")),
    )

    @Test
    fun `no sequence of posts cancels clears and caller edits makes the recordings disagree with the contract`() = runTest {
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            val notifier = RecordingNotifier()
            val calls: MutableList<Call> = mutableListOf()
            val showing: MutableMap<String, LocalNotification> = mutableMapOf()
            val cancelled: MutableList<String> = mutableListOf()
            var cancelAlls = 0
            val log = StringBuilder()
            repeat(STEPS) { step: Int ->
                val where = "seed $seed step $step"
                when (random.nextInt(10)) {
                    0, 1, 2, 3, 4 -> {
                        val id: String = IDS.random(random)
                        val result: NotificationResult = results.random(random)
                        notifier.result = result
                        val actions: MutableList<NotificationAction> =
                            MutableList(random.nextInt(3)) { NotificationAction("a$it", "A$it") }
                        val extras: MutableMap<String, String>? =
                            if (random.nextBoolean()) mutableMapOf("k" to "v${random.nextInt(3)}") else null
                        val icons: MutableMap<String, NotificationIcon> = mutableMapOf("a0" to NotificationIcon.AndroidDrawable(1))
                        val notification = LocalNotification(
                            title = "t$step", body = "b", channel = NotificationChannelSpec("c", "C"),
                            actions = actions, contentExtras = extras,
                        )
                        val options: NotificationOptions? =
                            if (random.nextBoolean()) NotificationOptions(alertOnce = random.nextBoolean(), actionIcons = icons) else null
                        val answer: NotificationResult =
                            if (options == null) notifier.post(id, notification) else notifier.post(id, notification, options)
                        log.append("post($id,$result,options=${options != null}); ")
                        assertEquals(result, answer, "$where: the answer. Steps: $log")
                        val snapshot: LocalNotification =
                            notification.copy(actions = actions.toList(), contentExtras = extras?.toMap())
                        val optionsAtCall: NotificationOptions =
                            options?.let { NotificationOptions(it.alertOnce, it.dismissAction, it.mediaStyle, it.actionIcons.toMap()) }
                                ?: NotificationOptions.DEFAULT
                        calls += Call(id, snapshot, optionsAtCall)
                        if (result == NotificationResult.Posted) showing[id] = snapshot
                        // The caller reuses and edits its own collections after the call.
                        actions += NotificationAction("late", "Late")
                        extras?.put("late", "edit")
                        icons["late"] = NotificationIcon.Default
                    }

                    5, 6 -> {
                        val id: String = (IDS + "never-posted").random(random)
                        notifier.cancel(id)
                        showing.remove(id)
                        cancelled += id
                        log.append("cancel($id); ")
                    }

                    7 -> {
                        notifier.cancelAll()
                        showing.clear()
                        cancelAlls++
                        log.append("cancelAll; ")
                    }

                    8 -> if (random.nextInt(3) == 0) {
                        notifier.clear()
                        calls.clear()
                        showing.clear()
                        cancelled.clear()
                        cancelAlls = 0
                        log.append("clear; ")
                    }

                    else -> {
                        val blank: String = listOf("", " ", "\t", "\n ").random(random)
                        assertFailsWith<IllegalArgumentException>("$where: a blank id '$blank' must be rejected. Steps: $log") {
                            notifier.post(blank, LocalNotification("t", "b", NotificationChannelSpec("c", "C")))
                        }
                        log.append("blank; ")
                    }
                }
                assertEquals(calls.map { it.id to it.notification }, notifier.posted.map { it.id to it.notification }, "$where: posted. Steps: $log")
                assertEquals(calls.map { it.options }, notifier.posted.map { it.options }, "$where: options of the posts. Steps: $log")
                assertEquals(showing.toMap(), notifier.showing, "$where: showing. Steps: $log")
                assertEquals(cancelled.toList(), notifier.cancelled, "$where: cancelled. Steps: $log")
                assertEquals(cancelAlls, notifier.cancelAllCount, "$where: cancelAll count. Steps: $log")
            }
        }
    }

    private companion object {
        const val SEEDS: Int = 300
        const val STEPS: Int = 60
        val IDS: List<String> = listOf("a", "b", "c", "d e")
    }
}
