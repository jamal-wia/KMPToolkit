package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Creates the Android [AlarmScheduler], backed by `AlarmManager`, and registers [handlers] for the
 * alarms it will later fire.
 *
 * **Call this from `Application.onCreate`.** Registering the handlers is deliberately part of
 * creating the scheduler — a `BroadcastReceiver` is instantiated by the framework and cannot be
 * given constructor dependencies, so the handlers have to reach it through process-scoped state,
 * and tying that to construction means there is exactly one call to get right instead of two that
 * can drift apart. An alarm may fire in a freshly created process where nothing but
 * `Application.onCreate` has run; if the scheduler is created later than that — lazily, when a
 * screen opens — the alarm arrives before any handler is registered and is dropped.
 *
 * ```kotlin
 * class App : Application() {
 *     lateinit var scheduler: AlarmScheduler
 *     override fun onCreate() {
 *         super.onCreate()
 *         scheduler = createAlarmScheduler(this, handlers = listOf(ReminderHandler(this)))
 *     }
 * }
 * ```
 *
 * The [context] is not retained: only its application context is, so passing an `Activity` here
 * leaks nothing.
 *
 * @param context any context; the application context is taken from it.
 * @param handlers one handler per [ScheduledAlarm.type] you schedule. Passing none is legal and
 *   means every fired alarm is dropped — useful only if you never schedule anything. When a handler
 *   needs the scheduler itself, use the `handlerProvider` overload instead.
 * @param config identifiers this module writes into intents; see [AlarmSchedulerConfig].
 */
public fun createAlarmScheduler(
    context: Context,
    handlers: List<AlarmHandler> = emptyList(),
    config: AlarmSchedulerConfig = AlarmSchedulerConfig(),
): AlarmScheduler {
    val appContext: Context = context.applicationContext
    val keys: AlarmIntentKeys = AlarmIntentKeys.from(config, appContext.packageName)
    AlarmDispatch.install(keys, handlers)
    return AndroidAlarmScheduler(appContext, keys)
}

/**
 * Creates the Android [AlarmScheduler] with handlers that are looked up when an alarm fires rather
 * than handed over up front.
 *
 * The same scheduler as the list overload, for the one situation that overload cannot express:
 * handlers that depend on the scheduler themselves. A handler that re-arms the next occurrence of a
 * repeating reminder needs an [AlarmScheduler]; the scheduler, created with a list, needs that
 * handler first. In a DI container this is a cycle that fails at resolution time. A provider breaks
 * it — the scheduler can be created, and exposed, before any handler exists:
 *
 * ```kotlin
 * single<AlarmScheduler> {
 *     createAlarmScheduler(androidContext(), handlerProvider = { getAll<AlarmHandler>() })
 * }
 * single { NextReminderHandler(scheduler = get()) } bind AlarmHandler::class
 * ```
 *
 * [handlerProvider] is called on a background thread each time an alarm fires, never during this
 * call, and whatever it returns then decides which handler runs. It must therefore be cheap to call
 * repeatedly (a DI lookup of singletons is) and must not throw; an exception from it drops the alarm.
 *
 * **Call this from `Application.onCreate`**, exactly as the list overload: an alarm that fires into a
 * process where no scheduler has been created yet is dropped, provider or not.
 *
 * The provider is held for the life of the process (the alarm receiver is built by the system with no
 * arguments, so it has nowhere else to find it), and the latest scheduler created wins. It must not
 * capture an `Activity` or any other UI object.
 *
 * @param context any context; the application context is taken from it.
 * @param handlerProvider returns the handlers to choose from, one per [ScheduledAlarm.type]; lives for
 *   the whole process, so it must not capture UI objects.
 * @param config identifiers this module writes into intents; see [AlarmSchedulerConfig].
 */
public fun createAlarmScheduler(
    context: Context,
    handlerProvider: () -> Collection<AlarmHandler>,
    config: AlarmSchedulerConfig = AlarmSchedulerConfig(),
): AlarmScheduler {
    val appContext: Context = context.applicationContext
    val keys: AlarmIntentKeys = AlarmIntentKeys.from(config, appContext.packageName)
    AlarmDispatch.install(keys, handlerProvider)
    return AndroidAlarmScheduler(appContext, keys)
}

/**
 * `AlarmManager` implementation.
 *
 * Each alarm becomes a broadcast `PendingIntent` targeting [AlarmReceiver], distinct per
 * [ScheduledAlarm.id] through a per-id data URI, so re-scheduling one id replaces its alarm instead
 * of stacking a second one, and two ids never collapse into one. An id owns a few `PendingIntent`
 * slots (request codes): a replacement is built in a slot no alarm occupies and the old slots are
 * retired only once the new alarm is armed, so a replacement the platform refuses leaves the armed
 * alarm, and the extras it will deliver, exactly as they were.
 *
 * Exact scheduling is attempted whenever the OS allows it and degrades to an inexact alarm — never
 * to no alarm — when it does not. Both outcomes are reported through [AlarmScheduleResult]; see
 * `docs/kmptoolkit-scheduler/05-platform-notes.md` for the permission that decides which one you
 * get.
 */
