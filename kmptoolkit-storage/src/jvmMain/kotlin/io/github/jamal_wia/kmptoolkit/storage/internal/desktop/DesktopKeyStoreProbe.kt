package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.DesktopKeyStore
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Which OS key store a desktop secure store would use now, or why none can (plan 3.4, 4.5-4.7 "Probe"). It never reads,
 * creates or deletes a key: no `copyGenericPassword`, `addGenericPassword`, `deleteGenericPassword`, `lookup`, `store` or
 * `clear`, and no `unprotect` of a store blob (the Windows probe protects and unprotects one throwaway byte). It never
 * prompts: every call is limited to [NativeExecutor.NON_INTERACTIVE_LIMIT] and passes `allowUi = false`, so a probe answers
 * within the limit even when the OS is hung. Failures are typed; a failure outside the tables is
 * `OperationFailed(ENSURE_KEY, key = null)`.
 */
internal class DesktopKeyStoreProbe(
    private val source: BindingSource,
    private val executor: NativeExecutor,
    private val environment: SecureStoreEnvironment,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun probe(options: DesktopSecureStorageOptions): StorageResult<DesktopKeyStore> = withStoreContext(dispatcher) {
        // Blocking: class loading of the binding happens here, once per process, off the caller's thread.
        val selection: BindingSelection = source.select()
        if (selection is BindingSelection.Unsupported) return@withStoreContext StorageResult.Failure(selection.error)
        selection as BindingSelection.Selected
        val caller = NativeCaller(executor, allowUi = false)
        val verdict: Verdict<DesktopKeyStore> = when (selection.os) {
            DesktopOs.MACOS -> when (val accessed: Accessed<MacKeychainCalls> = accessCalls { selection.binding.macOs() }) {
                is Accessed.Ready -> probeMac(accessed.calls, caller, options)
                is Accessed.Unusable -> Verdict.Failed(accessed.error)
            }
            DesktopOs.WINDOWS -> when (val accessed: Accessed<DpapiCalls> = accessCalls { selection.binding.windows() }) {
                is Accessed.Ready -> probeWindows(accessed.calls, caller)
                is Accessed.Unusable -> Verdict.Failed(accessed.error)
            }
            DesktopOs.LINUX -> when (val accessed: Accessed<SecretServiceCalls> = accessCalls { selection.binding.linux() }) {
                is Accessed.Ready -> probeLinux(accessed.calls, caller, options)
                is Accessed.Unusable -> Verdict.Failed(accessed.error)
            }
        }
        when (verdict) {
            is Verdict.Ok -> StorageResult.Success(verdict.value)
            is Verdict.Failed -> StorageResult.Failure(verdict.error)
            is Verdict.NotFoundUnconfirmed -> StorageResult.Failure(verdict.error)
            Verdict.NotFound -> StorageResult.Failure(StorageError.OperationFailed(StorageOperation.ENSURE_KEY))
        }
    }

    private suspend fun probeMac(calls: MacKeychainCalls, caller: NativeCaller, options: DesktopSecureStorageOptions): Verdict<DesktopKeyStore> {
        val outcome: NativeOutcome<Int> = caller.callLimited { calls.probe(false) }
        val status: Int = (outcome as? NativeOutcome.Completed)?.value
            ?: return Verdict.Failed(ErrorMapping.nativeOutcome(outcome, StorageOperation.ENSURE_KEY, null))
        return ErrorMapping.macProbe(status, options.allowUserInteraction)
    }

    private suspend fun probeWindows(calls: DpapiCalls, caller: NativeCaller): Verdict<DesktopKeyStore> {
        val identity = StoreIdentity("kmptoolkit-probe", "0".repeat(32))
        return ErrorMapping.windowsProbe(WindowsBackend(calls, caller).roundTrip(identity, StorageOperation.ENSURE_KEY, null))
    }

    private suspend fun probeLinux(calls: SecretServiceCalls, caller: NativeCaller, options: DesktopSecureStorageOptions): Verdict<DesktopKeyStore> {
        if (!environment.hasSessionBus()) return Verdict.Failed(StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION))
        val lockedOutcome: NativeOutcome<RawResult<Boolean>> = caller.callLimited { calls.defaultCollectionLocked() }
        val locked: Boolean = when (val raw: RawResult<Boolean>? = (lockedOutcome as? NativeOutcome.Completed)?.value) {
            null -> return Verdict.Failed(ErrorMapping.nativeOutcome(lockedOutcome, StorageOperation.ENSURE_KEY, null))
            is RawResult.Failure -> return Verdict.Failed(ErrorMapping.linuxStatus(raw.status, StorageOperation.ENSURE_KEY, null))
            is RawResult.Success -> raw.value
        }
        val portalOutcome: NativeOutcome<RawResult<Boolean>> = caller.callLimited { calls.backendIsPortal() }
        val portal: Boolean = when (val raw: RawResult<Boolean>? = (portalOutcome as? NativeOutcome.Completed)?.value) {
            null -> return Verdict.Failed(ErrorMapping.nativeOutcome(portalOutcome, StorageOperation.ENSURE_KEY, null))
            is RawResult.Failure -> return Verdict.Failed(ErrorMapping.linuxStatus(raw.status, StorageOperation.ENSURE_KEY, null))
            is RawResult.Success -> raw.value
        }
        return ErrorMapping.linuxProbe(locked, portal, options.allowUserInteraction)
    }
}
