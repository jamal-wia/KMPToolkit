package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger

/**
 * An activity that records every start made from it, instead of handing it to Robolectric.
 *
 * Robolectric's `shadowOf(activity)` and `shadowOf(application)` read one shared queue of started
 * activities, so they cannot tell which context started a screen. This can: a start recorded here was
 * made from this activity, and a start in the application's queue was not.
 */
class RecordingActivity : Activity() {

    /** Every intent started from this activity, in order. */
    val started: MutableList<Intent> = mutableListOf()

    /** Actions this activity's `startActivity` refuses with `ActivityNotFoundException`, after recording. */
    var unresolvable: Set<String> = emptySet()

    override fun startActivity(intent: Intent, options: Bundle?) {
        started += Intent(intent)
        if (intent.action in unresolvable) throw ActivityNotFoundException("No activity for ${intent.action}")
    }
}

/** An [ActivityAccess] that always reports [activity] as the resumed one. */
class FixedActivityAccess(private val activity: Activity) : ActivityAccess {

    override fun <R> withActivity(block: (Activity) -> R): R = block(activity)

    override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
        listener(activity)
        return object : ActivitySubscription {
            override fun cancel() = Unit
        }
    }

    override fun release() = Unit
}

/** A [Logger] that keeps every entry. */
class RecordingLogger : Logger {

    data class Entry(val level: LogLevel, val throwable: Throwable?, val message: String)

    val entries: MutableList<Entry> = mutableListOf()

    override val tag: String = "RecordingLogger"

    override fun isLoggable(level: LogLevel): Boolean = true

    override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
        entries += Entry(level, throwable, message())
    }
}
