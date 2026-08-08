package io.github.jamal_wia.kmptoolkit.session

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks a coroutine as running inside a given manager's teardown, so a call back into that same
 * manager can be refused instead of deadlocking on its non-reentrant lock.
 *
 * It carries the manager identity rather than a bare flag because two managers in one process are
 * legal — a cleaner of manager A calling `endSession()` on manager B is not reentrancy and must
 * work.
 */
internal class TeardownMarker(
    val manager: SessionManager,
) : AbstractCoroutineContextElement(TeardownMarker) {

    companion object Key : CoroutineContext.Key<TeardownMarker>
}
