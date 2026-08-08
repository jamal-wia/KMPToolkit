package io.github.jamal_wia.kmptoolkit.scheduler

/**
 * Process-wide handle from the OS-instantiated [AlarmReceiver] back to the handlers and identifiers
 * the app configured.
 *
 * A `BroadcastReceiver` is constructed by the framework with a no-argument constructor, so there is
 * no way to hand it a dependency — some process-scoped registration is unavoidable for any library
 * that ships its own receiver. Keeping that registration here, internal and tiny, is the smallest
 * version of it: the public API stays a factory function, and the only way to populate this is to
 * create a scheduler.
 *
 * Last writer wins. Creating a second scheduler with different handlers replaces the first
 * registration rather than merging, because two live registrations would make dispatch depend on
 * construction order — pass every handler to one `createAlarmScheduler` call instead.
 */
internal object AlarmDispatch {

    @Volatile
    private var registration: Registration? = null

    /** Installed by `createAlarmScheduler`, read by [AlarmReceiver]. */
    fun install(keys: AlarmIntentKeys, handlers: List<AlarmHandler>) {
        registration = Registration(keys, handlers.toList())
    }

    /** `null` until a scheduler has been created in this process. */
    fun current(): Registration? = registration

    /** Test seam: drops the registration so one test cannot see another's handlers. */
    fun reset() {
        registration = null
    }

    data class Registration(
        val keys: AlarmIntentKeys,
        val handlers: List<AlarmHandler>,
    )
}
