package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.DesktopKeyStore
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageConfig
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.createKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.createSecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.probeDesktopKeyStore
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/*
 * Packaging smoke of the desktop storage (plan 6.7 P). Not a test and not published: the host app of a Compose Desktop
 * release distribution (jlink runtime image, ProGuard with obfuscation on) that runs ONE secure round trip through the
 * real native binding and ONE plain round trip, then exits. Everything it checks is something a unit test cannot see:
 * that the packaged, shrunk and renamed artifacts still find and call the binding.
 *
 * Output contract (the Gradle tasks :smoke-storage-desktop:smokeFfm / smokeJna print this file):
 *   SMOKE-RESULT: PASS                       both round trips ran
 *   SMOKE-RESULT: PASS-WITH-SKIP (...)       the plain round trip ran; the secure one was SKIPPED because the probe says
 *                                            there is no usable key store, or was NOT RUN because of --probe-only (a skip,
 *                                            NOT a pass of the secure path)
 *   SMOKE-RESULT: FAIL <reason>              anything else; exit code 1
 */

/** The one binding name each variant must find in the shrunk jar (the keep rules of the library artifacts keep these). */
private const val FFM_BINDING_CLASS: String = "io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm.FfmNativeKeyStoreBinding"
private const val JNA_BINDING_CLASS: String = "io.github.jamal_wia.kmptoolkit.storage.jna.JnaNativeKeyStoreBinding"

/** One fixed store name: the key item a secure run leaves in the OS key store is reused by the next run, never piled up. */
private const val SECURE_STORE_NAME: String = "kmptoolkit.smoke.storage.secure"
private const val PLAIN_STORE_NAME: String = "kmptoolkit.smoke.storage.plain"
private const val ITEM_LABEL: String = "KMPToolkit packaging smoke (safe to delete)"
private const val HARD_BOUND_MILLIS: Long = 120_000L

private class Options(val variant: String, val resultFile: File?, val secureRequired: Boolean, val probeOnly: Boolean) {
    companion object {
        fun parse(args: Array<String>): Options {
            fun value(name: String): String? = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
            return Options(
                variant = requireNotNull(value("variant")) { "--variant=ffm|jna is required" },
                resultFile = value("result-file")?.let { File(it) },
                secureRequired = args.contains("--secure-required"),
                probeOnly = args.contains("--probe-only"),
            )
        }
    }
}

/** What the secure half did: it ran and passed, or it was skipped because no key store can serve this session. */
private sealed interface SecureOutcome {
    class Passed(val store: DesktopKeyStore) : SecureOutcome
    class Skipped(val why: String) : SecureOutcome

    /** Only the key-store probe ran (it never prompts and never touches a key); no data was put or read. */
    class ProbedOnly(val store: DesktopKeyStore) : SecureOutcome
}

public fun main(args: Array<String>) {
    val options: Options = Options.parse(args)
    val log: StringBuilder = StringBuilder()
    fun say(line: String) {
        println(line)
        log.append(line).append('\n')
    }
    var verdict: String
    var exitCode: Int
    val directory: File = Files.createTempDirectory("kmptoolkit-smoke-").toFile()
    try {
        say("SMOKE variant=${options.variant} java=${Runtime.version()} os=${System.getProperty("os.name")} secureRequired=${options.secureRequired}")
        val outcome: SecureOutcome = runBlocking { withTimeout(HARD_BOUND_MILLIS) { run(options, directory, ::say) } }
        when (outcome) {
            is SecureOutcome.Passed -> {
                say("secure round trip: PASSED (key store ${outcome.store})")
                verdict = "SMOKE-RESULT: PASS"
            }
            is SecureOutcome.ProbedOnly -> {
                say("secure round trip: NOT RUN (--probe-only); the probe found key store ${outcome.store}")
                verdict = "SMOKE-RESULT: PASS-WITH-SKIP (secure round trip NOT RUN (--probe-only); the probe found ${outcome.store})"
            }
            is SecureOutcome.Skipped -> {
                say("secure round trip: SKIPPED (no key store): ${outcome.why}")
                verdict = "SMOKE-RESULT: PASS-WITH-SKIP (secure round trip SKIPPED (no key store): ${outcome.why})"
            }
        }
        exitCode = 0
    } catch (failure: Throwable) {
        verdict = "SMOKE-RESULT: FAIL ${failure::class.java.name}: ${failure.message}"
        exitCode = 1
    } finally {
        directory.deleteRecursively()
    }
    say(verdict)
    options.resultFile?.let { file: File ->
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(log.toString())
    }
    // Not a return: nothing the library may have left running (a native executor thread) can keep the verdict from ending the process.
    exitProcess(exitCode)
}

private suspend fun run(options: Options, directory: File, say: (String) -> Unit): SecureOutcome {
    checkRunOptions(options.secureRequired, options.probeOnly)
    checkPackaging(options.variant, say)
    plainRoundTrip(directory, say)
    return secureRoundTrip(directory, options, say)
}

