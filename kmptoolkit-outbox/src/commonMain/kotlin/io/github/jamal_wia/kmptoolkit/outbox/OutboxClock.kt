package io.github.jamal_wia.kmptoolkit.outbox

/**
 * Where the engine reads the current time — the seam that keeps every timing decision testable.
 *
 * Backoff gates and delivery leases are persisted as absolute epoch milliseconds, because they have
 * to survive process death and a monotonic reading does not. That makes them wall-clock values,
 * with everything that implies: the user can change the device clock, and a network time sync can
 * step it backwards. The engine defends against a backwards jump (see
 * [OutboxConfig.clockAnomalyFactor]); a forwards jump merely runs some items early, which is
 * harmless.
 *
 * Substitute your own to test lease expiry and backoff without waiting for real time, alongside
 * `runTest`'s virtual time for the delays themselves.
 */
public fun interface OutboxClock {

    /**
     * The current time as milliseconds since the Unix epoch.
     *
     * Called several times per drain pass, so it must be cheap. It must not suspend and must not
     * throw.
     */
    public fun nowEpochMillis(): Long

    /** The default clock, reading the platform's wall clock. */
    public companion object {

        /** `System.currentTimeMillis()` on Android, `NSDate` on iOS. */
        public val System: OutboxClock = OutboxClock { currentEpochMillis() }
    }
}

/** The platform's wall clock, in milliseconds since the Unix epoch. */
internal expect fun currentEpochMillis(): Long

/** A fresh unique id for a queued item — a random UUID in its canonical string form. */
internal expect fun randomOutboxItemId(): String
