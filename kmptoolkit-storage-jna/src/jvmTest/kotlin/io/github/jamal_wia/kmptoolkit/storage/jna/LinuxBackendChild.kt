package io.github.jamal_wia.kmptoolkit.storage.jna

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretLookup

/**
 * The main class of the forked JVMs of the Linux real-store suite. libsecret caches its backend for the life of a process
 * and reads `SECRET_BACKEND` only when it first creates it, so each backend scenario needs a fresh process with its own
 * environment. It prints one `KEY=value` line per fact, which the parent test reads.
 *
 * - `probe`: what the two backend questions answer.
 * - `roundtrip <service attribute>`: store, look up and clear one item (with the file backend selected by the environment,
 *   so that no Secret Service daemon is needed).
 */
internal object LinuxBackendChild {

    @JvmStatic
    fun main(arguments: Array<String>) {
        val calls = JnaSecretServiceCalls(lazy(LinuxPlatform::load))
        when (arguments[0]) {
            "probe" -> {
                println("PORTAL=${describe(calls.backendIsPortal())}")
                println("LOCKED=${describe(calls.defaultCollectionLocked())}")
            }
            "roundtrip" -> {
                val attributes: Map<String, String> = linkedMapOf("service" to arguments[1], "directory" to "0123456789abcdef0123456789abcdef")
                val secret: ByteArray = "c2VjcmV0LWtleS1iYXNlNjQ=".toByteArray()
                val stored = calls.store("KMPToolkit test item", attributes, secret)
                println("STORE=${stored.isSuccess} $stored")
                val found: RawResult<SecretLookup> = calls.lookup(attributes, allowUi = false)
                println("FOUND=${(found as? RawResult.Success)?.value.let { it is SecretLookup.Found && it.secret.contentEquals(secret) }} $found")
                val cleared = calls.clear(attributes)
                println("CLEAR=${cleared.isSuccess} $cleared")
                val after: RawResult<SecretLookup> = calls.lookup(attributes, allowUi = false)
                println("AFTER=${(after as? RawResult.Success)?.value} $after")
            }
            else -> error("unknown mode ${arguments[0]}")
        }
    }

    private fun describe(result: RawResult<Boolean>): String = when (result) {
        is RawResult.Success -> "Success(${result.value})"
        is RawResult.Failure -> "Failure(${result.status})"
    }
}
