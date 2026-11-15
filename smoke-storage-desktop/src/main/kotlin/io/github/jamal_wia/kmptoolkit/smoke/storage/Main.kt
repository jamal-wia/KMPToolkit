package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.DesktopKeyStore
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageConfig
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.createKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.createSecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.MacKeychainCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.NativeKeyStoreBinding
import io.github.jamal_wia.kmptoolkit.storage.probeDesktopKeyStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
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
 *                                            there is no usable key store, or was NOT RUN because of --probe-only or because the
 *                                            smoke cannot clean up after this key store (a skip, NOT a pass of the secure path)
 *   SMOKE-RESULT: FAIL <reason>              anything else; exit code 1
 *
 * A secure run leaves nothing behind, on success and on failure: the temp directory is deleted, and the key item the secure store
 * made in the OS key store is removed by the app itself (the library's clear() keeps the key by contract); see Cleanup.kt.
 */

/** The one binding name each variant must find in the shrunk jar (the keep rules of the library artifacts keep these). */
private const val FFM_BINDING_CLASS: String = "io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm.FfmNativeKeyStoreBinding"
private const val JNA_BINDING_CLASS: String = "io.github.jamal_wia.kmptoolkit.storage.jna.JnaNativeKeyStoreBinding"

/**
 * The secure store's name is this prefix plus a fresh random suffix on every run, so no consumer and no earlier run can share its OS
 * identity. A run removes the key item it created (see [KeyItemRemoval]) and a failed removal is a failure of the run; the name is
 * unique so that a leftover of a crashed run could never be mistaken for, or collide with, the item of a later run.
 */
private const val SECURE_STORE_NAME_PREFIX: String = "kmptoolkit.smoke.storage.secure"
private const val PLAIN_STORE_NAME: String = "kmptoolkit.smoke.storage.plain"

/** The label of the key item in the OS key store; the Gradle finalizer of the smoke tasks looks for it after a macOS run. */
internal const val ITEM_LABEL: String = "KMPToolkit packaging smoke (safe to delete)"
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

    /** The probe found a key store, but the secure round trip was not run for [why]; not a pass of the secure path. */
    class NotRun(val why: String) : SecureOutcome
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
    // A run stopped from outside (Gradle cancelling the build, `timeout`, Ctrl-C) never reaches the finally below: see Shutdown.kt.
    val shutdown: ShutdownCleanup = ShutdownCleanup(directory)
    shutdown.install()
    try {
        say("SMOKE variant=${options.variant} java=${Runtime.version()} os=${System.getProperty("os.name")} secureRequired=${options.secureRequired}")
        val outcome: SecureOutcome = runBlocking { withTimeout(HARD_BOUND_MILLIS) { run(options, directory, shutdown, ::say) } }
        when (outcome) {
            is SecureOutcome.Passed -> {
                say("secure round trip: PASSED (key store ${outcome.store})")
                verdict = "SMOKE-RESULT: PASS"
            }
            is SecureOutcome.ProbedOnly -> {
                say("secure round trip: NOT RUN (--probe-only); the probe found key store ${outcome.store}")
                verdict = "SMOKE-RESULT: PASS-WITH-SKIP (secure round trip NOT RUN (--probe-only); the probe found ${outcome.store})"
            }
            is SecureOutcome.NotRun -> {
                say("secure round trip: NOT RUN: ${outcome.why}")
                verdict = "SMOKE-RESULT: PASS-WITH-SKIP (secure round trip NOT RUN: ${outcome.why})"
            }
            is SecureOutcome.Skipped -> {
                say("secure round trip: SKIPPED (no key store): ${outcome.why}")
                verdict = "SMOKE-RESULT: PASS-WITH-SKIP (secure round trip SKIPPED (no key store): ${outcome.why})"
            }
        }
        exitCode = 0
    } catch (failure: Throwable) {
        // Every failure that was attached while the run unwound (a cleanup that failed too) is printed: a leftover key item must never be silent.
        failureDetails(failure).forEach(::say)
        verdict = failureVerdict(failure)
        exitCode = 1
    } finally {
        shutdown.deleteDirectory()
    }
    if (directory.exists()) {
        // Something is left behind, and a run that leaves something behind is not a pass.
        say("ALSO FAILED while cleaning up (something is left behind): the temporary directory $directory could not be deleted")
        if (exitCode == 0) {
            verdict = "SMOKE-RESULT: FAIL the temporary directory $directory could not be deleted"
            exitCode = 1
        }
    }
    say(verdict)
    options.resultFile?.let { file: File ->
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(log.toString())
    }
    // Not a return: nothing the library may have left running (a native executor thread) can keep the verdict from ending the process.
    exitProcess(exitCode)
}

