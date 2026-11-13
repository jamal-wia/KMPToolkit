package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * A dispatcher that discards everything it is given, for `createVideoPlayer(engine, config, NeverDispatcher)`
 * in the model tier: the shared player's position poller then never ticks, and nothing in a model test
 * depends on real time passing.
 *
 * SOUND ONLY BECAUSE of one fact: `EngineVideoPlayer.startPositionUpdates` is the only place the player
 * launches a coroutine on its dispatcher. Every other transition is serialized by the player's own gate and
 * never touches the dispatcher. A model test therefore reads `engine.positionMs()`, the native model and the
 * type of `stateFlow`'s value, never `Playing.currentPosition`, which only the poller refreshes.
 *
 * The fact is pinned, not assumed: every dispatch is recorded with the stack of the code that made it, and
 * [foreignDispatches] lists those that did not come from `startPositionUpdates`. The model fixture fails a
 * test that has one, so a new launch in the player cannot silently turn into a model test that waits for
 * work that never runs.
 *
 * Shared by every model test of the JVM. Tests of one class run one after another, so the record is cleared
 * by [clear] at the start of each model test, which [fxModel] does.
 */
internal object NeverDispatcher : CoroutineDispatcher() {

    /** One discarded dispatch: where it was made and what asked for it. */
    class Dispatch(val context: CoroutineContext, val stack: List<StackTraceElement>) {
        /** Whether `EngineVideoPlayer.startPositionUpdates` is on the stack of this dispatch. */
        val isPoller: Boolean
            get() = stack.any { it.className == POLLER_CLASS && it.methodName == POLLER_METHOD }

        override fun toString(): String =
            "Dispatch(from ${stack.firstOrNull { !it.className.startsWith("kotlinx.coroutines") && it.className != NeverDispatcher::class.java.name }})"
    }

    private val recorded: CopyOnWriteArrayList<Dispatch> = CopyOnWriteArrayList()

    /** Every dispatch discarded since the last [clear], in order. */
    val dispatches: List<Dispatch> get() = recorded.toList()

    /** The dispatches that were not the position poller's launch; empty in a sound model test. */
    val foreignDispatches: List<Dispatch> get() = recorded.filterNot { it.isPoller }

    /** Forgets the record. */
    fun clear() {
        recorded.clear()
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        recorded.add(Dispatch(context, Throwable().stackTrace.toList()))
    }

    private const val POLLER_CLASS: String = "io.github.jamal_wia.kmptoolkit.video.player.EngineVideoPlayer"
    private const val POLLER_METHOD: String = "startPositionUpdates"
}