internal class AndroidAlarmScheduler(
    private val context: Context,
    private val keys: AlarmIntentKeys,
) : AlarmScheduler {

    /**
     * The platform service, or `null` when it cannot be reached: the lookup answered null, or threw
     * (a system server that is restarting under the app). A throwing lookup is a platform refusal
     * like any other, so it is logged and treated as unreachable; the next call looks it up again.
     */
    private val alarmManager: AlarmManager?
        get() = try {
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        } catch (refusal: RuntimeException) {
            Log.w(LOG_TAG, "The platform alarm service could not be reached.", refusal)
            null
        }

    override suspend fun schedule(alarm: ScheduledAlarm): AlarmScheduleResult {
        // Copied now, not after the lock is won: the caller may edit or reuse its payload map while
        // this call waits behind another one, and the alarm armed is the one passed at the call.
        val snapshot: ScheduledAlarm = alarm.copy(payload = alarm.payload.toMap())
        val manager: AlarmManager = alarmManager
            ?: return AlarmScheduleResult.Failed(AlarmFailure.SchedulerUnavailable)
        // The guard sits inside the lock and around the non-suspending arm only: a cancellation of
        // the coroutine while it waits for the lock is a CancellationException (an
        // IllegalStateException), and must reach the caller rather than become a Failed.
        return platformCalls.withLock {
            try {
                arm(manager, snapshot)
            } catch (refusal: RuntimeException) {
                // AlarmManager refuses in ways a caller cannot prevent: since Android 12 an app holding
                // too many alarms at once (the limit is 500) gets an IllegalStateException from every
                // set call, inexact ones included; a revoked right is a SecurityException, a system
                // server that is restarting under the app a plain RuntimeException. "Never throws for
                // a platform refusal" is the contract, so every one of them becomes a result the
                // caller can act on instead of a crash.
                AlarmScheduleResult.Failed(AlarmFailure.PlatformError(refusal.message))
            }
        }
    }

    /**
     * Arms [alarm] in a slot no alarm of its id occupies, then retires every other slot. A failure
     * before the new alarm is armed leaves every occupied slot untouched (and drops the
     * `PendingIntent` this call created, which would otherwise be a leftover with no alarm).
     *
     * A slot that is already occupied is never written to: writing it would rewrite, with
     * `FLAG_UPDATE_CURRENT`, the extras of an alarm that stays armed if the replacement is then
     * refused. Slots stay occupied only after a refused retirement, so all of them are taken only
     * after [AlarmIntents.SLOT_COUNT] - 1 replacements in a row whose retirement was refused, with no successful schedule or
     * cancel of the id in between. Then the replacement is refused without touching anything
     * ([AlarmScheduleResult.Failed], documented in 04-api-reference.md): the armed alarm stays exactly
     * as it was, and `cancel(id)` (which retires every slot) lets the id schedule again.
     */
    private fun arm(manager: AlarmManager, alarm: ScheduledAlarm): AlarmScheduleResult {
        val occupied: Map<Int, PendingIntent> = SLOTS
            .mapNotNull { slot: Int -> resolve(alarm.id, slot)?.let { slot to it } }
            .toMap()
        val targetSlot: Int = SLOTS.firstOrNull { it !in occupied }
            ?: return AlarmScheduleResult.Failed(AlarmFailure.PlatformError(SATURATED_MESSAGE))
        // Not a slot and never counted toward saturation: an alarm armed by 3.0.x under the old URI.
        val retired: List<PendingIntent> = occupied.values.toList() + listOfNotNull(resolveLegacy(alarm.id))
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(alarm.id, targetSlot),
            AlarmIntents.toIntent(context, alarm, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Dropped on every exit that did not arm it, an Error (an OutOfMemoryError while the request
        // is marshalled) included: a slot left behind with no alarm behind it would count toward the
        // saturation of the id. Only the exception propagates; nothing is caught here.
        var armed: Boolean = false
        val result: AlarmScheduleResult = try {
            armIn(manager, alarm, pendingIntent).also { armed = true }
        } finally {
            if (!armed) pendingIntent.cancel()
        }
        // Each retirement on its own, so a refusal for one does not skip the others.
        for (stale: PendingIntent in retired) {
            try {
                manager.cancel(stale)
                stale.cancel()
            } catch (refusal: RuntimeException) {
                // The new alarm is armed; reporting a failure for it would be a lie. The old one may
                // fire once more, and the next successful schedule or a cancel of the id clears it.
                logCancelFailure(alarm.id, refusal)
            }
        }
        return result
    }

    private fun resolve(id: String, slot: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        AlarmIntents.requestCode(id, slot),
        AlarmIntents.cancelIntent(context, id, keys),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    )

    /** An alarm armed by 3.0.x for an id with an unpaired surrogate (old lossy URI); see [AlarmIntents.legacyCancelIntent]. */
    private fun resolveLegacy(id: String): PendingIntent? {
        val intent: Intent = AlarmIntents.legacyCancelIntent(context, id, keys) ?: return null
        return PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(id, 0),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
    }

    private fun armIn(manager: AlarmManager, alarm: ScheduledAlarm, pendingIntent: PendingIntent): AlarmScheduleResult {
        if (!canScheduleExact(manager)) {
            manager.armInexact(alarm, pendingIntent)
            return AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING)
        }
        return try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.fireAtEpochMillis, pendingIntent)
            AlarmScheduleResult.Exact
        } catch (_: SecurityException) {
            // Android 12+ lets the user revoke "Alarms & reminders" at any moment, so the
            // permission can disappear between canScheduleExactAlarms() and this call. The caller
            // asked for an alarm, not for an exact one specifically — arm what we still can and
            // report the downgrade rather than throwing away the reminder.
            manager.armInexact(alarm, pendingIntent)
            AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_REVOKED)
        }
    }

    override suspend fun cancel(id: String) {
        val manager: AlarmManager = alarmManager ?: return
        platformCalls.withLock {
            // Each slot on its own, so a refusal for one does not skip the others.
            for (slot: Int in SLOTS) {
                try {
                    // FLAG_NO_CREATE resolves the PendingIntent armed earlier — matched on request code,
                    // component and data URI, extras ignored — without creating one if there is none.
                    val existing: PendingIntent? = resolve(id, slot)
                    if (existing != null) {
                        manager.cancel(existing)
                        existing.cancel()
                    }
                } catch (refusal: RuntimeException) {
                    logCancelFailure(id, refusal)
                }
            }
            try {
                val legacy: PendingIntent? = resolveLegacy(id)
                if (legacy != null) {
                    manager.cancel(legacy)
                    legacy.cancel()
                }
            } catch (refusal: RuntimeException) {
                logCancelFailure(id, refusal)
            }
        }
    }

    /**
     * `cancel` never throws: it is a teardown call, often made on logout or from a `finally`, and
     * AlarmManager offers no recoverable failure there. The refusal is logged and the alarm may stay
     * armed — the caller cannot learn that, because `cancel` returns nothing.
     */
    private fun logCancelFailure(id: String, failure: RuntimeException) {
        Log.w(LOG_TAG, "The platform refused to cancel alarm '$id'; it may still be armed.", failure)
    }

    override suspend fun cancelAll(ids: Collection<String>) {
        // A snapshot: each cancel may suspend, and a caller's collection that changes meanwhile
        // must not break the iteration half way and leave the later ids armed.
        ids.toList().forEach { cancel(it) }
    }

    /**
     * `setAndAllowWhileIdle`, not plain `set`: plain `set` is deferred to the next Doze maintenance
     * window and can land hours late, while this still fires during Doze, rate-limited to roughly
     * once every nine minutes per app. The drift stays in minutes.
     */
    private fun AlarmManager.armInexact(alarm: ScheduledAlarm, pendingIntent: PendingIntent) {
        setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.fireAtEpochMillis, pendingIntent)
    }

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    private companion object {

        const val LOG_TAG: String = "KmpToolkitScheduler"

        const val SATURATED_MESSAGE: String =
            "Every PendingIntent slot of this alarm id is held by an alarm the platform refused to cancel; " +
                "nothing was changed. Call cancel(id) to free them."
        val SLOTS: List<Int> = (0 until AlarmIntents.SLOT_COUNT).toList()

        /**
         * Serializes every arm and cancel in the process, across every scheduler instance.
         *
         * Arming is several platform calls — resolving the id's slots, creating the new slot's
         * `PendingIntent`, which with `FLAG_UPDATE_CURRENT` rewrites the extras it carries, handing it
         * to `AlarmManager` with a time, and retiring the old slot — and cancelling is as many. Two schedules of one id interleaving those steps arm one
         * call's time carrying the other call's content, which is neither alarm the callers asked for.
         * Cancelling takes the same lock, so a cancel and a schedule of one id also apply whole, one
         * after the other, instead of interleaving. Each `createAlarmScheduler` call makes a new instance and
         * all of them share the one `AlarmManager` and the one `PendingIntent` per id, so the lock is
         * process-wide rather than per instance. One lock rather than one per id: every critical section
         * is a few quick binder calls, so contention costs nothing worth the bookkeeping of a lock table.
         *
         * A coroutine [Mutex], because the API is suspend: a waiting caller suspends instead of blocking
         * its thread, which may be the main one.
         */
        val platformCalls: Mutex = Mutex()
    }
}
