package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult

/*
 * The secure round trip and its cleanup, apart from Main.kt so the module's own tests reach the very code a packaged run executes (the
 * wiring of the body, the key item removal and the control), with a fake store and a fake keychain behind it.
 */

/**
 * One secure round trip on [store], then the cleanup that leaves nothing behind on any way out: [store] is cleared (data) and [removal]
 * deletes the key item the store made in the OS key store, which `clear()` keeps by contract. [openSecond] opens a second instance over the
 * same file: the key comes back from the OS key store (or its in-process cache) and opens the file on disk.
 *
 * The control of [removal] (the item MUST be found at the identity the smoke derived, otherwise the derivation drifted from the library's and
 * the item the library made would be left behind) is on from the moment the first `put` has succeeded, on a failed run too: from then on
 * the store has made its key, so a missing item is a wrong identity, never "nothing to remove". Before that, a failure may precede the key,
 * and an absent item is fine.
 *
 * A failing cleanup never replaces the failure that came first (it is attached as suppressed, see [guarded]).
 *
 * [stop] is asked before every step of the body, never before the cleanup: once the process is being stopped from outside (the shutdown
 * hook, see Shutdown.kt) the body ends at its next step, with the call in flight finished, and the cleanup then removes whatever that call made.
 */
internal suspend fun runSecureRoundTrip(
    store: SecureKeyValueStorage,
    openSecond: () -> SecureKeyValueStorage,
    removal: KeyItemRemoval,
    stop: StopSignal = NeverStopped,
) {
    var keyCreated: Boolean = false
    guarded(
        body = {
            stop.check()
            store.put("token", "s3cret").orFail("secure put")
            keyCreated = true
            stop.check()
            expect("secure get", "s3cret", store.get("token").orFail("secure get"))
            stop.check()
            expect("secure get (new instance)", "s3cret", openSecond().get("token").orFail("secure get (new instance)"))
            stop.check()
            store.remove("token").orFail("secure remove")
            stop.check()
            expect("secure get after remove", null, store.get("token").orFail("secure get after remove"))
        },
        cleanup = {
            runAll(
                { store.clear().orFail("secure clear") },
                { removal.remove(expectPresent = keyCreated) },
            )
        },
    )
}

internal fun <T> StorageResult<T>.orFail(step: String): T = when (this) {
    is StorageResult.Success -> value
    is StorageResult.Failure -> throw SmokeFailure("$step failed: $error")
}

internal fun expect(step: String, expected: String?, actual: String?) {
    if (expected != actual) throw SmokeFailure("$step: expected ${expected?.let { "a value" } ?: "no value"}, got ${actual?.let { "a different value" } ?: "no value"}")
}
