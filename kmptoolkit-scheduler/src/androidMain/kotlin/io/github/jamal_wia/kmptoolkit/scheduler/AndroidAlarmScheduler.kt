package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

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
 * @param context any context; the application context is taken from it.
 * @param handlerProvider returns the handlers to choose from, one per [ScheduledAlarm.type].
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
 * [ScheduledAlarm.id] through a per-id data URI, so re-scheduling one id replaces its alarm
 * (`FLAG_UPDATE_CURRENT`) instead of stacking a second one, and two ids never collapse into one.
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

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override suspend fun schedule(alarm: ScheduledAlarm): AlarmScheduleResult {
        val manager: AlarmManager = alarmManager
            ?: return AlarmScheduleResult.Failed(AlarmFailure.SchedulerUnavailable)
        return try {
            arm(manager, alarm)
        } catch (failure: RuntimeException) {
            // AlarmManager refuses in ways a caller cannot prevent: since Android 12 an app holding
            // too many alarms at once (the limit is 500) gets an IllegalStateException from every
            // set call, inexact ones included. "Never throws for a platform refusal" is the contract,
            // so the refusal becomes a result the caller can act on instead of a crash.
            AlarmScheduleResult.Failed(AlarmFailure.PlatformError(failure.message))
        }
    }

    private fun arm(manager: AlarmManager, alarm: ScheduledAlarm): AlarmScheduleResult {
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(alarm.id),
            AlarmIntents.toIntent(context, alarm, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

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
        // FLAG_NO_CREATE resolves the PendingIntent armed earlier — matched on component and data
        // URI, extras ignored — without creating one if there is none.
        val existing: PendingIntent? = PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(id),
            AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (existing != null) {
            manager.cancel(existing)
            existing.cancel()
        }
    }

    override suspend fun cancelAll(ids: Collection<String>) {
        ids.forEach { cancel(it) }
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
}