private suspend fun run(options: Options, directory: File, shutdown: ShutdownCleanup, say: (String) -> Unit): SecureOutcome {
    checkRunOptions(options.secureRequired, options.probeOnly)
    checkPackaging(options.variant, say)
    checkRemovalSeam(options.variant, say)
    plainRoundTrip(directory, say)
    return secureRoundTrip(directory, options, shutdown, say)
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

/**
 * On macOS, the seam the cleanup uses must work in the shrunk, renamed image too: the binding is created by name and its raw calls are
 * reached through the interface. Checked before any store call, and with no call into the keychain (creating the binding and asking it for
 * its calls does not touch the OS), so a probe-only run proves the removal path is packaged right before a real run needs it.
 */
private fun checkRemovalSeam(variant: String, say: (String) -> Unit) {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return
    loadMacCalls(variant)
    say("packaging: the key item removal seam of the $variant binding is reachable")
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

private suspend fun secureRoundTrip(directory: File, run: Options, shutdown: ShutdownCleanup, say: (String) -> Unit): SecureOutcome {
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
    if (!isRemovalSupported(keyStore)) {
        val why: String = "this smoke can remove the key item it creates only on macOS (Windows has none), and the probe found $keyStore"
        if (required) throw SmokeFailure("$why (secure round trip required)")
        return SecureOutcome.NotRun(why)
    }
    val secureDirectory: File = File(directory, "secure")
    val storeName: String = "$SECURE_STORE_NAME_PREFIX.${UUID.randomUUID().toString().replace("-", "").take(RUN_ID_CHARS)}"
    // Armed BEFORE the store exists, so a kill at any later moment still removes the key item the first put makes. The run uses the same
    // removal (wrapped to run once), so the normal path and the hook never delete twice.
    val removal: KeyItemRemoval = shutdown.arm(keyItemRemovalFor(run.variant, keyStore, storeName, secureDirectory))
    val store: SecureKeyValueStorage = createSecureKeyValueStorage(secureDirectory, StorageConfig(storeName), options)
    // Nothing is left behind on any way out (the data and the key item), and a failing cleanup never hides the first failure: see SecureRun.kt.
    runSecureRoundTrip(
        store = store,
        openSecond = { createSecureKeyValueStorage(secureDirectory, StorageConfig(storeName), options) },
        removal = removal,
    )
    say("cleanup: the store's data and its key item (where $keyStore has one) are removed and the item's absence is confirmed")
    return SecureOutcome.Passed(keyStore)
}

private const val RUN_ID_CHARS: Int = 12

/** Whether the smoke knows how to remove the key item a secure store makes in [keyStore]: macOS has one, Windows (DPAPI) has none. */
private fun isRemovalSupported(keyStore: DesktopKeyStore): Boolean =
    keyStore == DesktopKeyStore.MACOS_FILE_KEYCHAIN || keyStore == DesktopKeyStore.WINDOWS_DPAPI

private fun keyItemRemovalFor(variant: String, keyStore: DesktopKeyStore, storeName: String, secureDirectory: File): KeyItemRemoval = when (keyStore) {
    DesktopKeyStore.MACOS_FILE_KEYCHAIN -> MacKeyItemRemoval(loadMacCalls(variant), MacKeyItem.of(storeName, secureDirectory))
    DesktopKeyStore.WINDOWS_DPAPI -> NoKeyItem
    else -> throw SmokeFailure("no key item removal for $keyStore")
}

/**
 * The macOS raw calls of the binding the packaged app runs on: the same class the library loads by name (a new instance, whose
 * constructor does not call the OS), so the removal exercises the packaged, shrunk binding like the round trip did.
 */
private fun loadMacCalls(variant: String): MacKeychainCalls {
    val className: String = if (variant == "ffm") FFM_BINDING_CLASS else JNA_BINDING_CLASS
    val binding: NativeKeyStoreBinding = Class.forName(className).getDeclaredConstructor().newInstance() as NativeKeyStoreBinding
    return binding.macOs()
}
