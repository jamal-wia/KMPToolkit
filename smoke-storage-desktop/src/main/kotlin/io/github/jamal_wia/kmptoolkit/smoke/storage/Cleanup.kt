package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.MacKeychainCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/*
 * What the smoke leaves behind, and the proof that it leaves nothing. A secure round trip creates one key item in the OS key store
 * (the library's `clear()` removes the data and keeps the key, by contract, and the public API has no way to delete the key), so the
 * smoke removes its own item itself, in the same process that created it: the packaged app owns the item, so no access prompt can
 * stand in the way. The removal goes through the library's binding seam (the public, `@ToolkitInternalApi` interface the library's
 * own bindings implement, which a non-published host may use), never through a second implementation of the OS calls.
 */

/** Runs [body], then [cleanup] on every way out. [cleanup] is told whether [body] succeeded, and runs even when [body] was cancelled. */
internal suspend fun <T> guarded(body: suspend () -> T, cleanup: suspend (succeeded: Boolean) -> Unit): T {
    val result: T = try {
        body()
    } catch (original: Throwable) {
        // The original failure is the verdict's reason: a failing cleanup is added to it, never put in its place.
        try {
            withContext(NonCancellable) { cleanup(false) }
        } catch (secondary: Throwable) {
            if (secondary !== original) original.addSuppressed(secondary)
        }
        throw original
    }
    withContext(NonCancellable) { cleanup(true) }
    return result
}

/** Runs every step even when an earlier one fails; throws the FIRST failure with the others attached as suppressed. */
internal suspend fun runAll(vararg steps: suspend () -> Unit) {
    var first: Throwable? = null
    for (step: suspend () -> Unit in steps) {
        try {
            step()
        } catch (failure: Throwable) {
            val earlier: Throwable? = first
            if (earlier == null) first = failure else if (earlier !== failure) earlier.addSuppressed(failure)
        }
    }
    first?.let { throw it }
}

/** The removal of the key item a secure store of the smoke created in the OS key store. */
internal interface KeyItemRemoval {
    /**
     * Removes the item and proves that it is gone. When [expectPresent] (the round trip succeeded, so the store did create its key)
     * the item MUST be found first: that is the control that the removal addresses the item the library really made, not an
     * identity that drifted from the library's. Otherwise (a failed run) an absent item is fine, because the failure may have come
     * before the key was made.
     */
    fun remove(expectPresent: Boolean)
}

/** Windows: DPAPI wraps the key into the store file's header, so there is no OS item to leave behind. */
internal object NoKeyItem : KeyItemRemoval {
    override fun remove(expectPresent: Boolean) = Unit
}

/**
 * The macOS identity of a secure store's key item, as the library derives it: the service is the store file's name without its
 * `.bin` suffix, NFC-normalized (`<name>.kmptoolkit.securestorage`), the account is `dir-` plus the first 128 bits of SHA-256 over the NFC-normalized
 * canonical path of the store file's directory, as 32 lowercase hex characters. This copies the library's derivation because it is
 * internal; [MacKeyItemRemoval] therefore checks, on every successful run, that the item is found at this identity, and a drift
 * fails the run instead of leaving an item behind unnoticed.
 */
internal class MacKeyItem(val service: String, val account: String) {
    companion object {
        private const val SERVICE_SUFFIX: String = ".kmptoolkit.securestorage"
        private const val DIRECTORY_HEX_BYTES: Int = 16

        /** The identity of the store [storeName] whose file lives in [secureDirectory] (resolved to its canonical path). */
        fun of(storeName: String, secureDirectory: File): MacKeyItem = ofCanonical(storeName, secureDirectory.canonicalFile.path)

        /** The identity of the store [storeName] whose file's directory has the canonical path [canonicalDirectory]. */
        fun ofCanonical(storeName: String, canonicalDirectory: String): MacKeyItem {
            val normalized: String = nfc(canonicalDirectory)
            val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(StandardCharsets.UTF_8))
            val hex: String = digest.take(DIRECTORY_HEX_BYTES).joinToString("") { "%02x".format(it) }
            // The service is NFC too, like the library's (macOS compares names byte for byte, and a decomposed store name would miss the item).
            return MacKeyItem(service = nfc(storeName) + SERVICE_SUFFIX, account = "dir-$hex")
        }

        private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
    }

    override fun toString(): String = "service=$service account=$account"
}

/**
 * Removes [item] through [calls] (the macOS raw calls of the binding that the packaged app runs on), never with a dialog, and proves it
 * is gone with a second read. Every call of one [remove] runs on ONE short-lived daemon thread that is given at most [boundMillis]: the
 * binding contract has one thread at a time, and a wedged keychain must end the run with a verdict instead of hanging it.
 */
internal class MacKeyItemRemoval(
    private val calls: MacKeychainCalls,
    private val item: MacKeyItem,
    private val boundMillis: Long = DEFAULT_BOUND_MILLIS,
) : KeyItemRemoval {

    override fun remove(expectPresent: Boolean) {
        runBounded("key item removal", boundMillis) { removeOnThisThread(expectPresent) }
    }

    private fun removeOnThisThread(expectPresent: Boolean) {
        val before: Boolean = isPresent("before the removal")
        if (expectPresent && !before) {
            throw SmokeFailure(
                "control failed: the secure round trip passed but no key item was found at the identity the smoke derived ($item): " +
                    "the smoke's copy of the library's identity derivation may have drifted, and the item the library made may still be in the " +
                    "login keychain (look for it with: security find-generic-password -l \"$ITEM_LABEL\")",
            )
        }
        val status: Int = calls.deleteGenericPassword(item.service, item.account, allowUi = false)
        if (status != STATUS_OK && status != STATUS_ITEM_NOT_FOUND) {
            throw SmokeFailure("deleting the key item ($item) failed with OSStatus $status; it may still be in the login keychain")
        }
        if (isPresent("after the removal")) {
            throw SmokeFailure("the key item ($item) is still in the login keychain after its removal reported OSStatus $status")
        }
    }

    /** One non-interactive read of the item. The key bytes the read returns are wiped at once: only the answer matters. */
    private fun isPresent(moment: String): Boolean = when (val read: RawResult<ByteArray?> = calls.copyGenericPassword(item.service, item.account, allowUi = false)) {
        is RawResult.Success -> {
            val data: ByteArray? = read.value
            data?.fill(0)
            data != null
        }
        is RawResult.Failure -> {
            if (read.status.code != STATUS_ITEM_NOT_FOUND) throw SmokeFailure("reading the key item $moment ($item) failed with ${read.status}")
            false
        }
    }

    companion object {
        const val DEFAULT_BOUND_MILLIS: Long = 30_000L
        private const val STATUS_OK: Int = 0
        private const val STATUS_ITEM_NOT_FOUND: Int = -25300
    }
}

/**
 * Runs the blocking [block] on a daemon thread and waits at most [millis]. A block that does not finish is abandoned (a daemon thread
 * cannot keep the process alive) and reported as a failure. A failure of the block itself is rethrown as it is.
 */
internal fun <T> runBounded(what: String, millis: Long, block: () -> T): T {
    val future: CompletableFuture<T> = CompletableFuture()
    val thread: Thread = Thread({
        try {
            future.complete(block())
        } catch (failure: Throwable) {
            future.completeExceptionally(failure)
        }
    }, "smoke-$what")
    thread.isDaemon = true
    thread.start()
    try {
        return future.get(millis, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        throw SmokeFailure("$what passed its time limit of $millis ms (a native call may be wedged)")
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }
}
