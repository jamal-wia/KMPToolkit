package io.github.jamal_wia.kmptoolkit.permission

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Where [ActivityResultPermissionRequestHost] gets the prefix of its registry keys.
 *
 * The number keeps two hosts of one process apart. The token keeps a dead process out of the new one:
 * the registry hands a parked answer back by key, and a process rebuilt from saved state would otherwise
 * number its hosts from 1 again and give its first request the key of a dialog the previous process left
 * up. The token is random per process, so that answer can never match a key of this one.
 *
 * Accepted residue: the key of a dialog that was still up when the process died stays in that task's saved
 * state, because nothing in the new process answers or unregisters it. Every key of a dialog that was
 * answered is released again (the host posts the unregister past the registry's dispatch), so the registry
 * does not grow with the number of hosts or of process generations.
 *
 * A [MutableStateFlow] updated with `updateAndGet`, not an atomic (D-21): the constructor of the host that
 * asks for a prefix is not suspending, and the update is one atomic step.
 */
internal object PermissionHostKeys {

    private data class Numbering(val token: String, val count: Int)

    private val numbering: MutableStateFlow<Numbering> = MutableStateFlow(Numbering(newToken(), 0))

    /** The next host's prefix: process token and host number. */
    fun nextPrefix(): String {
        val next: Numbering = numbering.updateAndGet { it.copy(count = it.count + 1) }
        return "io.github.jamal_wia.kmptoolkit.permission.host#${next.token}-${next.count}"
    }

    /** Test seam: what a freshly started process looks like, a new token and numbering from the start. */
    internal fun restartProcessForTest() {
        numbering.value = Numbering(newToken(), 0)
    }

    private fun newToken(): String = UUID.randomUUID().toString()
}
