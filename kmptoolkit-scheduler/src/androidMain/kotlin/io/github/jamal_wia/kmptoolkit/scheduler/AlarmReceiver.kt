package io.github.jamal_wia.kmptoolkit.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the broadcast `AlarmManager` sends at an alarm's fire time, rebuilds the
 * [ScheduledAlarm] from the intent, and hands it to the [AlarmHandler] registered for its type.
 *
 * Declared in this module's own `AndroidManifest.xml` as `android:exported="false"`, so only
 * `PendingIntent`s this module created can trigger it. It is declared here rather than left to the
 * consumer because a forgotten `<receiver>` element produces no compile error and no runtime error
 * — only alarms that quietly never arrive.
 *
 * The alarm is dropped, silently, when the intent is not one of ours, when no scheduler has been
 * created in this process yet, or when no handler claims the alarm's type — asked at fire time, so
 * with a handler provider that is whatever the provider returns then. There is nothing else
 * to do with a fired alarm: it cannot be un-fired, retried, or reported to anyone.
 */
internal class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val registration: AlarmDispatch.Registration = AlarmDispatch.current() ?: return
        val alarm: ScheduledAlarm = AlarmIntents.fromIntent(intent, registration.keys) ?: return

        // Nullable: the framework only supplies a PendingResult while genuinely dispatching a
        // broadcast, so a directly invoked onReceive gets null here and must still run the handler.
        val pendingResult: PendingResult? = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Resolved here, at fire time and off the main thread, not when the scheduler was
                // created: a provider exists so that handlers depending on the scheduler themselves
                // can be built after it, and building them may be arbitrarily expensive.
                val handler: AlarmHandler? = try {
                    registration.handlerProvider().handlerFor(alarm.type)
                } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
                    // A provider that cannot answer — typically a DI container not started yet in a
                    // process the alarm itself created — drops this alarm, as documented. Its own
                    // failure is not the handler's, so it must not take the process down with it.
                    null
                }
                handler?.onFire(alarm)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
