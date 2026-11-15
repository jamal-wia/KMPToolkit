package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DpapiCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Paths

/**
 * The Windows half of the FFM binding: DPAPI (`CryptProtectData` / `CryptUnprotectData` of `crypt32.dll`) for the current
 * user, never `LOCAL_MACHINE`, with `CRYPTPROTECT_UI_FORBIDDEN` on every call (plan S11).
 *
 * - **Libraries by absolute path** under `%SystemRoot%\System32` (`crypt32.dll`, and `kernel32.dll` for `LocalFree`), never
 *   by a search path.
 * - **`GetLastError` is captured by the linker** (`Linker.Option.captureCallState`) in the same call, so no other call can
 *   overwrite it between the failing function and the read.
 * - **The output blob is `LocalFree`d in every case**; the plaintext output of `CryptUnprotectData` (the data key) is
 *   zeroed before it is freed, and the native copy of the input key lives in a scope buffer that is wiped on close.
 * - The optional description output of `CryptUnprotectData` is not requested (`NULL`), so there is nothing else to free.
 *
 * A status is `0` or the value of `GetLastError()`; this class makes no decision about it. The 1312 and round-trip rules
 * of plan 4.6 live above the seam.
 */
internal class FfmDpapi(
    private val foreign: Foreign,
    private val ledger: NativeLedger,
    systemRoot: String = defaultSystemRoot(),
) : DpapiCalls {

    private val crypt32: SymbolLookup = foreign.open(systemLibrary(systemRoot, "crypt32.dll"))
    private val kernel32: SymbolLookup = foreign.open(systemLibrary(systemRoot, "kernel32.dll"))

    private val captureLayout: MemoryLayout = Linker.Option.captureStateLayout()
    private val lastErrorOffset: Long = captureLayout.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))

    // BOOL f(DATA_BLOB* in, LPCWSTR/LPWSTR* description, DATA_BLOB* entropy, PVOID reserved, PROMPTSTRUCT* prompt, DWORD flags, DATA_BLOB* out)
    private val cryptDescriptor: FunctionDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
    )
    private val cryptProtectData: Downcall = foreign.downcall(
        crypt32, "CryptProtectData", cryptDescriptor, Linker.Option.captureCallState("GetLastError"),
    )
    private val cryptUnprotectData: Downcall = foreign.downcall(
        crypt32, "CryptUnprotectData", cryptDescriptor, Linker.Option.captureCallState("GetLastError"),
    )
    private val localFree: Downcall =
        foreign.downcall(kernel32, "LocalFree", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))

    override fun protect(data: ByteArray, entropy: ByteArray?): RawResult<ByteArray> =
        transform(cryptProtectData, data, entropy, wipeOutput = false)

    override fun unprotect(blob: ByteArray, entropy: ByteArray?): RawResult<ByteArray> =
        transform(cryptUnprotectData, blob, entropy, wipeOutput = true)

    private fun transform(function: Downcall, input: ByteArray, entropy: ByteArray?, wipeOutput: Boolean): RawResult<ByteArray> =
        ledger.scope().use { scope: NativeScope ->
            val inputBlob: MemorySegment = blob(scope, input)
            val entropyBlob: MemorySegment = if (entropy == null) MemorySegment.NULL else blob(scope, entropy)
            val output: MemorySegment = scope.arena.allocate(DATA_BLOB_SIZE)
            val callState: MemorySegment = scope.arena.allocate(captureLayout)
            val succeeded: Int = function.int(
                callState, inputBlob, MemorySegment.NULL, entropyBlob, MemorySegment.NULL, MemorySegment.NULL,
                CRYPTPROTECT_UI_FORBIDDEN, output,
            )
            val pointer: MemorySegment = output.get(ValueLayout.ADDRESS, POINTER_OFFSET)
            val length: Int = output.get(ValueLayout.JAVA_INT, 0L)
            // Whatever the call allocated is ours to free, success or not; own it before anything else can fail.
            if (pointer.address() != 0L) {
                scope.own(pointer) { value: MemorySegment ->
                    if (wipeOutput && length > 0) value.reinterpret(length.toLong()).fill(0)
                    localFree.invoke(value)
                }
            }
            if (succeeded == 0) {
                val error: Int = callState.get(ValueLayout.JAVA_INT, lastErrorOffset)
                return@use RawResult.Failure(RawStatus(if (error == 0) ERROR_GEN_FAILURE else error))
            }
            if (pointer.address() == 0L || length < 0) return@use RawResult.Failure(RawStatus(ERROR_GEN_FAILURE))
            RawResult.Success(if (length == 0) ByteArray(0) else pointer.reinterpret(length.toLong()).toArray(ValueLayout.JAVA_BYTE))
        }

    /** A `DATA_BLOB { DWORD cbData; BYTE* pbData }` over a wiped-on-close copy of [bytes]. */
    private fun blob(scope: NativeScope, bytes: ByteArray): MemorySegment {
        val buffer: MemorySegment = scope.secret(bytes)
        val blob: MemorySegment = scope.arena.allocate(DATA_BLOB_SIZE)
        blob.set(ValueLayout.JAVA_INT, 0L, bytes.size)
        blob.set(ValueLayout.ADDRESS, POINTER_OFFSET, buffer)
        return blob
    }

    internal companion object {
        /** `CRYPTPROTECT_UI_FORBIDDEN`. */
        const val CRYPTPROTECT_UI_FORBIDDEN: Int = 0x1

        /** `ERROR_GEN_FAILURE`: a call reported failure with no error code (a code of 0 would read as success). */
        const val ERROR_GEN_FAILURE: Int = 31

        /** Size of a `DATA_BLOB` on 64-bit Windows (a DWORD, four bytes of padding, a pointer). */
        private const val DATA_BLOB_SIZE: Long = 16L
        private const val POINTER_OFFSET: Long = 8L

        fun defaultSystemRoot(): String = System.getenv("SystemRoot") ?: System.getenv("windir") ?: "C:\\Windows"

        /** `<root>\System32\<name>`, built with the path API so no separator is hard-coded. */
        fun systemLibrary(systemRoot: String, name: String): String = Paths.get(systemRoot, "System32", name).toString()
    }
}
