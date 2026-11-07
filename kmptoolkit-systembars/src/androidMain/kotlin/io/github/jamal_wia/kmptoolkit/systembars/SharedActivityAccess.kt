package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Application
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess

/**
 * The one [ActivityAccess] that every `Context`-based factory of this module shares.
 *
 * Each `createActivityAccess` call registers a new `ActivityLifecycleCallbacks` on the
 * `Application`, and nothing the factories could do would ever unregister it: the `Application`
 * outlives every controller, and a controller cannot know whether another one still uses the
 * access. Creating a controller per screen or per session would therefore add one callback per
 * creation for the rest of the process. Sharing one tracker per `Application` bounds that to one,
 * whatever the creation pattern, and no controller releases it.
 *
 * A single slot rather than a map: the value (the tracker) strongly references its key (the
 * `Application`), so a weak map would never drop an entry. A process has one `Application`; a test
 * runner that builds a new one per test simply replaces the slot, and the old tracker is left to
 * die with its old `Application`.
 */
internal object SharedActivityAccess {

    private var slot: Pair<Application, ActivityAccess>? = null

    // Synchronized: called from non-suspending factories on any thread, and creating the access registers
    // lifecycle callbacks - a side effect a CAS retry could run twice.
    /** The shared access for [application], created and registered on first use. */
    @Synchronized
    fun forApplication(application: Application): ActivityAccess {
        slot?.let { (owner, access) -> if (owner === application) return access }
        return createActivityAccess(application).also { slot = application to it }
    }
}