/** Facts about the packaged runtime that no store call reports: the runtime level, the native-access flag, the binding names. */
private fun checkPackaging(variant: String, say: (String) -> Unit) {
    val feature: Int = Runtime.version().feature()
    val ffmLoadable: Boolean = isLoadable(FFM_BINDING_CLASS)
    val jnaLoadable: Boolean = isLoadable(JNA_BINDING_CLASS)
    // Read BEFORE any native call: the JDK enables native access implicitly (with a warning) at the first restricted call.
    val nativeAccess: Boolean? = nativeAccessEnabledForThisApp()
    say("packaging: runtime=$feature ffmBindingLoadable=$ffmLoadable jnaBindingLoadable=$jnaLoadable nativeAccessFlag=$nativeAccess")
    when (variant) {
        "ffm" -> {
            if (feature < 22) throw SmokeFailure("variant ffm must be packaged with JDK 22 or newer, the runtime image is $feature")
            if (!ffmLoadable) throw SmokeFailure("the FFM binding class is not loadable by name from the shrunk jar (keep rule missing or class removed)")
            if (jnaLoadable) throw SmokeFailure("variant ffm must not carry the JNA artifact")
            if (nativeAccess != true) throw SmokeFailure("--enable-native-access=ALL-UNNAMED is not in effect in the packaged launcher")
        }
        "jna" -> {
            if (feature >= 22) throw SmokeFailure("variant jna must be packaged with JDK 21, the runtime image is $feature")
            if (!jnaLoadable) throw SmokeFailure("the JNA binding class is not loadable by name from the shrunk jar (keep rule missing or class removed)")
        }
        else -> throw SmokeFailure("unknown variant '$variant'")
    }
}

private fun isLoadable(name: String): Boolean = try {
    Class.forName(name, false, Options::class.java.classLoader)
    true
} catch (_: ClassNotFoundException) {
    false
}

/** `Module.isNativeAccessEnabled()` of the app's module, `null` on a runtime older than JDK 22 (the method does not exist there). */
private fun nativeAccessEnabledForThisApp(): Boolean? = try {
    Module::class.java.getMethod("isNativeAccessEnabled").invoke(Options::class.java.module) as Boolean
} catch (_: NoSuchMethodException) {
    null
}

private suspend fun plainRoundTrip(directory: File, say: (String) -> Unit) {
    val plainDirectory: File = File(directory, "plain")
    val config: StorageConfig = StorageConfig(PLAIN_STORE_NAME)
    val store: KeyValueStorage = createKeyValueStorage(plainDirectory, config)
    store.put("greeting", "hello").orFail("plain put")
    expect("plain get", "hello", store.get("greeting").orFail("plain get"))
    // A second instance over the same file: the value really reached the disk, not an instance cache.
    expect("plain get (new instance)", "hello", createKeyValueStorage(plainDirectory, config).get("greeting").orFail("plain get (new instance)"))
    store.remove("greeting").orFail("plain remove")
    expect("plain get after remove", null, store.get("greeting").orFail("plain get after remove"))
    store.clear().orFail("plain clear")
    say("plain round trip: PASSED")
}

private suspend fun secureRoundTrip(directory: File, run: Options, say: (String) -> Unit): SecureOutcome {
    val required: Boolean = run.secureRequired
    // No dialog, ever: a smoke run must not block on a prompt nobody is looking at.
    val options: DesktopSecureStorageOptions = DesktopSecureStorageOptions(itemLabel = ITEM_LABEL, allowUserInteraction = false)
    val keyStore: DesktopKeyStore = when (val probe: StorageResult<DesktopKeyStore> = probeDesktopKeyStore(options)) {
        is StorageResult.Success -> probe.value
        is StorageResult.Failure -> when (val decision: ProbeFailureDecision = decideProbeFailure(probe.error, required)) {
            is ProbeFailureDecision.Skip -> return SecureOutcome.Skipped(decision.why)
            is ProbeFailureDecision.Fail -> throw SmokeFailure(decision.reason)
        }
    }
    say("probe: key store $keyStore")
    if (run.probeOnly) return SecureOutcome.ProbedOnly(keyStore)
    val secureDirectory: File = File(directory, "secure")
    val config: StorageConfig = StorageConfig(SECURE_STORE_NAME)
    val store: SecureKeyValueStorage = createSecureKeyValueStorage(secureDirectory, config, options)
    try {
        store.put("token", "s3cret").orFail("secure put")
        expect("secure get", "s3cret", store.get("token").orFail("secure get"))
        // A second instance: the key comes back from the OS key store (or its in-process cache) and opens the file on disk.
        val second: SecureKeyValueStorage = createSecureKeyValueStorage(secureDirectory, config, options)
        expect("secure get (new instance)", "s3cret", second.get("token").orFail("secure get (new instance)"))
        store.remove("token").orFail("secure remove")
        expect("secure get after remove", null, store.get("token").orFail("secure get after remove"))
    } finally {
        // Removes the data; the key item stays in the OS key store on purpose (reused by the next run, named by ITEM_LABEL).
        store.clear().orFail("secure clear")
    }
    return SecureOutcome.Passed(keyStore)
}

private fun <T> StorageResult<T>.orFail(step: String): T = when (this) {
    is StorageResult.Success -> value
    is StorageResult.Failure -> throw SmokeFailure("$step failed: $error")
}

private fun expect(step: String, expected: String?, actual: String?) {
    if (expected != actual) throw SmokeFailure("$step: expected ${expected?.let { "a value" } ?: "no value"}, got ${actual?.let { "a different value" } ?: "no value"}")
}
