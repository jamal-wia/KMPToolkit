@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.core.StateMachineLock
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the fakes use to record calls and to count live collectors, from any thread.
 *
 * Two things, each with the cheapest correct tool:
 * - the list of entries sits behind the suite's [StateMachineLock] (the same primitive the players
 *   and the recorder share), because an append must be O(1): a call log of an 80 000-call storm
 *   copied on every append (a `MutableStateFlow<List>`) is quadratic, and its compare-and-set retries
 *   under eight writers make it worse. The lock guards only plain list operations: it is never held
 *   across a suspension point and never across a callback into foreign code (values are copied out
 *   before anyone reads them, and no flow is touched inside the section);
 * - the count is a `MutableStateFlow<Int>` moved with `update {}` (a lock-free compare-and-set), so
 *   the public `StateFlow<Int>` a test awaits is exact even when many threads add and remove at
 *   once, and its collectors are never resumed from inside the lock.
 *
 * The list and the count are exact once the threads that touched them have finished, and each is
 * exact on its own at every moment; between two racing calls they can disagree by the calls in
 * flight. [add] and [remove] both change the list first and the count second.
 */
internal class FakeRecorder<T> {

    private val lock: StateMachineLock = StateMachineLock()
    private val entries: ArrayList<Entry<T>> = ArrayList()
    private val live: MutableStateFlow<Int> = MutableStateFlow(0)

    /** Entries currently recorded (added and not yet removed). */
    val count: StateFlow<Int> = live.asStateFlow()

    /** Records [value]; hand the result to [remove] to take it out again. */
    fun add(value: T): Entry<T> {
        val entry: Entry<T> = Entry(value)
        lock.exclusive { entries.add(entry) }
        live.update { it + 1 }
        return entry
    }

    /** Takes [entry] out; a second call for the same entry does nothing (release is idempotent). */
    fun remove(entry: Entry<T>) {
        val removed: Boolean = lock.exclusive { entries.remove(entry) }
        if (removed) live.update { it - 1 }
    }

    /** A copy of the recorded values, oldest first. */
    fun snapshot(): List<T> = lock.exclusive { entries.map { it.value } }

    /** One recorded value; compared by identity, so two equal values are two entries. */
    class Entry<T>(val value: T)
}
