package io.github.jamal_wia.kmptoolkit.outbox.spi

import kotlinx.coroutines.flow.StateFlow

/**
 * A live yes/no precondition that effects can be gated on — "the network is up", "the socket is
 * connected", "the user is signed in".
 *
 * A handler names the constraints it needs by key in
 * [OutboxHandler.constraintKeys][io.github.jamal_wia.kmptoolkit.outbox.OutboxHandler.constraintKeys];
 * the engine is given the providers at construction and matches them up by [key].
 *
 * The engine uses a provider in two ways, and both matter:
 * - it **reads** [satisfied] when deciding whether an item is attemptable in this pass;
 * - it **subscribes** to [satisfied] and triggers a drain on every `false → true` transition, so
 *   an effect waiting for connectivity fires the moment connectivity returns instead of waiting
 *   for the next heartbeat.
 *
 * This module deliberately ships no built-in provider — not even a network one. Connectivity
 * detection belongs to the app (or to `kmptoolkit-platform`, whose `ConnectivityObserver` is two
 * lines away from being a provider), and hardcoding one here would bind every consumer to this
 * library's idea of what "online" means.
 *
 * ## Contract
 *
 * - **[key] is stable and unique** across the providers handed to one engine. Two providers with
 *   the same key is a wiring bug; the engine keeps one and the other silently never applies.
 * - **[satisfied] must be cheap to read.** The engine reads `.value` once per item per drain pass.
 *   Do not compute anything there — cache it in the flow.
 * - **[satisfied] must emit on every change**, in both directions, and must have a sensible
 *   initial value. Prefer an optimistic initial value where the truth is not yet known: a wrong
 *   `true` costs one failed attempt and a backoff, while a wrong `false` stalls every gated effect
 *   until the first real signal arrives.
 * - **[satisfied] is read from arbitrary threads** and collected for the engine's lifetime. Back it
 *   with a `StateFlow` that outlives the engine, or at least the scope you gave the engine.
 *
 * A handler naming a key that no provider supplies is treated as **satisfied**, with an error
 * logged. Failing open is deliberate: a wiring typo that silently stalls a queue forever is far
 * harder to notice than one extra delivery attempt.
 */
public interface ConstraintProvider {

    /**
     * The identifier handlers reference. Matched exactly, case-sensitively, against
     * [OutboxHandler.constraintKeys][io.github.jamal_wia.kmptoolkit.outbox.OutboxHandler.constraintKeys].
     */
    public val key: String

    /** Whether the precondition currently holds. `true` means gated effects may be attempted. */
    public val satisfied: StateFlow<Boolean>
}
