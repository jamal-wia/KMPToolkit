package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DpapiCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus

/**
 * The DPAPI functions of `crypt32.dll`. `GetLastError()` is captured by JNA right after each call (the
 * [LastErrorException] declaration), never by a second call that the JVM could disturb in between.
 */
internal interface Crypt32Api : Library {
    @Throws(LastErrorException::class)
    fun CryptProtectData(
        dataIn: Pointer,
        description: Pointer?,
        entropy: Pointer?,
        reserved: Pointer?,
        prompt: Pointer?,
        flags: Int,
        dataOut: Pointer,
    ): Boolean

    @Throws(LastErrorException::class)
    fun CryptUnprotectData(
        dataIn: Pointer,
        descriptionOut: Pointer?,
        entropy: Pointer?,
        reserved: Pointer?,
        prompt: Pointer?,
        flags: Int,
        dataOut: Pointer,
    ): Boolean
}

/** `LocalFree` of `kernel32.dll`, which releases every `DATA_BLOB` output DPAPI allocates. */
internal interface Kernel32Api : Library {
    fun LocalFree(memory: Pointer?): Pointer?
}

/** The two DLLs, loaded by absolute path under `%SystemRoot%\System32` (plan S3: never searched for). */
internal class WindowsPlatform(val crypt32: Crypt32Api, val kernel32: Kernel32Api) {
    internal companion object {
        /** Loads the real DLLs. Throws a [LinkageError] when JNA or a DLL cannot load. */
        fun load(): WindowsPlatform {
            // Force JNA's own native library first, so a failure there is one clear LinkageError.
            Native.POINTER_SIZE
            val system32: String = systemDirectory() + "\\System32\\"
            return WindowsPlatform(
                crypt32 = Native.load(system32 + "crypt32.dll", Crypt32Api::class.java),
                kernel32 = Native.load(system32 + "kernel32.dll", Kernel32Api::class.java),
            )
        }

        private fun systemDirectory(): String =
            System.getenv("SystemRoot")?.takeIf { it.isNotBlank() }
                ?: System.getenv("windir")?.takeIf { it.isNotBlank() }
                ?: "C:\\Windows"
    }
}

/**
 * The Windows binding: DPAPI for the current user only (`CRYPTPROTECT_UI_FORBIDDEN`, never `LOCAL_MACHINE`).
 *
 * - The input blob (the key, for a protect) is copied into a native block that is zeroed and freed in a `finally`.
 * - The output blob is copied out, zeroed and handed to `LocalFree` in a `finally`, also on a failure that left an
 *   allocation behind.
 * - A status is `0`-free: a failure is the `GetLastError()` value, or [ERROR_GEN_FAILURE] when the call failed without one.
 *
 * Calls arrive one at a time from the native-executor thread; this class holds no state between calls.
 */
internal class JnaDpapiCalls(
    private val loaded: Lazy<WindowsPlatform>,
    private val scratch: ScratchMemory = ScratchMemory.Default,
) : DpapiCalls {

    /** The DLLs, loaded by the first call (never by constructing this object). */
    private val platform: WindowsPlatform get() = loaded.value

    override fun protect(data: ByteArray, entropy: ByteArray?): RawResult<ByteArray> =
        run(data, entropy) { input: Pointer, entropyBlob: Pointer?, output: Pointer ->
            platform.crypt32.CryptProtectData(input, null, entropyBlob, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
        }

    override fun unprotect(blob: ByteArray, entropy: ByteArray?): RawResult<ByteArray> =
        run(blob, entropy) { input: Pointer, entropyBlob: Pointer?, output: Pointer ->
            platform.crypt32.CryptUnprotectData(input, null, entropyBlob, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
        }

    private inline fun run(
        input: ByteArray,
        entropy: ByteArray?,
        call: (Pointer, Pointer?, Pointer) -> Boolean,
    ): RawResult<ByteArray> = scratch.withCopy(input) { inputBytes: Memory ->
        withEntropy(entropy) { entropyBlob: Memory? ->
            val inBlob: Memory = blob(inputBytes, input.size)
            val outBlob: Memory = scratch.allocate(BLOB_SIZE).also { it.clear() }
            try {
                var failure: Int? = null
                try {
                    if (!call(inBlob, entropyBlob, outBlob)) failure = ERROR_GEN_FAILURE
                } catch (error: LastErrorException) {
                    failure = if (error.errorCode == 0) ERROR_GEN_FAILURE else error.errorCode
                }
                val allocated: Pointer? = outBlob.getPointer(POINTER_OFFSET)
                // A call that reported a stale error but produced a blob did succeed: the output decides.
                if (allocated != null && (failure == null || outBlob.getInt(0L) > 0)) {
                    RawResult.Success(takeOutput(outBlob, allocated))
                } else {
                    if (allocated != null) release(outBlob, allocated)
                    RawResult.Failure(RawStatus(failure ?: ERROR_GEN_FAILURE))
                }
            } finally {
                inBlob.clear()
                inBlob.close()
                outBlob.clear()
                outBlob.close()
            }
        }
    }

    private inline fun <T> withEntropy(entropy: ByteArray?, block: (Memory?) -> T): T {
        if (entropy == null) return block(null)
        return scratch.withCopy(entropy) { entropyBytes: Memory ->
            val entropyBlob: Memory = blob(entropyBytes, entropy.size)
            try {
                block(entropyBlob)
            } finally {
                entropyBlob.clear()
                entropyBlob.close()
            }
        }
    }

    /** A `DATA_BLOB` (`DWORD cbData; BYTE *pbData`) over [bytes]. */
    private fun blob(bytes: Memory, length: Int): Memory {
        val blob: Memory = scratch.allocate(BLOB_SIZE)
        blob.clear()
        blob.setInt(0L, length)
        blob.setPointer(POINTER_OFFSET, bytes)
        return blob
    }

    private fun takeOutput(outBlob: Memory, allocated: Pointer): ByteArray {
        val length: Int = outBlob.getInt(0L)
        try {
            return if (length > 0) allocated.getByteArray(0L, length) else ByteArray(0)
        } finally {
            release(outBlob, allocated)
        }
    }

    /** Zeroes the system-allocated output and returns it to `LocalFree`. */
    private fun release(outBlob: Memory, allocated: Pointer) {
        val length: Int = outBlob.getInt(0L)
        if (length > 0) allocated.clear(length.toLong())
        platform.kernel32.LocalFree(allocated)
    }

    internal companion object {
        /** `CRYPTPROTECT_UI_FORBIDDEN`: the call never shows a dialog. */
        const val CRYPTPROTECT_UI_FORBIDDEN: Int = 0x1

        /** `ERROR_GEN_FAILURE`: reported when a call failed and Windows recorded no error. */
        const val ERROR_GEN_FAILURE: Int = 31

        // Computed on use, never at class-load time: reading Native.POINTER_SIZE loads JNA's native library, and loading
        // this class must not (the binding is constructed by the selector before any call).

        /** The size of a `DATA_BLOB`: a `DWORD` padded to pointer alignment, then a pointer. */
        val BLOB_SIZE: Long get() = 2L * Native.POINTER_SIZE

        /** The offset of `pbData`. */
        val POINTER_OFFSET: Long get() = Native.POINTER_SIZE.toLong()
    }
}
