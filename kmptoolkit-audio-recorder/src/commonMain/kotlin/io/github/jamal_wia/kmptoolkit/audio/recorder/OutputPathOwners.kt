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
 * The takeover is provisional until [commit]. A claim remembers the claims it displaced; while its
 * attempt is still running, or when it ends without a session (it failed, was refused, or its caller
 * was cancelled), [release] hands the path back to the claim it displaced, which may still be a live
 * recorder with a file to clean up. The attempt that succeeds calls [commit], which forgets the
 * displaced claims for good, and the commit counts even when a newer attempt is still running
 * (engines finish in any order). Why a plain stack would be wrong: a newer recorder that succeeded must
 * never hand the path back, or recorder B records, stops and is released, an older recorder A
 * becomes the owner again, and `A.release()` from a prepared state deletes B's finished recording.
 *
 * Residual, accepted: if the displaced recorder is released or cancelled while the later attempt is
 * still running, it leaves the file alone (the later attempt may be writing it) and its claim leaves
 * the registry; should that later attempt then fail, the file stays on disk. That is litter, not loss.
 *
 * A claim lives as long as the `prepare()` call that took it, or, when that call succeeds, as long as
 * the recorder's session keeps the file ([release] when the file is deleted or the recorder is
 * prepared again or released). Every later deletion of the session's file (cancel, system end of a
 * prepared recording, release of a prepared recorder, a failed start) goes through [deleteIfOwner]
 * too. The registry therefore never holds more entries than there are live recorders and in-flight
 * prepares, never one per recording.
 *
 * The state is a [MutableStateFlow] holding an immutable map (no lock, no atomic). The one step
 * that has to be atomic with the ownership check is the delete itself: [deleteIfOwner] first swaps
 * the entry to a `deleting` marker with compareAndSet, and a [claim] that meets the marker suspends
 * until the delete is over, so a claim and a delete can never interleave.
 */
internal object OutputPathOwners {

    /**
     * Immutable. [owner] is the claim that may delete; [displaced] are the claims it took the path over
     * from (oldest first) and gives it back to unless it is committed; [deleting] is true only while a
     * delete is in progress.
     */
    private class Slot(val owner: OutputPathClaim, val displaced: List<OutputPathClaim>, val deleting: Boolean)

    /** The slot after its owner leaves: the most recently displaced claim owns again, or nobody does. */
    private fun Slot.withoutOwner(): Slot? =
        if (displaced.isEmpty()) null else Slot(displaced.last(), displaced.dropLast(1), deleting = false)

    private val slots: MutableStateFlow<Map<String, Slot>> = MutableStateFlow(emptyMap())

    /**
     * The registry key of [path]: the path with repeated separators and `.` segments dropped, `..`
     * resolved lexically (dropped at the root), and letters folded to lower case.
     *
     * The key decides only whether a deletion may run, never which file is deleted: the path handed to
     * the file system stays exactly as the caller wrote it. Two different files that share a key cost at
     * most one skipped deletion (a stray file), while two spellings of one file that got different keys
     * would let a released recorder delete the next one's recording, so the normalisation leans towards
     * matching more. Folding case is why: iOS and Android's shared storage are case-insensitive, and
     * on a case-sensitive volume it only over-matches. Not covered: symbolic and hard links, and
     * Unicode normalisation differences (a real path lookup would need the file to exist).
     */
    fun ownershipKey(path: String): String {
        val segments: MutableList<String> = mutableListOf()
        for (segment: String in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return "/" + segments.joinToString("/").lowercase()
    }

    /**
     * Registers [claim] as the owner of [path], taking it over from any earlier claim, after waiting
     * out a delete that is running on the path right now.
     */
    suspend fun claim(path: String, claim: OutputPathClaim) {
        val key: String = ownershipKey(path)
        while (true) {
            val current: Map<String, Slot> = slots.value
            if (current[key]?.deleting == true) {
                slots.first { map: Map<String, Slot> -> map[key]?.deleting != true }
                continue
            }
            val old: Slot? = current[key]
            val taken = Slot(claim, old?.let { slot: Slot -> slot.displaced + slot.owner } ?: emptyList(), deleting = false)
            if (slots.compareAndSet(current, current + (key to taken))) return
        }
    }

    /**
     * Makes the takeover of [path] by [claim] final, whether [claim] owns the path or was itself
     * displaced by a newer attempt that is still running: every claim older than [claim] is forgotten,
     * so nothing ever hands the path back to them. A newer pending claim stays on top, and if it ends
     * without a session the path goes back to [claim], a live session that must still be able to delete
     * its own file. Called when [claim] becomes a recorder's session. A no-op if [claim] holds no entry
     * on the path or has nothing older left to forget.
     */
    fun commit(path: String, claim: OutputPathClaim) {
        val key: String = ownershipKey(path)
        slots.update { map: Map<String, Slot> ->
            val slot: Slot? = map[key]
            if (slot == null) {
                map
            } else if (slot.owner === claim) {
                if (slot.displaced.isEmpty()) map else map + (key to Slot(claim, emptyList(), slot.deleting))
            } else {
                val index: Int = slot.displaced.indexOf(claim)
                if (index <= 0) map else map + (key to Slot(slot.owner, slot.displaced.drop(index), slot.deleting))
            }
        }
    }

    /**
     * Runs [delete] only if [claim] still owns [path]; returns whether it ran. Never suspends, so
     * it can be called with the recorder's state lock held. [delete] must be a quick best-effort
     * call (it is `RecordingFileSystem.delete`).
     */
    fun deleteIfOwner(path: String, claim: OutputPathClaim, delete: () -> Unit): Boolean {
        val key: String = ownershipKey(path)
        while (true) {
            val current: Map<String, Slot> = slots.value
            val slot: Slot = current[key] ?: return false
            if (slot.owner !== claim || slot.deleting) return false
            val marker = Slot(claim, slot.displaced, deleting = true)
            if (!slots.compareAndSet(current, current + (key to marker))) continue
            try {
                delete()
            } finally {
                // The delete ended this claim's ownership. Only this deleter ever sets or clears the
                // flag on this slot ([claim] waits it out, [release] leaves the owner of a deleting
                // slot alone), so "deleting and still mine" identifies the marker.
                slots.update { map: Map<String, Slot> ->
                    val now: Slot? = map[key]
                    if (now == null || !now.deleting || now.owner !== claim) {
                        map
                    } else {
                        val next: Slot? = now.withoutOwner()
                        if (next == null) map - key else map + (key to next)
                    }
                }
            }
            return true
        }
    }

    /**
     * Lets go of every entry [claim] holds. Where it owns the path, the claim it displaced (if any)
     * owns it again; where it was displaced itself, it is simply forgotten. The owner of a slot with a
     * delete in progress stays until that delete's own finally pops it.
     */
    fun release(claim: OutputPathClaim) {
        slots.update { map: Map<String, Slot> ->
            if (map.values.none { slot: Slot -> slot.owner === claim || claim in slot.displaced }) {
                map
            } else {
                val next: MutableMap<String, Slot> = map.toMutableMap()
                for ((key: String, slot: Slot) in map) {
                    when {
                        slot.owner === claim && slot.deleting -> Unit
                        slot.owner === claim -> {
                            val without: Slot? = slot.withoutOwner()
                            if (without == null) next.remove(key) else next[key] = without
                        }
                        claim in slot.displaced -> next[key] = Slot(slot.owner, slot.displaced - claim, slot.deleting)
                    }
                }
                next
            }
        }
    }
}
