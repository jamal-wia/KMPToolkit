package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * One `prepare()` attempt's claim on an output path. A plain class on purpose: claims are compared
 * by identity, so two attempts on the same path can never be mistaken for each other.
 */
internal class OutputPathClaim

/**
 * In-process registry of who may still delete a file at an explicit output path, process-wide
 * because the two recorders that meet on one path (a released screen's and the next screen's) are
 * different objects that share nothing else.
 *
 * Why it exists: a `prepare()` that was released while the engine was still opening the file
 * finishes later and undoes its preparation by deleting the file it created. By then the next
 * recorder may have taken the same explicit path and finished a recording there, and the late
 * delete would destroy it. The file system seam cannot tell the two files apart, so ownership is
 * tracked instead: every attempt [claim]s its path before it looks at the file, a later claim on the
 * same path takes the path over (that is the handoff), and a tail deletes through [deleteIfOwner]
 * only while its claim is still the current one. An attempt that never finishes its tail never
 * blocks the next one, because a claim is taken over rather than waited for.
 *
 * A claim lives only as long as the `prepare()` call that took it ([release] in its `finally`), so
 * the registry never grows with the number of recordings.
 *
 * The state is a [MutableStateFlow] holding an immutable map (no lock, no atomic). The one step
 * that has to be atomic with the ownership check is the delete itself: [deleteIfOwner] first swaps
 * the entry to a `deleting` marker with compareAndSet, and a [claim] that meets the marker suspends
 * until the delete is over, so a claim and a delete can never interleave.
 */
internal object OutputPathOwners {

    /** Immutable and compared by identity; [deleting] is true only while a delete is in progress. */
    private class Slot(val claim: OutputPathClaim, val deleting: Boolean)

    private val slots: MutableStateFlow<Map<String, Slot>> = MutableStateFlow(emptyMap())

    /**
     * Registers [claim] as the owner of [path], taking it over from any earlier claim, after waiting
     * out a delete that is running on the path right now.
     */
    suspend fun claim(path: String, claim: OutputPathClaim) {
        while (true) {
            val current: Map<String, Slot> = slots.value
            if (current[path]?.deleting == true) {
                slots.first { map: Map<String, Slot> -> map[path]?.deleting != true }
                continue
            }
            if (slots.compareAndSet(current, current + (path to Slot(claim, deleting = false)))) return
        }
    }

    /**
     * Runs [delete] only if [claim] still owns [path]; returns whether it ran. Never suspends, so
     * it can be called with the recorder's state lock held. [delete] must be a quick best-effort
     * call (it is `RecordingFileSystem.delete`).
     */
    fun deleteIfOwner(path: String, claim: OutputPathClaim, delete: () -> Unit): Boolean {
        while (true) {
            val current: Map<String, Slot> = slots.value
            val slot: Slot = current[path] ?: return false
            if (slot.claim !== claim || slot.deleting) return false
            val marker = Slot(claim, deleting = true)
            if (!slots.compareAndSet(current, current + (path to marker))) continue
            try {
                delete()
            } finally {
                slots.update { map: Map<String, Slot> -> if (map[path] === marker) map - path else map }
            }
            return true
        }
    }

    /** Drops every entry [claim] still holds; entries taken over by a later claim stay. */
    fun release(claim: OutputPathClaim) {
        slots.update { map: Map<String, Slot> ->
            if (map.values.any { slot: Slot -> slot.claim === claim }) {
                map.filterValues { slot: Slot -> slot.claim !== claim }
            } else {
                map
            }
        }
    }
}
